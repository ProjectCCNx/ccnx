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
#include <ccn/charbuf.h>
#include <ccn/flatname.h>
#include <ccn/nametree.h>

#define CCN_SKIPLIST_MAX_DEPTH 16

/**
 * Create a new, empty nametree
 */
struct ccn_nametree *
ccn_nametree_create(void)
{
    struct ccn_nametree *h;
    
    h = calloc(1, sizeof(*h));
    if (h != NULL) {
        h->n = 0;
        h->head = ccny_create(0);
        if (h->head == NULL) {
            free(h);
            return(NULL);
        }
        if (h->head->skipdim != CCN_SKIPLIST_MAX_DEPTH) abort();
        h->head->skipdim = 0;
        // XXX - when we grow flags, mark head as such
        h->cookiemask = 255;
        h->limit = 255 - 255 / 4;
        h->nmentry_by_cookie = calloc(h->cookiemask + 1, sizeof(struct ccny *));
        h->data = NULL;
        h->post_enroll = 0;
        h->pre_remove = 0;
        h->check = 0;
        h->finalize = 0;
    }
    return(h);
}

/**
 * Create a new nametree entry, not hooked up to anything
 *
 * The skiplinks array needs to be sized with an appropriate random
 * distribution; for this purpose the caller must provide a word of
 * random bits in rb.
 */
struct ccny *
ccny_create(unsigned rb)
{
    struct ccny *y;
    int d;
    
    for (d = 1; d < CCN_SKIPLIST_MAX_DEPTH; d++, rb >>= 2)
        if ((rb & 3) != 0) break;
    y = calloc(1, sizeof(*y) + (d - 1) * sizeof(y->skiplinks[0]));
    if (y == NULL)
        return(y);
    
    y->cookie = 0;
    y->prev = NULL;
    y->skipdim = d;
    return(y);
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
    
    c = h->head;
    for (i = h->head->skipdim - 1; i >= 0; i--) {
        for (;;) {
            y = c->skiplinks[i];
            if (y == NULL)
                break;
            order = ccn_flatname_compare(y->flatname->buf, y->flatname->length,
                                         key, size);
            if (order >= 0)
                break;
            if (i >= y->skipdim) abort();
            c = y;
        }
        ans[i] = c;
    }
    return(order == 0);
}

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
 *  Insert an entry into the skiplist
 *
 * @returns -1 and does not insert if an exact key match is found
 */
static int
ccny_skiplist_insert(struct ccn_nametree *h, struct ccny *y)
{
    struct ccny *next = NULL;
    struct ccny *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    struct ccny *z = NULL;
    int found;
    int i;
    int d;
    
    d = y->skipdim;
    while (h->head->skipdim < d)
        h->head->skiplinks[h->head->skipdim++] = NULL;
    found = ccny_skiplist_findbefore(h, y->flatname->buf, y->flatname->length,
                                     pred);
    if (found)
        return(-1);
    for (i = 0; i < d; i++) {
        z = pred[i]->skiplinks[i];
        y->skiplinks[i] = (z && z->cookie) ? z : NULL;
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
    struct ccny *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    int i;
    int d;
    
    next = y->skiplinks[0];
    if (next == NULL)
        next = h->head;
    if (next->prev != y) abort();
    next->prev = y->prev;
    y->prev = NULL;
    ccny_skiplist_findbefore(h, y->flatname->buf, y->flatname->length, pred);
    if (pred[0]->skiplinks[0] != y) abort();
    d = y->skipdim;
    if (h->head->skipdim < d) abort();
    for (i = 0; i < d; i++) {
        pred[i]->skiplinks[i] = y->skiplinks[i];
        y->skiplinks[i] = NULL;
    }
    y->cookie = 0;
}

/**
 *  Enroll an entry into the nametree
 *
 * Although this detects a full table, caller should prevent that from ever
 * happening by trimming or resizing as appropriate, to maintain some
 * percentage of free slots.
 *
 * @returns 1 if an entry with the name is already present,
 *   -1 if table is full, 0 for success.
 */
int
ccny_enroll(struct ccn_nametree *h, struct ccny *y)
{
    ccn_cookie cookie;
    unsigned i;
    unsigned m;
    int res;
    
    /* Require one empty slot so safety belt loop counter won't wrap */
    if (h->n >= h->cookiemask)
        return(-1);
    for (m = h->cookiemask; m != 0; m--) {
        cookie = ++(h->cookie);
        i = cookie & h->cookiemask;
        if (cookie != 0 && h->nmentry_by_cookie[i] == NULL) {
            y->cookie = cookie;
            res = ccny_skiplist_insert(h, y);
            if (res == -1) {
                h->cookie--;
                y->cookie = 0;
                return(1);
            }
            h->nmentry_by_cookie[i] = y;
            h->n += 1;
            if (h->post_enroll)
                (h->post_enroll)(h, y);
            return(0);
        }
    }
    abort();
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
    newtab = calloc(cookiemask, sizeof(newtab[0]));
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
ccny_destroy(struct ccn_nametree *h, struct ccny **y)
{
    if (*y == NULL)
        return;
    if ((*y)->cookie != 0) abort();
    if (h != NULL && h->finalize)
        (h->finalize)(h, *y);
    ccn_charbuf_destroy(&(*y)->flatname);
    free(*y);
    *y = NULL;
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
        if (y->flatname->length > y->flatname->limit) abort();
        if (y->prev != NULL) {
            if (ccn_flatname_charbuf_compare(y->prev->flatname, y->flatname) >= 0) abort();
            if (y != y->prev->skiplinks[0]) abort();
        }
        else {
            if (y != h->head->skiplinks[0]) abort();
        }
        n++;
    }
    if (n != h->n) abort();
    for (n = 0, y = h->head->skiplinks[0]; y != NULL; y = y->skiplinks[0]) {
        if (y->flatname->length > y->flatname->limit) abort();
        for (i = 0; i < y->skipdim; i++) {
            z = y->skiplinks[i];
            if (z != NULL) {
                if (ccn_flatname_charbuf_compare(y->flatname, z->flatname) >= 0) abort();
            }
        }
        n++;
    }
    if (n != h->n) abort();
    if (h->check) {
        for (y = h->head->skiplinks[0]; y != NULL; y = y->skiplinks[0])
            (h->check)(h, y);
    }
}
