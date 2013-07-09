/**
 * @file lib/ccn_nametree.c
 */ 
/* Part of the CCNx C Library.
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

#include <stdlib.h>
#include <string.h>
#include <ccn/charbuf.h>
#include <ccn/flatname.h>
#define CCN_NAMETREE_IMPL
#include <ccn/nametree.h>

#define CCN_SKIPLIST_MAX_DEPTH 16
#define NAMETREE_PVT_PAYLOAD_OWNED 0x40

/**
 *  Create a new, empty nametree
 *
 * The initial_limit is the number of entries that may be
 * inserted before growing the table.
 */
struct ccn_nametree *
ccn_nametree_create(int initial_limit)
{
    struct ccn_nametree *h;
    ccn_cookie c;
    
    if (initial_limit < 6)
        initial_limit = 6;
    h = calloc(1, sizeof(*h));
    if (h != NULL) {
        h->n = 0;
        h->head = ccny_create(0, 0);
        if (h->head == NULL) {
            free(h);
            return(NULL);
        }
        if (h->head->skipdim != CCN_SKIPLIST_MAX_DEPTH) abort();
        h->head->skipdim = 1;
        h->head->skiplinks[0] = NULL;
        for (c = (~0U) / 2; c / 2 - c / 8 > initial_limit;)
            c = c / 2;
        h->cookiemask = c;
        h->limit = c - c / 4;
        h->nmentry_by_cookie = calloc(c + 1, sizeof(struct ccny *));
        if (h->nmentry_by_cookie == NULL) {
            free(h->head);
            free(h);
            return(NULL);
        }
        h->data = NULL;
        h->post_enroll = 0;
        h->pre_remove = 0;
        h->check = 0;
        h->finalize = 0;
        h->compare = &ccn_flatname_compare;
    }
    return(h);
}

/**
 *  Create a new nametree entry, not hooked up to anything
 *
 * The skiplinks array needs to be sized with an appropriate random
 * distribution; for this purpose the caller must provide a word of
 * random bits.
 *
 * If payload_size is non-zero, extra zero-initialized space will
 * be allocated, and a pointer to it provided in the payload field.
 * This will be automatically freed when the entry is destroyed.
 *
 * If the payload size is zero, the caller assumes resonsibility
 * for managing the payload memory, probably by providing a suitable
 * finalize action.
 */
struct ccny *
ccny_create(unsigned randombits, size_t payload_size)
{
    struct ccny *y = NULL;
    int d;
    unsigned rb;
    size_t base_size;
    
    for (d = 1, rb = randombits; d < CCN_SKIPLIST_MAX_DEPTH; d++, rb >>= 2)
        if ((rb & 3) != 0) break;
    base_size = sizeof(*y) + (d - 1) * sizeof(y->skiplinks[0]);
    if (payload_size > 0)
        base_size = (base_size + 7) / 8 * 8; /* 8-byte alignment */
    y = calloc(1, base_size + payload_size);
    if (y == NULL)
        return(y);
    y->cookie = 0;
    y->prev = NULL;
    y->payload = NULL;
    y->skipdim = d;
    if (payload_size > 0) {
        y->payload = ((unsigned char *)y) + base_size;
        y->prv |= NAMETREE_PVT_PAYLOAD_OWNED;
    }
    return(y);
}

/**
 *  Set the key in a nametree entry
 *
 * This makes a copy.  The entry must not be in a nametree.
 * Any old key is freed before making the copy.
 *
 * A client may choose to manage the key storage differently,
 * but in such a case it must provide a finalize action that
 * leaves y->key NULL.
 *
 * @returns -1 for error, 0 for success.
 */
int
ccny_set_key(struct ccny *y, const unsigned char *key, size_t size)
{
    if (size >= (~0U) / 2)
        return(-1);
    if (y->cookie != 0)
        return(-1);
    if (y->key != NULL) {
        free(y->key);
        y->key = NULL;
        y->keylen = 0;
    }
    if (key == NULL)
        return(0);
    y->key = malloc(size);
    if (y->key == NULL)
        return(-1);
    memcpy(y->key, key, size);
    y->keylen = size;
    return(0);
}

/**
 *  Directly assign the key and keylen in a nametree entry
 *
 *  This is for clients that are handling their own memory management.
 */

void
ccny_set_key_fields(struct ccny *y, unsigned char *key, unsigned size)
{
    y->key = key;
    y->keylen = size;
}

/**
 *  Look up an entry, given a cookie.
 */
struct ccny *
ccny_from_cookie(struct ccn_nametree *h, ccn_cookie cookie)
{
    struct ccny *ans;
    
    ans = h->nmentry_by_cookie[cookie & h->cookiemask];
    if (ans != NULL && ans->cookie == cookie)
        return(ans);
    return(NULL);
}

/** 
 *  Find the entry, or the one just before where it would go
 *
 * The ans array is populated with pointers to the skiplinks
 * at each level.
 *
 * @returns 1 if an exact match was found
 */
static int
ccny_skiplist_findbefore(struct ccn_nametree *h,
                         const unsigned char *key,
                         size_t size,
                         struct ccny **ans)
{
    int i;
    struct ccny *c;
    struct ccny *y;
    int order = -1;
    ccn_nametree_compare cmp = h->compare;
    
    
    c = h->head;
    for (i = h->head->skipdim - 1; i >= 0; i--) {
        for (;;) {
            y = c->skiplinks[i];
            if (y == NULL)
                break;
            order = (*cmp)(y->key, y->keylen, key, size);
            if (order >= 0)
                break;
            if (i >= y->skipdim) abort();
            c = y;
        }
        ans[i] = c;
    }
    return(order == 0);
}

/**
 *  Look for an entry with a key less than the given key
 *
 * When there are multiple possibilities, the one with the largest
 * key is returned.  Returns NULL if nothing matches.
 */
struct ccny *
ccn_nametree_look_lt(struct ccn_nametree *h,
                     const unsigned char *key, size_t size)
{
    struct ccny *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    
    ccny_skiplist_findbefore(h, key, size, pred);
    if (pred[0] == h->head)
        return(NULL);
    return(pred[0]);
}

/**
 *  Look for an entry with a key less than or equal to the given key
 *
 * When there are multiple possibilities, the one with the largest
 * key is returned.  Returns NULL if nothing matches.
 */
struct ccny *
ccn_nametree_look_le(struct ccn_nametree *h,
                     const unsigned char *key, size_t size)
{
    struct ccny *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    int found;
    
    found = ccny_skiplist_findbefore(h, key, size, pred);
    if (found)
        return(pred[0]->skiplinks[0]);
    if (pred[0] == h->head)
        return(NULL);
    return(pred[0]);
}

/**
 *  Look for an entry with a key equal to given key
 */
struct ccny *
ccn_nametree_lookup(struct ccn_nametree *h,
                    const unsigned char *key, size_t size)
{
    struct ccny *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    int found;
    
    found = ccny_skiplist_findbefore(h, key, size, pred);
    if (found)
        return(pred[0]->skiplinks[0]);
    return(NULL);
}

/**
 *  Look for an entry with a key greater than or equal to the given key
 *
 * When there are multiple possibilities, the one with the smallest
 * key is returned.  Returns NULL if nothing matches.
 */
struct ccny *
ccn_nametree_look_ge(struct ccn_nametree *h,
                     const unsigned char *key, size_t size)
{
    struct ccny *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    
    ccny_skiplist_findbefore(h, key, size, pred);
    return(pred[0]->skiplinks[0]);
}

/**
 *  Look for an entry with a key greater than the given key
 *
 * When there are multiple possibilities, the one with the smallest
 * key is returned.  Returns NULL if nothing matches.
 */
struct ccny *
ccn_nametree_look_gt(struct ccn_nametree *h,
                     const unsigned char *key, size_t size)
{
    struct ccny *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    int found;
    
    found = ccny_skiplist_findbefore(h, key, size, pred);
    if (found)
        return(pred[0]->skiplinks[0]->skiplinks[0]);
    return(pred[0]->skiplinks[0]);
}

/**
 *  Insert an entry into the skiplist
 *
 * @returns old cookie and does not insert if an exact key match is found
 */
static ccn_cookie
ccny_skiplist_insert(struct ccn_nametree *h, struct ccny *y)
{
    struct ccny *next = NULL;
    struct ccny *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    int found;
    int i;
    int d;
    int skipdim;
    
    d = y->skipdim;
    skipdim = h->head->skipdim;
    while (h->head->skipdim < d)
        h->head->skiplinks[h->head->skipdim++] = NULL;
    found = ccny_skiplist_findbefore(h, y->key, y->keylen, pred);
    if (found) {
        h->head->skipdim = skipdim;
        return(pred[0]->skiplinks[0]->cookie);
    }
    for (i = 0; i < d; i++) {
        y->skiplinks[i] = pred[i]->skiplinks[i];
        pred[i]->skiplinks[i] = y;
    }
    next = y->skiplinks[0];
    if (next == NULL)
        next = h->head;
    y->prev = next->prev;
    next->prev = y;
    return(0);
}

/**
 *  Remove an entry from the skiplist
 *
 * The entry must be present.
 */
static void
ccny_skiplist_remove(struct ccn_nametree *h, struct ccny *y)
{
    struct ccny *next;
    struct ccny *prev;
    struct ccny *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    int i;
    int d;
    
    next = y->skiplinks[0];
    if (next == NULL)
        next = h->head;
    prev = y->prev;
    d = y->skipdim;
    if (next->prev != y) abort();
    next->prev = prev;
    y->prev = NULL;
    if (d == 1 && prev != NULL) {
        prev->skiplinks[0] = y->skiplinks[0];
        y->skiplinks[0] = NULL;
        y->cookie = 0;
        return;
    }
    ccny_skiplist_findbefore(h, y->key, y->keylen, pred);
    if (pred[0]->skiplinks[0] != y) abort();
    d = y->skipdim;
    if (h->head->skipdim < d) abort();
    for (i = 0; i < d; i++) {
        pred[i]->skiplinks[i] = y->skiplinks[i];
        y->skiplinks[i] = NULL;
    }
    for (i = h->head->skipdim - 1; i > 0 && h->head->skiplinks[i] == NULL;)
        h->head->skipdim = i--;
    y->cookie = 0;
}

/**
 *  Enroll an entry into the nametree
 *
 * Although this detects a full table, caller should prevent that from ever
 * happening by trimming or resizing as appropriate, to maintain some
 * percentage of free slots.
 *
 * @returns cookie of old entry in the case that one with the old key is 
 *   present, or 0 upon success or a full table.  The latter case
 *   may be disambiguated by examining y->cookie.
 */
ccn_cookie
ccny_enroll(struct ccn_nametree *h, struct ccny *y)
{
    ccn_cookie cookie;
    unsigned i;
    unsigned lastslot;
    ccn_cookie res;
    
    if (y->cookie != 0)
        abort();
    lastslot = h->cookie & h->cookiemask;
    for (;;) {
        cookie = ++(h->cookie);
        i = cookie & h->cookiemask;
        if (cookie != 0 && h->nmentry_by_cookie[i] == NULL) {
            y->cookie = cookie;
            res = ccny_skiplist_insert(h, y);
            if (res != 0) {
                h->cookie--;
                y->cookie = 0;
                return(res);
            }
            h->nmentry_by_cookie[i] = y;
            h->n += 1;
            if (h->post_enroll)
                (h->post_enroll)(h, y);
            return(0);
        }
        if (i == lastslot)
            return(0);
    }
}

/**
 *  Double the size of the direct lookup table
 *
 * @returns 0 for success, -1 for error.
 */
int
ccn_nametree_grow(struct ccn_nametree *h)
{
    struct ccny **newtab = NULL;
    struct ccny *y = NULL;
    unsigned cookiemask;
    
    cookiemask = 2 * h->cookiemask + 1;
    if (cookiemask > (ccn_cookie)((~0U) / 2))
        return(-1);
    newtab = calloc(cookiemask + 1, sizeof(newtab[0]));
    if (newtab == NULL)
        return(-1);
    for (y = h->head->prev; y != NULL; y = y->prev)
        newtab[cookiemask & y->cookie] = y;
    free(h->nmentry_by_cookie);
    h->nmentry_by_cookie = newtab;
    h->cookiemask = cookiemask;
    h->limit = cookiemask - cookiemask / 4;
    return(0);
}

/**
 *  Remove y from the nametree
 *
 * If y is not in the nametree, nothing is changed.
 * On success, y->cookie is cleared, but y is not freed.
 */
void
ccny_remove(struct ccn_nametree *h, struct ccny *y)
{
    unsigned i;
    
    if (y == NULL)
        return;
    if (y->cookie != 0) {
        i = y->cookie & h->cookiemask;
        if (h->nmentry_by_cookie[i] != y)
            return;
        if (h->pre_remove)
            (h->pre_remove)(h, y);
        ccny_skiplist_remove(h, y);
        y->cookie = 0;
        h->nmentry_by_cookie[i] = NULL;
        h->n -= 1;
    }
}

/**
 * Destroy a nametree entry
 *
 * The entry must not be in any nametree.
 */
void
ccny_destroy(struct ccn_nametree *h, struct ccny **py)
{
    struct ccny *y = *py;
    if (y == NULL)
        return;
    if (y->cookie != 0) abort();
    if (h != NULL && h->finalize)
        (h->finalize)(h, y);
    if (y->key != NULL)
        free(y->key);
    free(y);
    *py = NULL;
}

/**
 * Destroy a nametree, deleting all entries
 */
void
ccn_nametree_destroy(struct ccn_nametree **ph)
{
    struct ccny *x;
    struct ccny *y;
    struct ccn_nametree *h = *ph;
    
    if (h == NULL)
        return;
    for (y = h->head->prev; y != NULL; y = x) {
        x = y->prev;
        ccny_remove(h, y);
        ccny_destroy(h, &y);
    }
    if (h->nmentry_by_cookie != NULL)
        free(h->nmentry_by_cookie);
    free(h->head);
    *ph = NULL;
    free(h);
}

/** Check the nametree for consistency */
void
ccn_nametree_check(struct ccn_nametree *h)
{
    int i, n;
    struct ccny *y = NULL;
    struct ccny *z = NULL;
    
    for (n = 0, i = 0; i <= h->cookiemask; i++) {
        y = h->nmentry_by_cookie[i];
        if (y == NULL)
            continue;
        if (y->cookie == 0) abort();
        if ((y->cookie & h->cookiemask) != i) abort();
        if (y != ccny_from_cookie(h, y->cookie)) abort();
        n++;
    }
    if (n != h->n) abort();
    if (n > h->limit) abort();
    if (h->limit > h->cookiemask) abort();
    for (n = 0, y = h->head->prev; y != NULL; y = y->prev) {
        if (y->prev != NULL) {
            if ((h->compare)(y->prev->key, y->prev->keylen,
                             y->key, y->keylen) >= 0) abort();
            if (y != y->prev->skiplinks[0]) abort();
        }
        else {
            if (y != h->head->skiplinks[0]) abort();
        }
        if (ccn_nametree_look_lt(h, y->key, y->keylen) != y->prev) abort();
        if (ccn_nametree_look_le(h, y->key, y->keylen) != y) abort();
        n++;
    }
    if (n != h->n) abort();
    for (n = 0, y = h->head->skiplinks[0]; y != NULL; y = y->skiplinks[0]) {
        for (i = 0; i < y->skipdim; i++) {
            z = y->skiplinks[i];
            if (z != NULL) {
                if ((h->compare)(y->key, y->keylen,
                                 z->key, z->keylen) >= 0) abort();
            }
        }
        z = y->skiplinks[0];
        if (ccn_nametree_look_gt(h, y->key, y->keylen) != z) abort();
        if (ccn_nametree_look_ge(h, y->key, y->keylen) != y) abort();
        n++;
    }
    if (n != h->n) abort();
    for (i = 1; i < h->head->skipdim; i++)
        if (h->head->skiplinks[i] == NULL) abort();
    if (h->check) {
        for (y = h->head->skiplinks[0]; y != NULL; y = y->skiplinks[0])
            (h->check)(h, y);
    }
}

/** Access the number of entries */
int
ccn_nametree_n(struct ccn_nametree *h)
{
    return(h->n);
}

/** Access the current limit on the number of entries */
int
ccn_nametree_limit(struct ccn_nametree *h)
{
    return(h->limit);
}

/** Access the cookie */
ccn_cookie
ccny_cookie(struct ccny *y)
{
    if (y == NULL)
        return(0);
    return(y->cookie);
}

/** Access the payload */
void *
ccny_payload(struct ccny *y)
{
    return(y->payload);
}

/** Set the payload */
void
ccny_set_payload(struct ccny *y, void *payload)
{
    y->payload = payload;
}


/** Access the key */
const unsigned char *
ccny_key(struct ccny *y)
{
    return(y->key);
}

/** Access the key size */
unsigned
ccny_keylen(struct ccny *y)
{
    return(y->keylen);
}

/** Get the client info */
unsigned
ccny_info(struct ccny *y)
{
    return(y->info);
}

/** Set the client info */
void
ccny_set_info(struct ccny *y, unsigned info)
{
    y->info = info;
}

/** Get the first entry */
struct ccny *
ccn_nametree_first(struct ccn_nametree *h)
{
    return(h->head->skiplinks[0]);
}

/** Get the next entry */
struct ccny *
ccny_next(struct ccny *y)
{
    return(y->skiplinks[0]);
}

/** Get the previous entry */
struct ccny *
ccny_prev(struct ccny *y)
{
    return(y->prev);
}

/** Get the last entry */
struct ccny *
ccn_nametree_last(struct ccn_nametree *h)
{
    return(h->head->prev);
}
