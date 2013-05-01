/**
 * @file ccnr_sync.c
 * 
 * Part of ccnr -  CCNx Repository Daemon.
 *
 */

/*
 * Copyright (C) 2011, 2013 Palo Alto Research Center, Inc.
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

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/types.h>

#include <ccn/btree.h>
#include <ccn/btree_content.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/indexbuf.h>
#include <ccn/schedule.h>

#include <sync/SyncBase.h>

#include "ccnr_private.h"

#include "ccnr_dispatch.h"
#include "ccnr_io.h"
#include "ccnr_link.h"
#include "ccnr_msg.h"
#include "ccnr_proto.h"
#include "ccnr_store.h"
#include "ccnr_sync.h"
#include "ccnr_util.h"

#include <sync/sync_plumbing.h>

#ifndef CCNLINT

/* Preliminary implementation - algorithm may change */

PUBLIC uintmax_t
ccnr_accession_encode(struct ccnr_handle *ccnr, ccnr_accession a)
{
    return(a);
}

PUBLIC ccnr_accession
ccnr_accession_decode(struct ccnr_handle *ccnr, uintmax_t encoded)
{
    return(encoded);
}

PUBLIC int
ccnr_accession_compare(struct ccnr_handle *ccnr, ccnr_accession x, ccnr_accession y)
{
    if (x > y) return 1;
    if (x == y) return 0;
    if (x < y) return -1;
    return CCNR_NOT_COMPARABLE;
}

PUBLIC uintmax_t
ccnr_hwm_encode(struct ccnr_handle *ccnr, ccnr_hwm hwm)
{
    return(hwm);
}

PUBLIC ccnr_hwm
ccnr_hwm_decode(struct ccnr_handle *ccnr, uintmax_t encoded)
{
    return(encoded);
}

PUBLIC int
ccnr_acc_in_hwm(struct ccnr_handle *ccnr, ccnr_accession a, ccnr_hwm hwm)
{
    return(a <= hwm);
}

PUBLIC ccnr_hwm
ccnr_hwm_update(struct ccnr_handle *ccnr, ccnr_hwm hwm, ccnr_accession a)
{
    return(a <= hwm ? hwm : a);
}

PUBLIC ccnr_hwm
ccnr_hwm_merge(struct ccnr_handle *ccnr, ccnr_hwm x, ccnr_hwm y)
{
    return(x < y ? y : x);
}

PUBLIC int
ccnr_hwm_compare(struct ccnr_handle *ccnr, ccnr_hwm x, ccnr_hwm y)
{
    if (x > y) return 1;
    if (x == y) return 0;
    if (x < y) return -1;
    return CCNR_NOT_COMPARABLE;
}
#endif

/**
 * A wrapper for ccnr_msg that takes a sync_plumbing instead of ccnr_handle
 */
PUBLIC void
r_sync_msg(struct sync_plumbing *sdd,
           const char *fmt, ...)
{
    struct ccnr_handle *ccnr = (struct ccnr_handle *)sdd->client_data;
    va_list ap;
    va_start(ap, fmt);
    ccnr_vmsg(ccnr, fmt, ap);
    va_end(ap);
}

PUBLIC int
r_sync_fence(struct sync_plumbing *sdd,
             uint64_t seq_num)
{
    struct ccnr_handle *h = (struct ccnr_handle *)sdd->client_data;
    // TODO: this needs to do something more interesting.
    ccnr_msg(h, "r_sync_fence: seq_num %ju", seq_num);
    h->notify_after = (ccnr_accession) seq_num;
    return (0);
}

/**
 * A wrapper for the sync_notify method that takes a content entry.
 */
PUBLIC int
r_sync_notify_content(struct ccnr_handle *ccnr, int e, struct content_entry *content)
{
    struct sync_plumbing *sync_plumbing = ccnr->sync_plumbing;
    int res;
    ccnr_accession acc = CCNR_NULL_ACCESSION;

    if (sync_plumbing == NULL)
        return (0);

    if (content == NULL) {
        if (e == 0)
            abort();
        res = sync_plumbing->sync_methods->sync_notify(ccnr->sync_plumbing, NULL, e, 0);
        if (res < 0)
            ccnr_msg(ccnr, "sync_notify(..., NULL, %d, 0) returned %d, expected >= 0",
                     e, res);
    }
    else {
        struct ccn_charbuf *cb = r_util_charbuf_obtain(ccnr);

        acc = r_store_content_accession(ccnr, content);
        if (acc == CCNR_NULL_ACCESSION) {
            ccnr_debug_content(ccnr, __LINE__, "r_sync_notify_content - not yet stable", NULL, content);
            return(0);
        }
        /* This must get the full name, including digest. */
        ccn_name_init(cb);
        res = r_store_name_append_components(cb, ccnr, content, 0, -1);
        if (res < 0) abort();
        if (CCNSHOULDLOG(ccnr, r_sync_notify_content, CCNL_FINEST))
            ccnr_debug_content(ccnr, __LINE__, "r_sync_notify_content", NULL, content);
        res = sync_plumbing->sync_methods->sync_notify(ccnr->sync_plumbing, cb, e, acc);
        r_util_charbuf_release(ccnr, cb);
    }
    if (CCNSHOULDLOG(ccnr, r_sync_notify_content, CCNL_FINEST))
        ccnr_msg(ccnr, "sync_notify(..., %d, 0x%jx, ...) returned %d",
                 e, ccnr_accession_encode(ccnr, acc), res);
    if (e == 0 && res == -1) {
        // TODO: wrong in new sync interface terms
        //r_sync_notify_after(ccnr, CCNR_MAX_ACCESSION); // XXXXXX should be hwm
    }
    return(res);
}

/**
 *  State for an ongoing sync enumeration.
 */
struct sync_enumeration_state {
    int magic; /**< for sanity check - should be se_cookie */
    int index; /**< Index into ccnr->active_enum */
    ccnr_cookie cookie; /**< Resumption point */
    struct ccn_parsed_interest parsed_interest;
    struct ccn_charbuf *interest;
    struct ccn_indexbuf *comps;
};
static const int se_cookie = __LINE__;

static struct sync_enumeration_state *
cleanup_se(struct ccnr_handle *ccnr, struct sync_enumeration_state *md)
{
    if (md != NULL && md->magic == se_cookie) {
        int i = md->index;
        if (CCNSHOULDLOG(ccnr, cleanup_se, CCNL_FINEST))
            ccnr_msg(ccnr, "sync_enum_cleanup %d", i);
        if (0 < i && i < CCNR_MAX_ENUM)
            ccnr->active_enum[i] = CCNR_NULL_ACCESSION;
        ccn_indexbuf_destroy(&md->comps);
        ccn_charbuf_destroy(&md->interest);
        free(md);
    }
    return(NULL);
}

static int
r_sync_enumerate_action(struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags)
{
    struct ccnr_handle *ccnr = clienth;
    struct sync_enumeration_state *md = NULL;
    struct content_entry *content = NULL;
    struct ccn_btree_node *leaf = NULL;
    struct ccn_charbuf *interest = NULL;
    struct ccn_parsed_interest *pi = NULL;
    struct ccn_charbuf *scratch = NULL;
    struct ccn_charbuf *flat = NULL;
    int ndx;
    int res;
    int try;
    int matches;
    
    md = ev->evdata;
    if (md->magic != se_cookie || md->index >= CCNR_MAX_ENUM) abort();
    if ((flags & CCN_SCHEDULE_CANCEL) != 0) {
        ev->evdata = cleanup_se(ccnr, md);
        return(0);
    }
    pi = &md->parsed_interest;
    interest = md->interest;
    /*
     * Recover starting point from either cookie or accession.
     *
     * The accession number might not be available yet (but we try to avoid
     * suspending in such a case).
     * The cookie might go away, but only if the content has been accessioned.
     */
    content = r_store_content_from_cookie(ccnr, md->cookie);
    if (content == NULL && md->cookie != 0)
        content = r_store_content_from_accession(ccnr, ccnr->active_enum[md->index]);
    for (try = 0, matches = 0; content != NULL; try++) {
        if (scratch == NULL)
            scratch = ccn_charbuf_create();
        flat = r_store_content_flatname(ccnr, content);
        res = ccn_btree_lookup(ccnr->btree, flat->buf, flat->length, &leaf);
        if (CCN_BT_SRCH_FOUND(res) == 0) {
            ccnr_debug_content(ccnr, __LINE__, "impossible", NULL, content);
            break;
        }
        ndx = CCN_BT_SRCH_INDEX(res);
        res = ccn_btree_match_interest(leaf, ndx, interest->buf, pi, scratch);
        if (res == -1) {
            ccnr_debug_content(ccnr, __LINE__, "impossible", NULL, content);
            break;
        }
        if (res == 1) {
            res = r_sync_notify_content(ccnr, md->index, content);
            matches++;
            if (res == -1) {
                if (CCNSHOULDLOG(ccnr, r_sync_enumerate_action, CCNL_FINEST))
                    ccnr_debug_content(ccnr, __LINE__, "r_sync_enumerate_action", NULL,
                                       content);
                ev->evdata = cleanup_se(ccnr, md);
                ccn_charbuf_destroy(&scratch);
                return(0);
            }
        }
        content = r_store_content_next(ccnr, content);
        if (content != NULL &&
            !r_store_content_matches_interest_prefix(ccnr, content,
                                                     interest->buf,
                                                     interest->length))
            content = NULL;
        if (content != NULL) {
            md->cookie = r_store_content_cookie(ccnr, content);
            ccnr->active_enum[md->index] = r_store_content_accession(ccnr, content);
            if (ccnr->active_enum[md->index] != CCNR_NULL_ACCESSION && 
                (matches >= 8 || try >= 200)) { // XXX - these numbers need tuning
                ccn_charbuf_destroy(&scratch);
                return(300);
            }
        }
    }
    r_sync_notify_content(ccnr, md->index, NULL);
    ev->evdata = cleanup_se(ccnr, md);
    ccn_charbuf_destroy(&scratch);
    return(0);
}

/**
 * Request that a SyncNotifyContent call be made for each content object
 *  in the repository that matches the interest.
 *
 * If SyncNotifyContent returns -1 the active enumeration will be cancelled.
 *
 * When there are no more matching objects, SyncNotifyContent will be called
 *  passing NULL for name.
 *
 * Content objects that arrive during an enumeration may or may not be included
 *  in that enumeration.
 *
 *  @returns -1 for error, or an enumeration number which will also be passed
 *      in the SyncNotifyContent
 */
PUBLIC int
r_sync_enumerate(struct sync_plumbing *sdd,
                 struct ccn_charbuf *interest)
{
    struct ccnr_handle *ccnr = (struct ccnr_handle *)sdd->client_data;
    int ans = -1;
    int i;
    int res;
    struct ccn_indexbuf *comps = NULL;
    struct ccn_parsed_interest parsed_interest = {0};
    struct ccn_parsed_interest *pi = &parsed_interest;
    struct content_entry *content = NULL;
    struct sync_enumeration_state *md = NULL;
    
    if (CCNSHOULDLOG(ccnr, r_sync_enumerate, CCNL_FINEST))
        ccnr_debug_ccnb(ccnr, __LINE__, "sync_enum_start", NULL,
                        interest->buf, interest->length);
    comps = ccn_indexbuf_create();
    res = ccn_parse_interest(interest->buf, interest->length, pi, comps);
    if (res < 0) {
        ccnr_debug_ccnb(ccnr, __LINE__, "bogus r_sync_enumerate request", NULL,
                        interest->buf, interest->length);
        if (CCNSHOULDLOG(ccnr, r_sync_enumerate, CCNL_FINEST)) {
            struct ccn_charbuf *temp = ccn_charbuf_create();
            ccn_charbuf_putf(temp, "interest_dump ");
            for (i = 0; i < interest->length; i++)
                ccn_charbuf_putf(temp, "%02X", interest->buf[i]);
            ccnr_msg(ccnr, ccn_charbuf_as_string(temp));
            ccn_charbuf_destroy(&temp);
        }
        goto Bail;
    }
    /* 0 is for notify_after - don't allocate it here. */
    for (i = 1; i < CCNR_MAX_ENUM; i++) {
        if (ccnr->active_enum[i] == CCNR_NULL_ACCESSION) {
            ans = i;
            ccnr->active_enum[ans] = CCNR_MAX_ACCESSION; /* for no-match case */
            break;
        }
    }
    if (ans < 0) {
        if (CCNSHOULDLOG(ccnr, r_sync_enumerate, CCNL_WARNING))
            ccnr_msg(ccnr, "sync_enum - Too many active enumerations!", ans);
        goto Bail;
    }
    content = r_store_find_first_match_candidate(ccnr, interest->buf, pi);
    if (content == NULL) {
        if (CCNSHOULDLOG(ccnr, r_sync_enumerate, CCNL_FINE))
            ccnr_debug_ccnb(ccnr, __LINE__, "sync_enum_nomatch", NULL,
                        interest->buf, interest->length);
    }
    else if (r_store_content_matches_interest_prefix(ccnr,
           content, interest->buf, interest->length)) {
        ccnr->active_enum[ans] = r_store_content_accession(ccnr, content);
        if (CCNSHOULDLOG(ccnr, r_sync_enumerate, CCNL_FINEST))
            ccnr_msg(ccnr, "sync_enum id=%d starting accession=0x%jx",
                     ans, ccnr_accession_encode(ccnr, ccnr->active_enum[ans]));
    }
    
    /* Set up the state for r_sync_enumerate_action */
    md = calloc(1, sizeof(*md));
    if (md == NULL) { ccnr->active_enum[ans] = CCNR_NULL_ACCESSION; ans = -1; goto Bail; }
    md->magic = se_cookie;
    md->cookie = content == NULL ? 0 : r_store_content_cookie(ccnr, content);
    md->index = ans;
    md->interest = ccn_charbuf_create();
    if (md->interest == NULL) goto Bail;
    ccn_charbuf_append(md->interest, interest->buf, interest->length);
    md->parsed_interest = parsed_interest;
    md->comps = comps;
    comps = NULL;

    /* All the upcalls happen in r_sync_enumerate_action. */
    
    if (NULL != ccn_schedule_event(ccnr->sched, 123, r_sync_enumerate_action, md, 0))
        md = NULL;
    
Bail:
    if (md != NULL) {
        ans = -1;
        md = cleanup_se(ccnr, md);
    }
    ccn_indexbuf_destroy(&comps);
    if (CCNSHOULDLOG(ccnr, r_sync_enumerate, CCNL_FINEST))
        ccnr_msg(ccnr, "sync_enum %d", ans);
    return(ans);
}

PUBLIC int
r_sync_lookup(struct sync_plumbing *sdd,
              struct ccn_charbuf *interest,
              struct ccn_charbuf *content_ccnb)
{
    struct ccnr_handle *ccnr = (struct ccnr_handle *)sdd->client_data;
    return(r_lookup(ccnr, interest, content_ccnb));
}

PUBLIC int
r_lookup(struct ccnr_handle *ccnr,
                  struct ccn_charbuf *interest,
                  struct ccn_charbuf *content_ccnb)
{
    int ans = -1;
    struct ccn_indexbuf *comps = r_util_indexbuf_obtain(ccnr);
    struct ccn_parsed_interest parsed_interest = {0};
    struct ccn_parsed_interest *pi = &parsed_interest;
    struct content_entry *content = NULL;
    
    if (NULL == comps || (ccn_parse_interest(interest->buf, interest->length, pi, comps) < 0))
        abort();
    content = r_store_lookup(ccnr, interest->buf, pi, comps);
    if (content != NULL) {
        ans = 0;
        if (content_ccnb != NULL) {
            const unsigned char *base = r_store_content_base(ccnr, content);
            size_t size = r_store_content_size(ccnr, content);
            if (base == NULL) {
                ccnr_debug_ccnb(ccnr, __LINE__, "r_sync_lookup null content base", NULL,
                                interest->buf, interest->length);
                ans = -1;
            } else
                ccn_charbuf_append(content_ccnb, base, size);
        }
    }
    r_util_indexbuf_release(ccnr, comps);
    return(ans);
}
/**
 * Called when a content object is received by sync and needs to be
 * committed to stable storage by the repo.
 */
PUBLIC enum ccn_upcall_res
r_sync_upcall_store(struct sync_plumbing *sdd,
                    enum ccn_upcall_kind kind,
                    struct ccn_upcall_info *info)
{
    struct ccnr_handle *ccnr = (struct ccnr_handle *)sdd->client_data;
    enum ccn_upcall_res ans = CCN_UPCALL_RESULT_OK;
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    struct content_entry *content;
    
    if (kind != CCN_UPCALL_CONTENT)
        return(CCN_UPCALL_RESULT_ERR);
    
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    
    content = process_incoming_content(ccnr, r_io_fdholder_from_fd(ccnr, ccn_get_connection_fd(info->h)),
                                       (void *)ccnb, ccnb_size, NULL);
    if (content == NULL) {
        ccnr_msg(ccnr, "r_sync_upcall_store: failed to process incoming content");
        return(CCN_UPCALL_RESULT_ERR);
    }
    // XXX - here we need to check if this is something we *should* be storing, according to our policy
    if ((r_store_content_flags(content) & CCN_CONTENT_ENTRY_STABLE) == 0) {
        r_store_commit_content(ccnr, content);
        if (CCNSHOULDLOG(ccnr, r_sync_upcall_store, CCNL_FINE))
            ccnr_debug_content(ccnr, __LINE__, "content_stored",
                               r_io_fdholder_from_fd(ccnr, ccnr->active_out_fd),
                               content);
    }        
    r_proto_initiate_key_fetch(ccnr, ccnb, info->pco, 0,
                               r_store_content_cookie(ccnr, content));

    return(ans);
}

/**
 * Called when a content object has been constructed locally by sync
 * and needs to be committed to stable storage by the repo.
 * returns 0 for success, -1 for error.
 */

PUBLIC int
r_sync_local_store(struct sync_plumbing *sdd,
                   struct ccn_charbuf *content_cb)
{
    struct ccnr_handle *ccnr = (struct ccnr_handle *)sdd->client_data;
    struct content_entry *content = NULL;
    
    // pretend it came from the internal client, for statistics gathering purposes
    content = process_incoming_content(ccnr, ccnr->face0,
                                       (void *)content_cb->buf, content_cb->length, NULL);
    if (content == NULL) {
        ccnr_msg(ccnr, "r_sync_local_store: failed to process content");
        return(-1);
    }
    // XXX - we assume we must store things from sync independent of policy
    // XXX - sync may want notification, or not, at least for now.
    if ((r_store_content_flags(content) & CCN_CONTENT_ENTRY_STABLE) == 0) {
        r_store_commit_content(ccnr, content);
        if (CCNSHOULDLOG(ccnr, r_sync_local_store, CCNL_FINE))
            ccnr_debug_content(ccnr, __LINE__, "content_stored_local",
                               r_io_fdholder_from_fd(ccnr, ccnr->active_out_fd),
                               content);
    }
    return(0);
}
