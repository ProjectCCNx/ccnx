/**
 * @file include/ccn/nametree.h
 *
 */
/*
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

#ifndef CCN_NAMETREE_DEFINED
#define CCN_NAMETREE_DEFINED

/**
 * A cookie is used as a more ephemeral way of holding a reference to a
 * nametree entry, without the danger of an undetected dangling reference
 * when the entry is destroyed.  This is useful for internal
 * data structures such as queues or enumeration states.
 *
 * Holding a cookie does not prevent the entry from being
 * destroyed, either explicitly or to conserve resources.
 *
 * The value 0 is used to denote no entry.
 */
typedef unsigned ccn_cookie;

struct ccn_nametree;
struct ccny;

/**
 *  Procedure type for several optional client callbacks.
 */
typedef void (*ccn_nametree_action)(struct ccn_nametree *, struct ccny *);

/**
 *  Procedure type for comparison callback used for ordering
 *
 * The result is tested for negative, zero, or positive,
 * meaning less, equal, or greater.
 *
 * The default is to use ccn_flatname_compare().
 */
typedef int (*ccn_nametree_compare)(const unsigned char *, size_t,
                                    const unsigned char *, size_t);

struct ccn_nametree {
    int n;                  /**< number of enrolled entries */
    int limit;              /**< recommended maximum n */
    ccn_cookie cookie;      /**< newest used cookie number */
    unsigned cookiemask;    /**< one less than a power of two */
    struct ccny **nmentry_by_cookie; /**< for direct lookup by cookie */
    struct ccny *head;      /**< head for skiplist, etc. */
    void *data;             /**< for client use */
    ccn_nametree_compare compare; /**< for comparison */
    ccn_nametree_action post_enroll; /**< called after enroll */
    ccn_nametree_action pre_remove; /**< called before removal */
    ccn_nametree_action check; /**< called to check client structures */
    ccn_nametree_action finalize; /**< called from destroy */
};

struct ccn_nametree *ccn_nametree_create(int initial_limit);

/* reasonably good random bits must be provided, crypto quality not needed */
struct ccny *ccny_create(unsigned randombits, size_t payload_size);

/* Consult the documentation about the difference between the next two */
int ccny_set_key(struct ccny *y, const unsigned char *key, size_t size);
void ccny_set_key_fields(struct ccny *y, unsigned char *key, unsigned size);

struct ccny *ccny_from_cookie(struct ccn_nametree *h, ccn_cookie cookie);

struct ccny *ccn_nametree_look_lt(struct ccn_nametree *h,
                                  const unsigned char *key, size_t size);

struct ccny *ccn_nametree_look_le(struct ccn_nametree *h,
                                  const unsigned char *key, size_t size);

struct ccny *ccn_nametree_lookup(struct ccn_nametree *h,
                                 const unsigned char *key, size_t size);

struct ccny *ccn_nametree_look_gt(struct ccn_nametree *h,
                                  const unsigned char *key, size_t size);

struct ccny *ccn_nametree_look_ge(struct ccn_nametree *h,
                                  const unsigned char *key, size_t size);

int ccn_nametree_grow(struct ccn_nametree *h);

/* Consult documentation about the return value to avoid surprises */
ccn_cookie ccny_enroll(struct ccn_nametree *h, struct ccny *y);

void ccny_remove(struct ccn_nametree *h, struct ccny *y);

void ccny_destroy(struct ccn_nametree *h, struct ccny **py);

void ccn_nametree_destroy(struct ccn_nametree **ph);

void ccn_nametree_check(struct ccn_nametree *h);

/* Accessors */
int ccn_nametree_n(struct ccn_nametree *h);
int ccn_nametree_limit(struct ccn_nametree *h);
ccn_cookie ccny_cookie(struct ccny *y);
void *ccny_payload(struct ccny *y);
void ccny_set_payload(struct ccny *y, void *payload);
const unsigned char *ccny_key(struct ccny *y);
unsigned ccny_keylen(struct ccny *y);
unsigned ccny_info(struct ccny *y);
void ccny_set_info(struct ccny *y, unsigned info);
struct ccny *ccn_nametree_first(struct ccn_nametree *h);
struct ccny *ccny_next(struct ccny *y);
struct ccny *ccny_prev(struct ccny *y);
struct ccny *ccn_nametree_last(struct ccn_nametree *h);

#endif

#ifdef CCN_NAMETREE_IMPL

/**
 *  A nametree entry
 *
 * The nametree is designed for rapid lookup of data by name prefix.
 * This can be useful for keeping track of such things as
 * PIT entries, FIB entries, statistics used by the strategy layer,
 * name enumeration, and creation/deletion notifications.
 *
 * To accomplish this, the nametree nodes are linked into several
 * data structures.  One of these is a skiplist, so that we can
 * quickly access the first node that has a given prefix.  Use of the base
 * layer of the skiplist links also allows for rapid forward traversal.
 * There is a linked list of the nodes in reverse order, so backward
 * traversal and removal is fast as well.
 *
 * Clients should normally use the procedural interface, so the details
 * of struct ccny are only exposed if CCN_NAMETREE_IMPL is defined before
 * inclusion of the header file.
 *
 */
struct ccny {
    struct ccny *prev;      /**< link to previous, in name order */
    unsigned char *key;     /**< for skiplist, et. al. */
    unsigned keylen;        /**< size of key, in bytes */
    ccn_cookie cookie;      /**< cookie for this entry */
    void *payload;          /**< client payload */
    unsigned info;          /**< for client use */
    unsigned short prv;     /**< not for client use */
    short skipdim;          /**< dimension of skiplinks array */
    struct ccny *skiplinks[1]; /**< skiplist links (flex array) */
};

#endif

