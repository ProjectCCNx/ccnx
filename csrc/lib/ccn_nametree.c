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

#define CCN_SKIPLIST_MAX_DEPTH 30

/**
 *  Look up an entry, given a cookie.
 *
 * The ans array is populated with pointers to the skiplinks
 * at each level.
 *
 * @returns 1 if an exact match was found
 */
static struct ccn_nmentry *
content_from_cookie(struct ccn_nametree *h, ccn_cookie cookie)
{
    struct ccn_nmentry *ans;
    
    ans = h->nmentry_by_cookie[cookie & h->cookiemask];
    if (ans == NULL && ans->cookie == cookie)
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
content_skiplist_findbefore(struct ccn_nametree *h,
                            struct ccn_charbuf *flatname,
                            struct ccn_nmentry *wanted_old,
                            ccn_cookie **ans)
{
    int i;
    ccn_cookie *c;
    struct ccn_nmentry *content;
    int order;
    int found = 0;
    
    c = h->skiplinks;
    for (i = h->skipdim - 1; i >= 0; i--) {
        for (;;) {
            if (c[i] == 0)
                break;
            content = content_from_cookie(h, c[i]);
            if (content == NULL)
                abort();
            order = ccn_flatname_charbuf_compare(content->flatname, flatname);
            if (order > 0)
                break;
            if (order == 0 && (wanted_old == content || wanted_old == NULL)) {
                found = 1;
                break;
            }
            if (content->skiplinks == NULL || i >= content->skipdim)
                abort();
            c = content->skiplinks;
        }
        ans[i] = c;
    }
    return(found);
}

/**
 *  Insert an entry into the skiplist
 *
 * @returns -1 and does not insert if an exact key match is found
 */
static int
content_skiplist_insert(struct ccn_nametree *h, struct ccn_nmentry *content)
{
    int i;
    int d;
    ccn_cookie *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    int found = 0;
    
    if (content->skiplinks != NULL) abort();
    for (d = 1; d < CCN_SKIPLIST_MAX_DEPTH - 1; d++)
        if ((nrand48(h->seed) & 3) != 0) break;
    while (h->skipdim < d)
        h->skiplinks[h->skipdim++] = 0;
    found = content_skiplist_findbefore(h, content->flatname, NULL, pred);
    if (found)
        return(-1);
    content->skiplinks = calloc(d, sizeof(ccn_cookie));
    for (i = 0; i < d; i++) {
        content->skiplinks[i] = pred[i][i];
        pred[i][i] = content->cookie;
    }
    return(0);
}

/**
 *  Remove an entry from the skiplist
 *
 * The entry must be present.
 */
static void
content_skiplist_remove(struct ccn_nametree *h, struct ccn_nmentry *content)
{
    int i;
    int d;
    ccn_cookie *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    
    if (content->skiplinks == NULL)
        return;
    content_skiplist_findbefore(h, content->flatname, content, pred);
    d = content->skipdim;
    if (h->skipdim < d) abort();
    for (i = 0; i < d; i++)
        pred[i][i] = content->skiplinks[i];
    free(content->skiplinks);
    content->skiplinks = NULL;
}

/**
 *  Enroll an entry into the nametree
 *
 * Allocation errors are fatal.
 *
 * @returns -1 if an entry with the name is already present.
 */
int
enroll_content(struct ccn_nametree *h, struct ccn_nmentry *content)
{
    ccn_cookie cookie;
    unsigned i;
    int res;
    
    cookie = ++(h->cookie);
    i = cookie & h->cookiemask;
    if (h->nmentry_by_cookie[i] == NULL) {
        content->cookie = cookie;
        res = content_skiplist_insert(h, content);
        if (res == -1) {
            h->cookie--;
            content->cookie = 0;
            return(-1);
        }
        h->nmentry_by_cookie[i] = content;
        return(0);
    }
    /* Add code to expand nmentry_by_cookie or remove old entry */
    if (0)
        content_skiplist_remove(h, content); /* silence warnings for now */
    return(-1);
}
