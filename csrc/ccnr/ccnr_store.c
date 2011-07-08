/**
 * @file ccnr_store.c
 * 
 * Part of ccnr -  CCNx Repository Daemon.
 *
 */

/*
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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
 
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netdb.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <netinet/in.h>

#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/ccn_private.h>
#include <ccn/charbuf.h>
#include <ccn/face_mgmt.h>
#include <ccn/hashtb.h>
#include <ccn/indexbuf.h>
#include <ccn/schedule.h>
#include <ccn/reg_mgmt.h>
#include <ccn/uri.h>

#include "ccnr_private.h"

#include "ccnr_store.h"

#include "ccnr_msg.h"

PUBLIC struct content_entry *
r_store_content_from_accession(struct ccnr_handle *h, ccn_accession_t accession)
{
    struct content_entry *ans = NULL;
    if (accession < h->accession_base) {
        struct sparse_straggler_entry *entry;
        entry = hashtb_lookup(h->sparse_straggler_tab,
                              &accession, sizeof(accession));
        if (entry != NULL)
            ans = entry->content;
    }
    else if (accession < h->accession_base + h->content_by_accession_window) {
        ans = h->content_by_accession[accession - h->accession_base];
        if (ans != NULL && ans->accession != accession)
            ans = NULL;
    }
    return(ans);
}

static void
cleanout_stragglers(struct ccnr_handle *h)
{
    ccn_accession_t accession;
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct sparse_straggler_entry *entry = NULL;
    struct content_entry **a = h->content_by_accession;
    unsigned n_direct;
    unsigned n_occupied;
    unsigned window;
    unsigned i;
    if (h->accession <= h->accession_base || a[0] == NULL)
        return;
    n_direct = h->accession - h->accession_base;
    if (n_direct < 1000)
        return;
    n_occupied = hashtb_n(h->content_tab) - hashtb_n(h->sparse_straggler_tab);
    if (n_occupied >= (n_direct / 8))
        return;
    /* The direct lookup table is too sparse, so sweep stragglers */
    hashtb_start(h->sparse_straggler_tab, e);
    window = h->content_by_accession_window;
    for (i = 0; i < window; i++) {
        if (a[i] != NULL) {
            if (n_occupied >= ((window - i) / 8))
                break;
            accession = h->accession_base + i;
            hashtb_seek(e, &accession, sizeof(accession), 0);
            entry = e->data;
            if (entry != NULL && entry->content == NULL) {
                entry->content = a[i];
                a[i] = NULL;
                n_occupied -= 1;
            }
        }
    }
    hashtb_end(e);
}

static int
cleanout_empties(struct ccnr_handle *h)
{
    unsigned i = 0;
    unsigned j = 0;
    struct content_entry **a = h->content_by_accession;
    unsigned window = h->content_by_accession_window;
    if (a == NULL)
        return(-1);
    cleanout_stragglers(h);
    while (i < window && a[i] == NULL)
        i++;
    if (i == 0)
        return(-1);
    h->accession_base += i;
    while (i < window)
        a[j++] = a[i++];
    while (j < window)
        a[j++] = NULL;
    return(0);
}

PUBLIC void
r_store_enroll_content(struct ccnr_handle *h, struct content_entry *content)
{
    unsigned new_window;
    struct content_entry **new_array;
    struct content_entry **old_array;
    unsigned i = 0;
    unsigned j = 0;
    unsigned window = h->content_by_accession_window;
    if ((content->accession - h->accession_base) >= window &&
        cleanout_empties(h) < 0) {
        if (content->accession < h->accession_base)
            return;
        window = h->content_by_accession_window;
        old_array = h->content_by_accession;
        new_window = ((window + 20) * 3 / 2);
        if (new_window < window)
            return;
        new_array = calloc(new_window, sizeof(new_array[0]));
        if (new_array == NULL)
            return;
        while (i < h->content_by_accession_window && old_array[i] == NULL)
            i++;
        h->accession_base += i;
        h->content_by_accession = new_array;
        while (i < h->content_by_accession_window)
            new_array[j++] = old_array[i++];
        h->content_by_accession_window = new_window;
        free(old_array);
    }
    h->content_by_accession[content->accession - h->accession_base] = content;
}

static int
content_skiplist_findbefore(struct ccnr_handle *h,
                            const unsigned char *key,
                            size_t keysize,
                            struct content_entry *wanted_old,
                            struct ccn_indexbuf **ans)
{
    int i;
    int n = h->skiplinks->n;
    struct ccn_indexbuf *c;
    struct content_entry *content;
    int order;
    size_t start;
    size_t end;
    
    c = h->skiplinks;
    for (i = n - 1; i >= 0; i--) {
        for (;;) {
            if (c->buf[i] == 0)
                break;
            content = r_store_content_from_accession(h, c->buf[i]);
            if (content == NULL)
                abort();
            start = content->comps[0];
            end = content->comps[content->ncomps - 1];
            order = ccn_compare_names(content->key + start - 1, end - start + 2,
                                      key, keysize);
            if (order > 0)
                break;
            if (order == 0 && (wanted_old == content || wanted_old == NULL))
                break;
            if (content->skiplinks == NULL || i >= content->skiplinks->n)
                abort();
            c = content->skiplinks;
        }
        ans[i] = c;
    }
    return(n);
}

#define CCN_SKIPLIST_MAX_DEPTH 30
PUBLIC void
r_store_content_skiplist_insert(struct ccnr_handle *h, struct content_entry *content)
{
    int d;
    int i;
    size_t start;
    size_t end;
    struct ccn_indexbuf *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    if (content->skiplinks != NULL) abort();
    for (d = 1; d < CCN_SKIPLIST_MAX_DEPTH - 1; d++)
        if ((nrand48(h->seed) & 3) != 0) break;
    while (h->skiplinks->n < d)
        ccn_indexbuf_append_element(h->skiplinks, 0);
    start = content->comps[0];
    end = content->comps[content->ncomps - 1];
    i = content_skiplist_findbefore(h,
                                    content->key + start - 1,
                                    end - start + 2, NULL, pred);
    if (i < d)
        d = i; /* just in case */
    content->skiplinks = ccn_indexbuf_create();
    for (i = 0; i < d; i++) {
        ccn_indexbuf_append_element(content->skiplinks, pred[i]->buf[i]);
        pred[i]->buf[i] = content->accession;
    }
}

static void
content_skiplist_remove(struct ccnr_handle *h, struct content_entry *content)
{
    int i;
    int d;
    size_t start;
    size_t end;
    struct ccn_indexbuf *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    if (content->skiplinks == NULL) abort();
    start = content->comps[0];
    end = content->comps[content->ncomps - 1];
    d = content_skiplist_findbefore(h,
                                    content->key + start - 1,
                                    end - start + 2, content, pred);
    if (d > content->skiplinks->n)
        d = content->skiplinks->n;
    for (i = 0; i < d; i++) {
        pred[i]->buf[i] = content->skiplinks->buf[i];
    }
    ccn_indexbuf_destroy(&content->skiplinks);
}


PUBLIC void
r_store_finalize_content(struct hashtb_enumerator *content_enumerator)
{
    struct ccnr_handle *h = hashtb_get_param(content_enumerator->ht, NULL);
    struct content_entry *entry = content_enumerator->data;
    unsigned i = entry->accession - h->accession_base;
    if (i < h->content_by_accession_window &&
          h->content_by_accession[i] == entry) {
        content_skiplist_remove(h, entry);
        h->content_by_accession[i] = NULL;
    }
    else {
        struct hashtb_enumerator ee;
        struct hashtb_enumerator *e = &ee;
        hashtb_start(h->sparse_straggler_tab, e);
        if (hashtb_seek(e, &entry->accession, sizeof(entry->accession), 0) ==
              HT_NEW_ENTRY) {
            ccnr_msg(h, "orphaned content %llu",
                     (unsigned long long)(entry->accession));
            hashtb_delete(e);
            hashtb_end(e);
            return;
        }
        content_skiplist_remove(h, entry);
        hashtb_delete(e);
        hashtb_end(e);
    }
    if (entry->comps != NULL) {
        free(entry->comps);
        entry->comps = NULL;
    }
}


PUBLIC struct content_entry *
r_store_find_first_match_candidate(struct ccnr_handle *h,
                           const unsigned char *interest_msg,
                           const struct ccn_parsed_interest *pi)
{
    int res;
    struct ccn_indexbuf *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    size_t start = pi->offset[CCN_PI_B_Name];
    size_t end = pi->offset[CCN_PI_E_Name];
    struct ccn_charbuf *namebuf = NULL;
    if (pi->offset[CCN_PI_B_Exclude] < pi->offset[CCN_PI_E_Exclude]) {
        /* Check for <Exclude><Any/><Component>... fast case */
        struct ccn_buf_decoder decoder;
        struct ccn_buf_decoder *d;
        size_t ex1start;
        size_t ex1end;
        d = ccn_buf_decoder_start(&decoder,
                                  interest_msg + pi->offset[CCN_PI_B_Exclude],
                                  pi->offset[CCN_PI_E_Exclude] -
                                  pi->offset[CCN_PI_B_Exclude]);
        ccn_buf_advance(d);
        if (ccn_buf_match_dtag(d, CCN_DTAG_Any)) {
            ccn_buf_advance(d);
            ccn_buf_check_close(d);
            if (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
                ex1start = pi->offset[CCN_PI_B_Exclude] + d->decoder.token_index;
                ccn_buf_advance_past_element(d);
                ex1end = pi->offset[CCN_PI_B_Exclude] + d->decoder.token_index;
                if (d->decoder.state >= 0) {
                    namebuf = ccn_charbuf_create();
                    ccn_charbuf_append(namebuf,
                                       interest_msg + start,
                                       end - start);
                    namebuf->length--;
                    ccn_charbuf_append(namebuf,
                                       interest_msg + ex1start,
                                       ex1end - ex1start);
                    ccn_charbuf_append_closer(namebuf);
                    if (h->debug & 8)
                        ccnr_debug_ccnb(h, __LINE__, "fastex", NULL,
                                        namebuf->buf, namebuf->length);
                }
            }
        }
    }
    if (namebuf == NULL) {
        res = content_skiplist_findbefore(h, interest_msg + start, end - start,
                                          NULL, pred);
    }
    else {
        res = content_skiplist_findbefore(h, namebuf->buf, namebuf->length,
                                          NULL, pred);
        ccn_charbuf_destroy(&namebuf);
    }
    if (res == 0)
        return(NULL);
    return(r_store_content_from_accession(h, pred[0]->buf[0]));
}

PUBLIC int
r_store_content_matches_interest_prefix(struct ccnr_handle *h,
                                struct content_entry *content,
                                const unsigned char *interest_msg,
                                struct ccn_indexbuf *comps,
                                int prefix_comps)
{
    size_t prefixlen;
    if (prefix_comps < 0 || prefix_comps >= comps->n)
        abort();
    /* First verify the prefix match. */
    if (content->ncomps < prefix_comps + 1)
            return(0);
    prefixlen = comps->buf[prefix_comps] - comps->buf[0];
    if (content->comps[prefix_comps] - content->comps[0] != prefixlen)
        return(0);
    if (0 != memcmp(content->key + content->comps[0],
                    interest_msg + comps->buf[0],
                    prefixlen))
        return(0);
    return(1);
}

PUBLIC ccn_accession_t
r_store_content_skiplist_next(struct ccnr_handle *h, struct content_entry *content)
{
    if (content == NULL)
        return(0);
    if (content->skiplinks == NULL || content->skiplinks->n < 1)
        return(0);
    return(content->skiplinks->buf[0]);
}

PUBLIC int
r_store_remove_content(struct ccnr_handle *h, struct content_entry *content)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    int res;
    if (content == NULL)
        return(-1);
    hashtb_start(h->content_tab, e);
    res = hashtb_seek(e, content->key,
                      content->key_size, content->size - content->key_size);
    if (res != HT_OLD_ENTRY)
        abort();
    if ((content->flags & CCN_CONTENT_ENTRY_STALE) != 0)
        h->n_stale--;
    if (h->debug & 4)
        ccnr_debug_ccnb(h, __LINE__, "remove", NULL,
                        content->key, content->size);
    hashtb_delete(e);
    hashtb_end(e);
    return(0);
}

PUBLIC struct content_entry *
r_store_next_child_at_level(struct ccnr_handle *h,
                    struct content_entry *content, int level)
{
    struct content_entry *next = NULL;
    struct ccn_charbuf *name;
    struct ccn_indexbuf *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
    int d;
    int res;
    
    if (content == NULL)
        return(NULL);
    if (content->ncomps <= level + 1)
        return(NULL);
    name = ccn_charbuf_create();
    ccn_name_init(name);
    res = ccn_name_append_components(name, content->key,
                                     content->comps[0],
                                     content->comps[level + 1]);
    if (res < 0) abort();
    res = ccn_name_next_sibling(name);
    if (res < 0) abort();
    if (h->debug & 8)
        ccnr_debug_ccnb(h, __LINE__, "child_successor", NULL,
                        name->buf, name->length);
    d = content_skiplist_findbefore(h, name->buf, name->length,
                                    NULL, pred);
    next = r_store_content_from_accession(h, pred[0]->buf[0]);
    if (next == content) {
        // XXX - I think this case should not occur, but just in case, avoid a loop.
        next = r_store_content_from_accession(h, r_store_content_skiplist_next(h, content));
        ccnr_debug_ccnb(h, __LINE__, "bump", NULL, next->key, next->size);
    }
    ccn_charbuf_destroy(&name);
    return(next);
}

PUBLIC struct content_entry *
r_store_lookup(struct ccnr_handle *h,
               const unsigned char *msg,
               const struct ccn_parsed_interest *pi,
               struct ccn_indexbuf *comps)
{
    struct content_entry *content = NULL;
    struct content_entry *last_match = NULL;
    int s_ok;
    int try;
    size_t size = pi->offset[CCN_PI_E];
    
    s_ok = (pi->answerfrom & CCN_AOK_STALE) != 0;
    content = r_store_find_first_match_candidate(h, msg, pi);
    if (content != NULL && (h->debug & 8))
        ccnr_debug_ccnb(h, __LINE__, "first_candidate", NULL,
                        content->key,
                        content->size);
    if (content != NULL &&
        !r_store_content_matches_interest_prefix(h, content, msg, comps,
                                                 pi->prefix_comps)) {
            if (h->debug & 8)
                ccnr_debug_ccnb(h, __LINE__, "prefix_mismatch", NULL,
                                msg, size);
            content = NULL;
        }
    for (try = 0; content != NULL; try++) {
        if ((s_ok || (content->flags & CCN_CONTENT_ENTRY_STALE) == 0) &&
            ccn_content_matches_interest(content->key,
                                         content->size,
                                         0, NULL, msg, size, pi)) {
                if ((pi->orderpref & 1) == 0 && // XXX - should be symbolic
                    pi->prefix_comps != comps->n - 1 &&
                    comps->n == content->ncomps &&
                    r_store_content_matches_interest_prefix(h, content, msg,
                                                            comps, comps->n - 1)) {
                        if (h->debug & 8)
                            ccnr_debug_ccnb(h, __LINE__, "skip_match", NULL,
                                            content->key,
                                            content->size);
                        goto move_along;
                    }
                if (h->debug & 8)
                    ccnr_debug_ccnb(h, __LINE__, "matches", NULL,
                                    content->key,
                                    content->size);
                if ((pi->orderpref & 1) == 0) // XXX - should be symbolic
                    break;
                last_match = content;
                content = r_store_next_child_at_level(h, content, comps->n - 1);
                goto check_next_prefix;
            }
    move_along:
        content = r_store_content_from_accession(h, r_store_content_skiplist_next(h, content));
    check_next_prefix:
        if (content != NULL &&
            !r_store_content_matches_interest_prefix(h, content, msg,
                                                     comps, pi->prefix_comps)) {
                if (h->debug & 8)
                    ccnr_debug_ccnb(h, __LINE__, "prefix_mismatch", NULL,
                                    content->key,
                                    content->size);
                content = NULL;
            }
    }
    if (last_match != NULL)
        content = last_match;
    return(content);
}

/**
 * Mark content as stale
 */
PUBLIC void
r_store_mark_stale(struct ccnr_handle *h, struct content_entry *content)
{
    ccn_accession_t accession = content->accession;
    if ((content->flags & CCN_CONTENT_ENTRY_STALE) != 0)
        return;
    if (h->debug & 4)
            ccnr_debug_ccnb(h, __LINE__, "stale", NULL,
                            content->key, content->size);
    content->flags |= CCN_CONTENT_ENTRY_STALE;
    h->n_stale++;
    if (accession < h->min_stale)
        h->min_stale = accession;
    if (accession > h->max_stale)
        h->max_stale = accession;
}

/**
 * Scheduled event that makes content stale when its FreshnessSeconds
 * has exported.
 */
static int
expire_content(struct ccn_schedule *sched,
               void *clienth,
               struct ccn_scheduled_event *ev,
               int flags)
{
    struct ccnr_handle *h = clienth;
    ccn_accession_t accession = ev->evint;
    struct content_entry *content = NULL;
    if ((flags & CCN_SCHEDULE_CANCEL) != 0)
        return(0);
    content = r_store_content_from_accession(h, accession);
    if (content != NULL)
        r_store_mark_stale(h, content);
    return(0);
}

/**
 * Schedules content expiration based on its FreshnessSeconds.
 *
 */
PUBLIC void
r_store_set_content_timer(struct ccnr_handle *h, struct content_entry *content,
                  struct ccn_parsed_ContentObject *pco)
{
    int seconds = 0;
    int microseconds = 0;
    size_t start = pco->offset[CCN_PCO_B_FreshnessSeconds];
    size_t stop  = pco->offset[CCN_PCO_E_FreshnessSeconds];
    if (start == stop)
        return;
    seconds = ccn_fetch_tagged_nonNegativeInteger(
                CCN_DTAG_FreshnessSeconds,
                content->key,
                start, stop);
    if (seconds <= 0)
        return;
    if (seconds > ((1U<<31) / 1000000)) {
        ccnr_debug_ccnb(h, __LINE__, "FreshnessSeconds_too_large", NULL,
            content->key, pco->offset[CCN_PCO_E]);
        return;
    }
    microseconds = seconds * 1000000;
    ccn_schedule_event(h->sched, microseconds,
                       &expire_content, NULL, content->accession);
}
