/**
 * @file ccnr_sync.c
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

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/types.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/indexbuf.h>
#include <ccn/schedule.h>

#include <sync/SyncBase.h>

#include "ccnr_private.h"

#include "ccnr_dispatch.h"
#include "ccnr_io.h"
#include "ccnr_msg.h"
#include "ccnr_sendq.h"
#include "ccnr_store.h"
#include "ccnr_sync.h"
#include "ccnr_util.h"

PUBLIC void
r_sync_notify_after(struct ccnr_handle *ccnr,
                    ccn_accession_t item)
{
    ccnr->notify_after = item;
}

/**
 * A wrapper for SyncNotifyContent that can work with a content_entry.
 */
PUBLIC int
r_sync_notify_content(struct ccnr_handle *ccnr, int e, struct content_entry *content)
{
    int res;
    uintmax_t acc = 0;
    
    if (content == NULL) {
        res = SyncNotifyContent(ccnr->sync_handle, e, 0, NULL, NULL);
        if (res != -1)
            ccnr_msg(ccnr, "errrrrr - expected -1 result from SyncNotifyContent(..., %d, 0, NULL, NULL), but got %d",
                     e, res);
    }
    else {
        // XXX - ugh, content_entry doesn't have the data in exactly the format we want.
        struct ccn_indexbuf *comps = r_util_indexbuf_obtain(ccnr);
        struct ccn_charbuf *cb = r_util_charbuf_obtain(ccnr);
        int i;
        acc = content->accession;
        ccn_charbuf_append(cb, content->key, content->size);
        ccn_indexbuf_reserve(comps, content->ncomps);
        for (i = 0; i < content->ncomps; i++)
            ccn_indexbuf_append_element(comps, content->comps[i]);
        // XXX - SyncNotifyContent apparently depends on having the complete name.
        // this is what we have at the moment, but that will change.
        if (CCNSHOULDLOG(ccnr, r_sync_notify_content, CCNL_FINEST))
            ccnr_debug_ccnb(ccnr, __LINE__, "r_sync_notify_content", NULL, cb->buf, cb->length);
        res = SyncNotifyContent(ccnr->sync_handle, e, content->accession,
                                cb, comps);
        r_util_indexbuf_release(ccnr, comps);
        r_util_charbuf_release(ccnr, cb);
    }
    if (CCNSHOULDLOG(ccnr, r_sync_notify_content, CCNL_FINEST))
        ccnr_msg(ccnr, "SyncNotifyContent(..., %d, %ju, ...) returned %d",
                 e, acc, res);
    if (e == 0 && res == -1)
        r_sync_notify_after(ccnr, CCNR_MAX_ACCESSION);
    return(res);
}

/**
 *  State for an ongoing sync enumeration.
 */
struct sync_enumeration_state {
    int magic; /**< for sanity check - should be se_cookie */
    int index; /**< Index into ccnr->active_enum */
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
            ccnr->active_enum[i] = 0;
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
    struct ccn_charbuf *interest = NULL;
    struct ccn_indexbuf *comps = NULL;
    struct ccn_parsed_interest *pi = NULL;
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
    comps = md->comps;
    
    content = r_store_content_from_accession(ccnr, ccnr->active_enum[md->index]);
    for (try = 0, matches = 0; content != NULL; try++) {
        if (ccn_content_matches_interest(content->key,
                                         content->size,
                                         0, NULL, interest->buf, interest->length, pi)) {
            res = r_sync_notify_content(ccnr, md->index, content);
            matches++;
            if (res == -1) {
                if (CCNSHOULDLOG(ccnr, r_sync_enumerate_action, CCNL_FINEST))
                    ccnr_debug_ccnb(ccnr, __LINE__, "r_sync_enumerate_action", NULL,
                                    content->key,
                                    content->size);
                ev->evdata = cleanup_se(ccnr, md);
                return(0);
            }
        }
        content = r_store_content_from_accession(ccnr, r_store_content_skiplist_next(ccnr, content));
        if (content != NULL &&
            !r_store_content_matches_interest_prefix(ccnr, content, interest->buf,
                                                     comps, pi->prefix_comps))
            content = NULL;
        if (content != NULL) {
            ccnr->active_enum[md->index] = content->accession;
            if (matches >= 8 || try >= 200) { // XXX - these numbers need tuning
                return(300);
            }
        }
    }
    r_sync_notify_content(ccnr, md->index, NULL);
    ev->evdata = cleanup_se(ccnr, md);
    return(0);
}

/**
 * Request that a SyncNotifyContent call be made for each content object
 *  in the repository that matches the interest.
 *
 * If SyncNotifyContent returns -1 the active enumeration will be cancelled.
 *
 * When there are no more matching objects, SyncNotifyContent will be called
 *  passing NULL for both content_ccnb and content_comps.
 *
 * Content objects that arrive during an enumeration may or may not be included
 *  in that enumeration.
 *
 *  @returns -1 for error, or an enumeration number which will also be passed
 *      in the SyncNotifyContent
 */
PUBLIC int
r_sync_enumerate(struct ccnr_handle *ccnr,
                 struct ccn_charbuf *interest)
{
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
        if (ccnr->active_enum[i] == 0) {
            ans = i;
            ccnr->active_enum[ans] = ~(ccn_accession_t)0; /* for no-match case */
            break;
        }
    }
    if (ans < 0) {
        if (CCNSHOULDLOG(ccnr, r_sync_enumerate, CCNL_WARNING))
            ccnr_msg(ccnr, "sync_enum - Too many active enumerations!", ans);
        goto Bail;
    }
    content = r_store_find_first_match_candidate(ccnr, interest->buf, pi);
    if (content != NULL) {
        if (r_store_content_matches_interest_prefix(ccnr,
               content, interest->buf, comps, comps->n - 1)) {
            ccnr->active_enum[ans] = content->accession;
            if (CCNSHOULDLOG(ccnr, r_sync_enumerate, CCNL_FINEST))
                ccnr_msg(ccnr, "sync_enum id=%d starting accession=%ju",
                         ans, (uintmax_t)content->accession);
        }
    }
    
    /* Set up the state for r_sync_enumerate_action */
    md = calloc(1, sizeof(*md));
    if (md == NULL) { ccnr->active_enum[ans] = 0; ans = -1; goto Bail; }
    md->magic = se_cookie;
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
r_sync_lookup(struct ccnr_handle *ccnr,
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
            // XXX - the extra name component should be excised here.
            ccn_charbuf_append(content_ccnb, content->key, content->size);
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
r_sync_upcall_store(struct ccnr_handle *ccnr,
                    enum ccn_upcall_kind kind,
                    struct ccn_upcall_info *info)
{
    enum ccn_upcall_res ans = CCN_UPCALL_RESULT_OK;
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    struct content_entry *content;
    
    if (kind != CCN_UPCALL_CONTENT)
        return(CCN_UPCALL_RESULT_ERR);
    
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    
    content = process_incoming_content(ccnr, r_io_fdholder_from_fd(ccnr, ccn_get_connection_fd(info->h)),
                                       (void *)ccnb, ccnb_size);
    if (content == NULL) {
        ccnr_msg(ccnr, "r_sync_upcall_store: failed to process incoming content");
        return(CCN_UPCALL_RESULT_ERR);
    }
    // XXX - here we need to check if this is something we *should* be storing, according to our policy
    if ((content->flags & CCN_CONTENT_ENTRY_STABLE) == 0) {
        // Need to actually append to the active repo data file
        r_sendq_face_send_queue_insert(ccnr, r_io_fdholder_from_fd(ccnr, ccnr->active_out_fd), content);
        // XXX - it would be better to do this after the write succeeds
        content->flags |= CCN_CONTENT_ENTRY_STABLE;
        ccnr_debug_ccnb(ccnr, __LINE__, "content_stored",
                        r_io_fdholder_from_fd(ccnr, ccnr->active_out_fd),
                        content->key, content->size);
    }
    
    return(ans);
}

/**
 * Called when a content object has been constructed locally by sync
 * and needs to be committed to stable storage by the repo.
 * returns 0 for success, -1 for error.
 */

PUBLIC int
r_sync_local_store(struct ccnr_handle *ccnr,
                   struct ccn_charbuf *content_cb)
{
    struct content_entry *content = NULL;
    
    // pretend it came from the internal client, for statistics gathering purposes
    content = process_incoming_content(ccnr, r_io_fdholder_from_fd(ccnr, ccn_get_connection_fd(ccnr->internal_client)),
                                       (void *)content_cb->buf, content_cb->length);
    if (content == NULL) {
        ccnr_msg(ccnr, "r_sync_local_store: failed to process content");
        return(-1);
    }
    // XXX - we assume we must store things from sync independent of policy
    // XXX - sync may want notification, or not, at least for now.
    if ((content->flags & CCN_CONTENT_ENTRY_STABLE) == 0) {
        r_sendq_face_send_queue_insert(ccnr, r_io_fdholder_from_fd(ccnr, ccnr->active_out_fd), content);
        // XXX - it would be better to do this after the write succeeds
        content->flags |= CCN_CONTENT_ENTRY_STABLE;
        ccnr_debug_ccnb(ccnr, __LINE__, "content_stored",
                        r_io_fdholder_from_fd(ccnr, ccnr->active_out_fd),
                        content->key, content->size);
    }
    return(0);
}
