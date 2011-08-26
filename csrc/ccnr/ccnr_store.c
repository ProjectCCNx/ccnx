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
#include "ccnr_link.h"
#include "ccnr_util.h"
#include "ccnr_proto.h"
#include "ccnr_msg.h"
#include "ccnr_sync.h"
#include "ccnr_match.h"
#include "ccnr_sendq.h"
#include "ccnr_io.h"

/**
 *  The content hash table is keyed by the initial portion of the ContentObject
 *  that contains all the parts of the complete name.  The extdata of the hash
 *  table holds the rest of the object, so that the whole ContentObject is
 *  stored contiguously.  The internal form differs from the on-wire form in
 *  that the final content-digest name component is represented explicitly,
 *  which simplifies the matching logic.
 *  The original ContentObject may be reconstructed simply by excising this
 *  last name component, which is easily located via the comps array.
 */
struct content_entry;
/* This is where the actual struct should be, but there is still some work to do. */

PUBLIC int
r_store_content_flags(struct content_entry *content)
{
    return(content->flags);
}

PUBLIC int
r_store_content_change_flags(struct content_entry *content, int set, int clear)
{
    int old = content->flags;
    content->flags |= set;
    content->flags &= ~clear;
    return(old);
}


PUBLIC void
r_store_init(struct ccnr_handle *h)
{
    struct hashtb_param param = {0};
    param.finalize_data = h;
    param.finalize = &r_store_finalize_content;
    h->content_tab = hashtb_create(sizeof(struct content_entry), &param);
    param.finalize = 0;
    h->content_by_accession_tab = hashtb_create(sizeof(struct content_by_accession_entry), NULL);
}

PUBLIC struct content_entry *
r_store_content_from_accession(struct ccnr_handle *h, ccnr_accession accession)
{
    struct content_entry *ans = NULL;
    struct content_by_accession_entry *entry;
    
    entry = hashtb_lookup(h->content_by_accession_tab,
                          &accession, sizeof(accession));
    if (entry != NULL)
        ans = entry->content;
    
    // XXXXXX - Here we have to read the content object from the correct repoFile.
    return(ans);
}

PUBLIC struct content_entry *
r_store_content_from_cookie(struct ccnr_handle *h, ccnr_cookie cookie)
{
    struct content_entry *ans = NULL;
    
    if (cookie < h->cookie_base)
        ans = NULL;
    else if (cookie < h->cookie_base + h->content_by_cookie_window) {
        ans = h->content_by_cookie[cookie - h->cookie_base];
        if (ans != NULL && ans->cookie != cookie)
            ans = NULL;
    }
    return(ans);
}

static void
cleanout_stragglers(struct ccnr_handle *h)
{
    struct content_entry **a = h->content_by_cookie;
    unsigned n_direct;
    unsigned n_occupied;
    unsigned window;
    unsigned i;
    
    if (h->cookie <= h->cookie_base || a[0] == NULL)
        return;
    n_direct = h->cookie - h->cookie_base;
    if (n_direct < 1000)
        return;
    n_occupied = hashtb_n(h->content_by_accession_tab);
    if (n_occupied >= (n_direct / 8))
        return;
    /* The direct lookup table is too sparse, so toss the stragglers */
    window = h->content_by_cookie_window;
    for (i = 0; i < window; i++) {
        if (a[i] != NULL) {
            if (n_occupied >= ((window - i) / 8))
                break;
            r_store_forget_content(h, &(a[i]));
            n_occupied -= 1;
        }
    }
}

static int
cleanout_empties(struct ccnr_handle *h)
{
    unsigned i = 0;
    unsigned j = 0;
    struct content_entry **a = h->content_by_cookie;
    unsigned window = h->content_by_cookie_window;
    if (a == NULL)
        return(-1);
    cleanout_stragglers(h);
    while (i < window && a[i] == NULL)
        i++;
    if (i == 0)
        return(-1);
    h->cookie_base += i;
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
    unsigned window;
    
    window = h->content_by_cookie_window;
    if ((content->cookie - h->cookie_base) >= window &&
        cleanout_empties(h) < 0) {
        if (content->cookie < h->cookie_base)
            return;
        window = h->content_by_cookie_window;
        old_array = h->content_by_cookie;
        new_window = ((window + 20) * 3 / 2);
        if (new_window < window)
            return;
        new_array = calloc(new_window, sizeof(new_array[0]));
        if (new_array == NULL)
            return;
        while (i < h->content_by_cookie_window && old_array[i] == NULL)
            i++;
        h->cookie_base += i;
        h->content_by_cookie = new_array;
        while (i < h->content_by_cookie_window)
            new_array[j++] = old_array[i++];
        h->content_by_cookie_window = new_window;
        free(old_array);
    }
    h->content_by_cookie[content->cookie - h->cookie_base] = content;
    
    if (content->accession != CCNR_NULL_ACCESSION) {
        struct hashtb_enumerator ee;
        struct hashtb_enumerator *e = &ee;
        ccnr_accession accession = content->accession;
        struct content_by_accession_entry *entry = NULL;
        hashtb_start(h->content_by_accession_tab, e);
        hashtb_seek(e, &accession, sizeof(accession), 0);
        entry = e->data;
        if (entry != NULL)
            entry->content = content;
        hashtb_end(e);
    }
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
            content = r_store_content_from_cookie(h, c->buf[i]);
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
        pred[i]->buf[i] = content->cookie;
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
    
    entry->destroy = NULL;
    r_store_forget_content(h, &entry);
}

PUBLIC void
r_store_forget_content(struct ccnr_handle *h, struct content_entry **pentry)
{
    unsigned i;
    struct content_entry *entry = *pentry;
    
    if (entry == NULL)
        return;
    *pentry = NULL;
    /* Unlink from skiplist, if it is there */
    content_skiplist_remove(h, entry);
    /* Remove the cookie reference */
    i = entry->cookie - h->cookie_base;
    if (i < h->content_by_cookie_window && h->content_by_cookie[i] == entry)
        h->content_by_cookie[i] = NULL;
    entry->cookie = 0;
    /* Remove the accession reference */
    if (entry->accession != CCNR_NULL_ACCESSION) {
        struct hashtb_enumerator ee;
        struct hashtb_enumerator *e = &ee;
        hashtb_start(h->content_by_accession_tab, e);
        if (hashtb_seek(e, &entry->accession, sizeof(entry->accession), 0) ==
            HT_NEW_ENTRY) {
            ccnr_msg(h, "orphaned content %llu",
                     (unsigned long long)(entry->accession));
            hashtb_delete(e);
            hashtb_end(e);
            return;
        }
        hashtb_delete(e);
        hashtb_end(e);
        entry->accession = CCNR_NULL_ACCESSION;
    }
    /* Clean up allocated subfields */
    if (entry->comps != NULL) {
        free(entry->comps);
        entry->comps = NULL;
    }
    /* Tell the entry to free itself, if it wants to */
    if (entry->destroy)
        (entry->destroy)(h, entry);
}

// XXXXXX - consolidation needed here
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
    if (CCNSHOULDLOG(h, LM_4, CCNL_INFO))
        ccnr_debug_ccnb(h, __LINE__, "remove", NULL,
                        content->key, content->size);
    hashtb_delete(e);
    hashtb_end(e);
    return(0);
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
                    if (CCNSHOULDLOG(h, LM_8, CCNL_FINER))
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
    return(r_store_content_from_cookie(h, pred[0]->buf[0]));
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

PUBLIC ccnr_cookie
r_store_content_skiplist_next(struct ccnr_handle *h, struct content_entry *content)
{
    if (content == NULL)
        return(0);
    if (content->skiplinks == NULL || content->skiplinks->n < 1)
        return(0);
    return(content->skiplinks->buf[0]);
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
    if (CCNSHOULDLOG(h, LM_8, CCNL_FINER))
        ccnr_debug_ccnb(h, __LINE__, "child_successor", NULL,
                        name->buf, name->length);
    d = content_skiplist_findbefore(h, name->buf, name->length,
                                    NULL, pred);
    next = r_store_content_from_cookie(h, pred[0]->buf[0]);
    if (next == content) {
        // XXX - I think this case should not occur, but just in case, avoid a loop.
        next = r_store_content_from_cookie(h, r_store_content_skiplist_next(h, content));
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
    int try;
    size_t size = pi->offset[CCN_PI_E];
    
    content = r_store_find_first_match_candidate(h, msg, pi);
    if (content != NULL && CCNSHOULDLOG(h, LM_8, CCNL_FINER))
        ccnr_debug_ccnb(h, __LINE__, "first_candidate", NULL,
                        content->key,
                        content->size);
    if (content != NULL &&
        !r_store_content_matches_interest_prefix(h, content, msg, comps,
                                                 pi->prefix_comps)) {
            if (CCNSHOULDLOG(h, LM_8, CCNL_FINER))
                ccnr_debug_ccnb(h, __LINE__, "prefix_mismatch", NULL,
                                msg, size);
            content = NULL;
        }
    for (try = 0; content != NULL; try++) {
        if (ccn_content_matches_interest(content->key,
                                         content->size,
                                         0, NULL, msg, size, pi)) {
                if ((pi->orderpref & 1) == 0 && // XXX - should be symbolic
                    pi->prefix_comps != comps->n - 1 &&
                    comps->n == content->ncomps &&
                    r_store_content_matches_interest_prefix(h, content, msg,
                                                            comps, comps->n - 1)) {
                        if (CCNSHOULDLOG(h, LM_8, CCNL_FINER))
                            ccnr_debug_ccnb(h, __LINE__, "skip_match", NULL,
                                            content->key,
                                            content->size);
                        goto move_along;
                    }
                if (CCNSHOULDLOG(h, LM_8, CCNL_FINER))
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
        content = r_store_content_from_cookie(h, r_store_content_skiplist_next(h, content));
    check_next_prefix:
        if (content != NULL &&
            !r_store_content_matches_interest_prefix(h, content, msg,
                                                     comps, pi->prefix_comps)) {
                if (CCNSHOULDLOG(h, LM_8, CCNL_FINER))
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
    ccnr_cookie cookie = content->cookie;
    if ((content->flags & CCN_CONTENT_ENTRY_STALE) != 0)
        return;
    if (CCNSHOULDLOG(h, LM_4, CCNL_INFO))
            ccnr_debug_ccnb(h, __LINE__, "stale", NULL,
                            content->key, content->size);
    content->flags |= CCN_CONTENT_ENTRY_STALE;
    h->n_stale++;
    if (cookie < h->min_stale)
        h->min_stale = cookie;
    if (cookie > h->max_stale)
        h->max_stale = cookie;
}

/**
 * Scheduled event that makes content stale when its FreshnessSeconds
 * has expired.
 */
static int
expire_content(struct ccn_schedule *sched,
               void *clienth,
               struct ccn_scheduled_event *ev,
               int flags)
{
    struct ccnr_handle *h = clienth;
    ccnr_cookie cookie = ev->evint;
    struct content_entry *content = NULL;
    if ((flags & CCN_SCHEDULE_CANCEL) != 0)
        return(0);
    content = r_store_content_from_cookie(h, cookie);
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
                       &expire_content, NULL, content->cookie);
}


PUBLIC struct content_entry *
process_incoming_content(struct ccnr_handle *h, struct fdholder *fdholder,
                         unsigned char *wire_msg, size_t wire_size)
{
    unsigned char *msg;
    size_t size;
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct ccn_parsed_ContentObject obj = {0};
    int res;
    size_t keysize = 0;
    size_t tailsize = 0;
    unsigned char *tail = NULL;
    struct content_entry *content = NULL;
    int i;
    struct ccn_indexbuf *comps = r_util_indexbuf_obtain(h);
    struct ccn_charbuf *cb = r_util_charbuf_obtain(h);
    
    msg = wire_msg;
    size = wire_size;
    
    res = ccn_parse_ContentObject(msg, size, &obj, comps);
    if (res < 0) {
        ccnr_msg(h, "error parsing ContentObject - code %d", res);
        goto Bail;
    }
    ccnr_meter_bump(h, fdholder->meter[FM_DATI], 1);
    if (comps->n < 1 ||
        (keysize = comps->buf[comps->n - 1]) > 65535 - 36) {
        ccnr_msg(h, "ContentObject with keysize %lu discarded",
                 (unsigned long)keysize);
        ccnr_debug_ccnb(h, __LINE__, "oversize", fdholder, msg, size);
        res = -__LINE__;
        goto Bail;
    }
    /* Make the ContentObject-digest name component explicit */
    ccn_digest_ContentObject(msg, &obj);
    if (obj.digest_bytes != 32) {
        ccnr_debug_ccnb(h, __LINE__, "indigestible", fdholder, msg, size);
        goto Bail;
    }
    i = comps->buf[comps->n - 1];
    ccn_charbuf_append(cb, msg, i);
    ccn_charbuf_append_tt(cb, CCN_DTAG_Component, CCN_DTAG);
    ccn_charbuf_append_tt(cb, obj.digest_bytes, CCN_BLOB);
    ccn_charbuf_append(cb, obj.digest, obj.digest_bytes);
    ccn_charbuf_append_closer(cb);
    ccn_charbuf_append(cb, msg + i, size - i);
    msg = cb->buf;
    size = cb->length;
    res = ccn_parse_ContentObject(msg, size, &obj, comps);
    if (res < 0) abort(); /* must have just messed up */
    
    if (obj.magic != 20090415) {
        if (++(h->oldformatcontent) == h->oldformatcontentgrumble) {
            h->oldformatcontentgrumble *= 10;
            ccnr_msg(h, "downrev content items received: %d (%d)",
                     h->oldformatcontent,
                     obj.magic);
        }
    }
    if (CCNSHOULDLOG(h, LM_4, CCNL_INFO))
        ccnr_debug_ccnb(h, __LINE__, "content_from", fdholder, msg, size);
    keysize = obj.offset[CCN_PCO_B_Content];
    tail = msg + keysize;
    tailsize = size - keysize;
    hashtb_start(h->content_tab, e);
    res = hashtb_seek(e, msg, keysize, tailsize);
    content = e->data;
    if (res == HT_OLD_ENTRY) {
        if (tailsize != e->extsize ||
              0 != memcmp(tail, ((unsigned char *)e->key) + keysize, tailsize)) {
            ccnr_msg(h, "ContentObject name collision!!!!!");
            ccnr_debug_ccnb(h, __LINE__, "new", fdholder, msg, size);
            ccnr_debug_ccnb(h, __LINE__, "old", NULL, e->key, e->keysize + e->extsize);
            content = NULL;
            hashtb_delete(e); /* XXX - Mercilessly throw away both of them. */
            res = -__LINE__;
        }
        else if ((content->flags & CCN_CONTENT_ENTRY_STALE) != 0) {
            /* When old content arrives after it has gone stale, freshen it */
            // XXX - ought to do mischief checks before this
            content->flags &= ~CCN_CONTENT_ENTRY_STALE;
            h->n_stale--;
            r_store_set_content_timer(h, content, &obj);
            // XXX - no counter for this case
        }
        else {
            h->content_dups_recvd++;
            if (CCNSHOULDLOG(h, LM_4, CCNL_INFO))
                ccnr_debug_ccnb(h, __LINE__, "dup", fdholder, msg, size);
        }
    }
    else if (res == HT_NEW_ENTRY) {
        content->cookie = ++(h->cookie);
        content->accession = ++(h->accession); // XXXXXX - here we should have the repository cobid
        r_store_enroll_content(h, content);
        if (content == r_store_content_from_cookie(h, content->cookie)) {
            content->ncomps = comps->n;
            content->comps = calloc(comps->n, sizeof(comps[0]));
        }
        content->key_size = e->keysize;
        content->size = e->keysize + e->extsize;
        content->key = e->key;
        if (content->comps != NULL) {
            for (i = 0; i < comps->n; i++)
                content->comps[i] = comps->buf[i];
            r_store_content_skiplist_insert(h, content);
            r_store_set_content_timer(h, content, &obj);
        }
        else {
            ccnr_msg(h, "could not enroll ContentObject (accession %ju)",
                ccnr_accession_encode(h, content->accession));
            hashtb_delete(e);
            res = -__LINE__;
            content = NULL;
        }
        if (content != NULL) {
            if (obj.type == CCN_CONTENT_KEY)
                content->flags |= CCN_CONTENT_ENTRY_PRECIOUS;
            if ((fdholder->flags & CCNR_FACE_REPODATA) != 0) {
                content->flags |= CCN_CONTENT_ENTRY_STABLE;
                if (content->accession >= h->notify_after) // XXXXXX
                    r_sync_notify_content(h, 0, content);
            }
            else {
                r_proto_initiate_key_fetch(h, msg, &obj, 0, content->cookie);
            }
        }
    }
    hashtb_end(e);
Bail:
    r_util_indexbuf_release(h, comps);
    r_util_charbuf_release(h, cb);
    cb = NULL;
    if (res >= 0 && content != NULL) {
        int n_matches;
        enum cq_delay_class c;
        struct content_queue *q;
        n_matches = r_match_match_interests(h, content, &obj, NULL, fdholder);
        if (res == HT_NEW_ENTRY) {
            if (n_matches < 0) {
                r_store_remove_content(h, content);
                return(NULL);
            }
            if (n_matches == 0 && (fdholder->flags & CCNR_FACE_GG) == 0) {
                content->flags |= CCN_CONTENT_ENTRY_SLOWSEND;
                ccn_indexbuf_append_element(h->unsol, content->cookie); // XXXXXX
            }
        }
        for (c = 0; c < CCN_CQ_N; c++) {
            q = fdholder->q[c];
            if (q != NULL) {
                i = ccn_indexbuf_member(q->send_queue, content->cookie);
                if (i >= 0) {
                    /*
                     * In the case this consumed any interests from this source,
                     * don't send the content back
                     */
                    if (CCNSHOULDLOG(h, LM_8, CCNL_FINER))
                        ccnr_debug_ccnb(h, __LINE__, "content_nosend", fdholder, msg, size);
                    q->send_queue->buf[i] = 0;
                }
            }
        }
    }
    return(content);
}

PUBLIC int
r_store_content_field_access(struct ccnr_handle *h,
                             struct content_entry *content,
                             enum ccn_dtag dtag,
                             const unsigned char **bufp, size_t *sizep)
{
	int res = -1;
	if (dtag == CCN_DTAG_Content)
        res = ccn_ref_tagged_BLOB(CCN_DTAG_Content, content->key,
                                  content->key_size, content->size,
                                  bufp, sizep);
	return(res);
}

PUBLIC void
r_store_send_content(struct ccnr_handle *h, struct fdholder *fdholder, struct content_entry *content)
{
    int n, a, b, size;
    size = content->size;
    if (CCNSHOULDLOG(h, LM_4, CCNL_INFO))
        ccnr_debug_ccnb(h, __LINE__, "content_to", fdholder,
                        content->key, size);
    /* Excise the message-digest name component */
    n = content->ncomps;
    if (n < 2) abort();
    a = content->comps[n - 2];
    b = content->comps[n - 1];
    if (b - a != 36)
        abort(); /* strange digest length */
    r_link_stuff_and_send(h, fdholder, content->key, a, content->key + b, size - b);
    
}

PUBLIC int
r_store_commit_content(struct ccnr_handle *h, struct content_entry *content)
{
    int res;
    // XXX - here we need to check if this is something we *should* be storing, according to our policy
    if ((r_store_content_flags(content) & CCN_CONTENT_ENTRY_STABLE) == 0) {
        // Need to actually append to the active repo data file
        r_sendq_face_send_queue_insert(h, r_io_fdholder_from_fd(h, h->active_out_fd), content);
        // XXX - it would be better to do this after the write succeeds
        r_store_content_change_flags(content, CCN_CONTENT_ENTRY_STABLE, 0);
        ccnr_debug_ccnb(h, __LINE__, "content_stored",
                        r_io_fdholder_from_fd(h, h->active_out_fd),
                        content->key, content->size);
        if (content->accession >= h->notify_after) // XXXXXX 
            res = r_sync_notify_content(h, 0, content);
    }
    return(0);
}

