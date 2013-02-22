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

#define CCN_SKIPLIST_MAX_DEPTH 20

/**
 * Create a new, empty nametree
 */
struct ccn_nametree *
ccn_nametree_create(void)
{
    struct ccn_nametree *h;
    
    h = calloc(1, sizeof(*h));
    if (h != NULL) {
        h->skiplinks = calloc(CCN_SKIPLIST_MAX_DEPTH, sizeof(ccn_cookie));
        h->skipdim = 0;
        h->cookiemask = 0xFFFFF; /* XXX - oversize for now */
        h->nmentry_by_cookie = calloc(h->cookiemask + 1, sizeof(struct ccny *));
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
ccny_create(struct ccn_nametree *h, unsigned rb)
{
    struct ccny *y;
    int d;
    
    for (d = 1; d < CCN_SKIPLIST_MAX_DEPTH - 1; d++, rb >>= 2)
        if ((rb & 3) != 0) break;
    y = calloc(1, sizeof(*y) + (d - 1) * sizeof(ccn_cookie));
    if (y == NULL)
        return(y);
    y->skipdim = d;
    return(y);
}

/**
 *  Look up an entry, given a cookie.
 *
 * The ans array is populated with pointers to the skiplinks
 * at each level.
 *
 * @returns 1 if an exact match was found
 */
struct ccny *
ccny_from_cookie(struct ccn_nametree *h, ccn_cookie cookie)
{
    struct ccny *ans;
    
    ans = h->nmentry_by_cookie[cookie & h->cookiemask];
    if (ans == NULL || ans->cookie == cookie)
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
                         struct ccn_charbuf *flatname,
                         struct ccny *wanted_old,
                         ccn_cookie **ans)
{
    int i;
    ccn_cookie *c;
    struct ccny *y;
    int order;
    int found = 0;
    
    c = h->skiplinks;
    for (i = h->skipdim - 1; i >= 0; i--) {
        for (;;) {
            if (c[i] == 0)
                break;
            y = ccny_from_cookie(h, c[i]);
            if (y == NULL)
                abort();
            order = ccn_flatname_charbuf_compare(y->flatname, flatname);
            if (order > 0)
                break;
            if (order == 0 && (wanted_old == y || wanted_old == NULL)) {
                found = 1;
                break;
            }
            if (i >= y->skipdim) abort();
            c = y->skiplinks;
        }
        ans[i] = c;
    }
    return(found);
}

ccn_cookie
ccn_nametree_lookup(struct ccn_nametree *h,
                    const unsigned char *key, size_t size)
{
    ccn_cookie *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    struct ccn_charbuf f;
    int found;
    
    f.buf = (unsigned char *)key;
    f.length = size;
    found = ccny_skiplist_findbefore(h, &f, NULL, pred);
    if (found)
        return(pred[0][0]);
    return(0);
}

/**
 *  Insert an entry into the skiplist
 *
 * @returns -1 and does not insert if an exact key match is found
 */
static int
ccny_skiplist_insert(struct ccn_nametree *h, struct ccny *y)
{
    struct ccny *next;
    ccn_cookie *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    int found;
    int i;
    int d;
    
    d = y->skipdim;
    while (h->skipdim < d)
        h->skiplinks[h->skipdim++] = 0;
    found = ccny_skiplist_findbefore(h, y->flatname, NULL, pred);
    if (found)
        return(-1);
    next = ccny_from_cookie(h, pred[0][0]);
    for (i = 0; i < d; i++) {
        y->skiplinks[i] = pred[i][i];
        pred[i][i] = y->cookie;
    }
    if (next == NULL) {
        y->prev = h->last;
        h->last = y;
    }
    else {
        y->prev = next->prev;
        next->prev = y;
    }
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
    ccn_cookie *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    int i;
    int d;
    
    next = ccny_from_cookie(h, y->skiplinks[0]);
    ccny_skiplist_findbefore(h, y->flatname, y, pred);
    if (next == NULL)
        h->last = y->prev;
    else {
        if (next->prev != y) abort();
        next->prev = y->prev;
    }
    d = y->skipdim;
    if (h->skipdim < d) abort();
    for (i = 0; i < d; i++) {
        pred[i][i] = y->skiplinks[i];
        y->skiplinks[i] = 0;
    }
}

/**
 *  Enroll an entry into the nametree
 *
 * @returns -1 if an entry with the name is already present.
 */
int
ccny_enroll(struct ccn_nametree *h, struct ccny *y)
{
    ccn_cookie cookie;
    unsigned i;
    int res;
    
    cookie = ++(h->cookie);
    i = cookie & h->cookiemask;
    if (h->nmentry_by_cookie[i] == NULL) {
        y->cookie = cookie;
        res = ccny_skiplist_insert(h, y);
        if (res == -1) {
            h->cookie--;
            y->cookie = 0;
            return(-1);
        }
        h->nmentry_by_cookie[i] = y;
        return(0);
    }
    /* Add code to expand nmentry_by_cookie or remove old entry */
    if (0)
        ccny_skiplist_remove(h, y); /* silence warnings for now */
    return(-1);
}

void
ccn_nametree_delete_entry(struct ccn_nametree *h, struct ccny **y)
{
    if (y == NULL)
        return;
    if ((*y)->cookie != 0)
        ccny_skiplist_remove(h, *y);
    ccn_charbuf_destroy(&(*y)->flatname);
    free(*y);
    *y = NULL;
}
