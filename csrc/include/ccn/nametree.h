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
 * content object, without the danger of an undetected dangling reference
 * when the in-memory content handle is destroyed.  This is for internal
 * data structures such as queues or enumeration states, but should not
 * be stored in any long-term way.  Use a ccnr_accession, content name, or
 * digest for that.
 *
 * Holding a cookie does not prevent the in-memory content handle from being
 * destroyed, either explicitly or to conserve resources.
 *
 * The value 0 is used to denote no entry.
 */
typedef unsigned ccn_cookie;

struct ccn_nametree;
struct ccny;

struct ccn_nametree {
    ccn_cookie cookie;      /**< newest used cookie number */
    unsigned cookiemask;    /**< one less than a power of two */
    struct ccny **nmentry_by_cookie; /**< for direct lookup by cookie */
    struct ccny *last;      /**< link to last */
    int skipdim;            /**< dimension of skiplinks array */
    ccn_cookie *skiplinks;  /**< skiplist for name-ordered ops */
};

/**
 *  A nametree entry
 *
 * Each entry is capable of representing a name prefix, a
 * content object, or both.  A name prefix is useful for keeping
 * track of PIT entries, FIB entries, statistics used by
 * the strategy layer, name enumeration, and creation/deletion
 * notifications.
 *
 * To accomplish this, the nametree nodes are linked into several
 * data structures.  One of these is a skiplist, so that we can
 * quickly access the first node that has a given prefix.  Use of the base
 * layer of the skiplist links also allows for rapid forward traversal.
 * There is a linked list of the nodes in reverse order, so backward
 * traversal is fast as well.
 */

struct ccny {
    ccn_cookie cookie;      /**< cookie for this entry */
    struct ccn_charbuf *flatname; /**< for skiplist, et. al. */
    struct ccny *prev;      /**< link to previous, in name order */
    int skipdim;            /**< dimension of skiplinks array */
    ccn_cookie skiplinks[1];  /**< skiplist links (flex array) */
};

struct ccn_nametree *ccn_nametree_create(void);

struct ccny *ccny_create(struct ccn_nametree *h, unsigned rb);

struct ccny *ccny_from_cookie(struct ccn_nametree *h, ccn_cookie cookie);

ccn_cookie ccn_nametree_lookup(struct ccn_nametree *h,
                               const unsigned char *key, size_t size);

int ccny_enroll(struct ccn_nametree *h, struct ccny *y);

void ccn_nametree_delete_entry(struct ccn_nametree *h, struct ccny **y);

void ccn_nametree_destroy(struct ccn_nametree **ph);

#endif
