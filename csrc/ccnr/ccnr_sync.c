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
    
    if (CCNSHOULDLOG(ccnr, r_sync_enumerate, CCNL_FINEST))
        ccnr_debug_ccnb(ccnr, __LINE__, "sync_enum_start", NULL,
                        interest->buf, interest->length);
    comps = ccn_indexbuf_create();
    res = ccn_parse_interest(interest->buf, interest->length, pi, comps);
    if (res < 0) {
        ccnr_debug_ccnb(ccnr, __LINE__, "bogus r_sync_enumerate request", NULL,
                        interest->buf, interest->length);
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
        if (!ccn_content_matches_interest(content->key, content->size,
             0, NULL, interest->buf, interest->length, pi)) {
            content = NULL;
        }
        else {
            if (CCNSHOULDLOG(ccnr, r_sync_enumerate, CCNL_FINEST))
                ccnr_msg(ccnr, "sync_enum %ju", (uintmax_t)content->accession);
            ccnr->active_enum[ans] = content->accession;
        }
    }
    
Bail:
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
