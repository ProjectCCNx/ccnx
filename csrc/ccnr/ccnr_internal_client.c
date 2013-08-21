/**
 * @file ccnr_internal_client.c
 * 
 * Part of ccnr -  CCNx Repository Daemon.
 *
 */

/*
 * Copyright (C) 2011-2013 Palo Alto Research Center, Inc.
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
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/errno.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/ccn_private.h>
#include <ccn/schedule.h>
#include <ccn/sockaddrutil.h>
#include <ccn/uri.h>
#include <ccn/keystore.h>
#include "ccnr_private.h"

#include "ccnr_internal_client.h"

#include "ccnr_forwarding.h"
#include "ccnr_io.h"
#include "ccnr_msg.h"
#include "ccnr_proto.h"
#include "ccnr_util.h"

static struct ccn_charbuf *
ccnr_init_service_ccnb(struct ccnr_handle *ccnr, struct ccn *h, const char *baseuri, int freshness)
{
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    struct ccn_charbuf *name = ccn_charbuf_create();
    struct ccn_charbuf *pubid = ccn_charbuf_create();
    struct ccn_charbuf *pubkey = ccn_charbuf_create();
    struct ccn_charbuf *keyid = ccn_charbuf_create();
    struct ccn_charbuf *cob = ccn_charbuf_create();
    int res;
    
    res = ccn_get_public_key(h, NULL, pubid, pubkey);
    if (res < 0) abort();
    ccn_name_from_uri(name, baseuri);
    ccn_charbuf_append_value(keyid, CCN_MARKER_CONTROL, 1);
    ccn_charbuf_append_string(keyid, ".M.K");
    ccn_charbuf_append_value(keyid, 0, 1);
    ccn_charbuf_append_charbuf(keyid, pubid);
    ccn_name_append(name, keyid->buf, keyid->length);
    ccn_create_version(h, name, 0, ccnr->starttime, ccnr->starttime_usec * 1000);
    sp.template_ccnb = ccn_charbuf_create();
    ccnb_element_begin(sp.template_ccnb, CCN_DTAG_SignedInfo);
    ccnb_element_begin(sp.template_ccnb, CCN_DTAG_KeyLocator);
    ccnb_element_begin(sp.template_ccnb, CCN_DTAG_KeyName);
    ccn_charbuf_append_charbuf(sp.template_ccnb, name);
    ccnb_element_end(sp.template_ccnb);
//    ccn_charbuf_append_tt(sp.template_ccnb, CCN_DTAG_PublisherPublicKeyDigest,
//                          CCN_DTAG);
//    ccn_charbuf_append_charbuf(sp.template_ccnb, pubid);
//    ccnb_element_end(sp.template_ccnb);
    ccnb_element_end(sp.template_ccnb);
    ccnb_element_end(sp.template_ccnb);
    sp.sp_flags |= CCN_SP_TEMPL_KEY_LOCATOR;
    ccn_name_from_uri(name, "%00");
    sp.sp_flags |= CCN_SP_FINAL_BLOCK;
    sp.type = CCN_CONTENT_KEY;
    sp.freshness = freshness;
    res = ccn_sign_content(h, cob, name, &sp, pubkey->buf, pubkey->length);
    if (res != 0) abort();
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&pubid);
    ccn_charbuf_destroy(&pubkey);
    ccn_charbuf_destroy(&keyid);
    ccn_charbuf_destroy(&sp.template_ccnb);
    return(cob);
}

/**
 * Common interest handler
 */
PUBLIC enum ccn_upcall_res
ccnr_answer_req(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    struct ccn_charbuf *msg = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *keylocator = NULL;
    struct ccn_charbuf *signed_info = NULL;
    struct ccn_charbuf *reply_body = NULL;
    struct ccnr_handle *ccnr = NULL;
    int res = 0;
    int morecomps = 0;
    const unsigned char *final_comp = NULL;
    size_t final_size = 0;
    
    switch (kind) {
        case CCN_UPCALL_FINAL:
            free(selfp);
            return(CCN_UPCALL_RESULT_OK);
        case CCN_UPCALL_INTEREST:
            break;
        case CCN_UPCALL_CONSUMED_INTEREST:
            return(CCN_UPCALL_RESULT_OK);
        default:
            return(CCN_UPCALL_RESULT_ERR);
    }
    ccnr = (struct ccnr_handle *)selfp->data;
    if (CCNSHOULDLOG(ccnr, LM_128, CCNL_FINE))
        ccnr_debug_ccnb(ccnr, __LINE__, "ccnr_answer_req", NULL,
                        info->interest_ccnb, info->pi->offset[CCN_PI_E]);
    morecomps = selfp->intdata & MORECOMPS_MASK;
    if ((info->pi->answerfrom & CCN_AOK_NEW) == 0 &&
        selfp->intdata != OP_SERVICE)
        return(CCN_UPCALL_RESULT_OK);
    if (info->matched_comps >= info->interest_comps->n)
        goto Bail;
    if ((selfp->intdata & OPER_MASK) != OP_SERVICE &&
        info->pi->prefix_comps != info->matched_comps + morecomps)
        goto Bail;
    if (morecomps == 1) {
        res = ccn_name_comp_get(info->interest_ccnb, info->interest_comps,
                                info->matched_comps,
                                &final_comp, &final_size);
        if (res < 0)
            goto Bail;
    }
    if ((selfp->intdata & MUST_VERIFY) != 0) {
        struct ccn_parsed_ContentObject pco = {0};
        // XXX - probably should check for message origin BEFORE verify
        res = ccn_parse_ContentObject(final_comp, final_size, &pco, NULL);
        if (res < 0) {
            ccnr_debug_ccnb(ccnr, __LINE__, "co_parse_failed", NULL,
                            info->interest_ccnb, info->pi->offset[CCN_PI_E]);
            goto Bail;
        }
        res = ccn_verify_content(info->h, final_comp, &pco);
        if (res != 0) {
            ccnr_debug_ccnb(ccnr, __LINE__, "co_verify_failed", NULL,
                            info->interest_ccnb, info->pi->offset[CCN_PI_E]);
            goto Bail;
        }
    }
    switch (selfp->intdata & OPER_MASK) {
        case OP_SERVICE:
            if (ccnr->service_ccnb == NULL)
                ccnr->service_ccnb = ccnr_init_service_ccnb(ccnr, info->h, CCNRID_LOCAL_URI, 600);
            if (ccn_content_matches_interest(
                    ccnr->service_ccnb->buf,
                    ccnr->service_ccnb->length,
                    1,
                    NULL,
                    info->interest_ccnb,
                    info->pi->offset[CCN_PI_E],
                    info->pi
                )) {
                ccn_put(info->h, ccnr->service_ccnb->buf,
                                 ccnr->service_ccnb->length);
                res = CCN_UPCALL_RESULT_INTEREST_CONSUMED;
                goto Finish;
            }
            // XXX this needs refactoring.
            if (ccnr->neighbor_ccnb == NULL)
                ccnr->neighbor_ccnb = ccnr_init_service_ccnb(ccnr, info->h, CCNRID_NEIGHBOR_URI, 5);
            if (ccn_content_matches_interest(
                    ccnr->neighbor_ccnb->buf,
                    ccnr->neighbor_ccnb->length,
                    1,
                    NULL,
                    info->interest_ccnb,
                    info->pi->offset[CCN_PI_E],
                    info->pi
                )) {
                ccn_put(info->h, ccnr->neighbor_ccnb->buf,
                                 ccnr->neighbor_ccnb->length);
                res = CCN_UPCALL_RESULT_INTEREST_CONSUMED;
                goto Finish;
            }
            if (ccn_content_matches_interest(
                                             ccnr->policy_link_cob->buf,
                                             ccnr->policy_link_cob->length,
                                             1,
                                             NULL,
                                             info->interest_ccnb,
                                             info->pi->offset[CCN_PI_E],
                                             info->pi
                                             )) {
                ccn_put(info->h, ccnr->policy_link_cob->buf,
                        ccnr->policy_link_cob->length);
                res = CCN_UPCALL_RESULT_INTEREST_CONSUMED;
                goto Finish;
            }
            goto Bail;
            break;
        default:
            // No other OP_xxx are supported here
            goto Bail;
    }
Bail:
    res = CCN_UPCALL_RESULT_ERR;
Finish:
    ccn_charbuf_destroy(&msg);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&keylocator);
    ccn_charbuf_destroy(&reply_body);
    ccn_charbuf_destroy(&signed_info);
    return(res);
}

static int
ccnr_internal_client_refresh(struct ccn_schedule *sched,
               void *clienth,
               struct ccn_scheduled_event *ev,
               int flags)
{
    struct ccnr_handle *ccnr = clienth;
    int microsec = 0;
    if ((flags & CCN_SCHEDULE_CANCEL) == 0 &&
          ccnr->internal_client != NULL &&
          ccnr->internal_client_refresh == ev) {
        microsec = ccn_process_scheduled_operations(ccnr->internal_client);
        if (microsec > ev->evint)
            microsec = ev->evint;
    }
    if (microsec <= 0 && ccnr->internal_client_refresh == ev)
        ccnr->internal_client_refresh = NULL;
    return(microsec);
}

#define CCNR_ID_TEMPL "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"

PUBLIC void
ccnr_uri_listen(struct ccnr_handle *ccnr, struct ccn *ccn, const char *uri,
                ccn_handler p, intptr_t intdata)
{
    struct ccn_charbuf *name;
    struct ccn_charbuf *uri_modified = NULL;
    struct ccn_closure *closure;
    struct ccn_indexbuf *comps;
    const unsigned char *comp;
    size_t comp_size;
    size_t offset;
    
    name = ccn_charbuf_create();
    ccn_name_from_uri(name, uri);
    comps = ccn_indexbuf_create();
    if (ccn_name_split(name, comps) < 0)
        abort();
    if (ccn_name_comp_get(name->buf, comps, 1, &comp, &comp_size) >= 0) {
        if (comp_size == 32 && 0 == memcmp(comp, CCNR_ID_TEMPL, 32)) {
            /* Replace placeholder with our ccnr_id */
            offset = comp - name->buf;
            memcpy(name->buf + offset, ccnr->ccnr_id, 32);
            uri_modified = ccn_charbuf_create();
            ccn_uri_append(uri_modified, name->buf, name->length, 1);
            uri = (char *)uri_modified->buf;
        }
    }
    closure = calloc(1, sizeof(*closure));
    closure->p = p;
    closure->data = ccnr;
    closure->intdata = intdata;
    ccn_set_interest_filter(ccn, name, closure);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&uri_modified);
    ccn_indexbuf_destroy(&comps);
}

/*
 * This is used to shroud the contents of the keystore, which mainly serves
 * to add integrity checking and defense against accidental misuse.
 * The file permissions serve for restricting access to the private keys.
 */
#ifndef CCNR_KEYSTORE_PASS
#define CCNR_KEYSTORE_PASS "Th1s 1s n0t 8 g00d R3p0s1t0ry p8ssw0rd!"
#endif

/**
 *  Create the repository keystore if necessary,
 *  and load it into the client handle h.
 *
 *  It is permitted for h to be NULL to skip the load.
 *  @returns -1 if there were problems.
 */
 
int
ccnr_init_repo_keystore(struct ccnr_handle *ccnr, struct ccn *h)
{
    struct ccn_charbuf *temp = NULL;
    struct ccn_charbuf *culprit = NULL;
    struct stat statbuf;
    int res = -1;
    char *keystore_path = NULL;
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    
    temp = ccn_charbuf_create();
    culprit = temp;
    ccn_charbuf_putf(temp, "%s/", ccnr->directory);
    res = stat(ccn_charbuf_as_string(temp), &statbuf);
    if (res == -1)
        goto Finish;
    if ((statbuf.st_mode & S_IFDIR) == 0) {
        res = -1;
        errno = ENOTDIR;
        goto Finish;
    }
    ccn_charbuf_putf(temp, "ccnx_repository_keystore");
    keystore_path = strdup(ccn_charbuf_as_string(temp));
    res = stat(keystore_path, &statbuf);
    
    if (res == 0 && h != NULL)
        res = ccn_load_default_key(h, keystore_path, CCNR_KEYSTORE_PASS);
    if (res >= 0) {
        culprit = NULL;
        goto Finish;
    }
    /* No stored keystore that we can access. Create one if we can.*/
    res = ccn_keystore_file_init(keystore_path, CCNR_KEYSTORE_PASS, "Repository", 0, 0);
    if (res != 0) {
        res = -1;
        goto Finish;
    }
    if (CCNSHOULDLOG(ccnr, keystore, CCNL_WARNING))
        ccnr_msg(ccnr, "New repository private key saved in %s", keystore_path);
    if (h != NULL)
        res = ccn_load_default_key(h, keystore_path, CCNR_KEYSTORE_PASS);
Finish:
    if (res >= 0 && h != NULL)
        res = ccn_chk_signing_params(h, NULL, &sp, NULL, NULL, NULL, NULL);
    if (res >= 0 && h != NULL) {
        memcpy(ccnr->ccnr_id, sp.pubid, sizeof(ccnr->ccnr_id));
        if (ccnr->ccnr_keyid == NULL)
            ccnr->ccnr_keyid = ccn_charbuf_create();
        else
            ccnr->ccnr_keyid->length = 0;
        ccn_charbuf_append_value(ccnr->ccnr_keyid, CCN_MARKER_CONTROL, 1);
        ccn_charbuf_append_string(ccnr->ccnr_keyid, ".M.K");
        ccn_charbuf_append_value(ccnr->ccnr_keyid, 0, 1);
        ccn_charbuf_append(ccnr->ccnr_keyid, ccnr->ccnr_id, sizeof(ccnr->ccnr_id));
    }
    if (res < 0) {
        ccnr->running = -1; /* Make note of init failure */
        if (culprit != NULL)
            ccnr_msg(ccnr, "Error accessing keystore - %s: %s\n",
                     strerror(errno), ccn_charbuf_as_string(temp));
    }
    ccn_charbuf_destroy(&temp);
    if (keystore_path != NULL)
        free(keystore_path);
    return(res);
}

static int
post_face_notice(struct ccnr_handle *ccnr, unsigned filedesc)
{
    struct fdholder *fdholder = ccnr_r_io_fdholder_from_fd(ccnr, filedesc);
    struct ccn_charbuf *msg = ccn_charbuf_create();
    int res = -1;
    int port;
    
    // XXX - text version for trying out stream stuff - replace with ccnb
    if (fdholder == NULL)
        ccn_charbuf_putf(msg, "destroyface(%u);\n", filedesc);
    else {
        ccn_charbuf_putf(msg, "newface(%u, 0x%x", filedesc, fdholder->flags);
        if (fdholder->name->length != 0 &&
            (fdholder->flags & (CCNR_FACE_INET | CCNR_FACE_INET6)) != 0) {
            ccn_charbuf_putf(msg, ", ");
            port = ccn_charbuf_append_sockaddr(msg, (struct sockaddr *)fdholder->name->buf);
            if (port < 0)
                msg->length--;
            else if (port > 0)
                ccn_charbuf_putf(msg, ":%d", port);
        }
        ccn_charbuf_putf(msg, ");\n", filedesc);
    }
    res = ccn_seqw_write(ccnr->notice, msg->buf, msg->length);
    ccn_charbuf_destroy(&msg);
    return(res);
}

static int
ccnr_notice_push(struct ccn_schedule *sched,
               void *clienth,
               struct ccn_scheduled_event *ev,
               int flags)
{
    struct ccnr_handle *ccnr = clienth;
    struct ccn_indexbuf *chface = NULL;
    int i = 0;
    int j = 0;
    int microsec = 0;
    int res = 0;
    
    if ((flags & CCN_SCHEDULE_CANCEL) == 0 &&
            ccnr->notice != NULL &&
            ccnr->notice_push == ev &&
            ccnr->chface != NULL) {
        chface = ccnr->chface;
        ccn_seqw_batch_start(ccnr->notice);
        for (i = 0; i < chface->n && res != -1; i++)
            res = post_face_notice(ccnr, chface->buf[i]);
        ccn_seqw_batch_end(ccnr->notice);
        for (j = 0; i < chface->n; i++, j++)
            chface->buf[j] = chface->buf[i];
        chface->n = j;
        if (res == -1)
            microsec = 3000;
    }
    if (microsec <= 0)
        ccnr->notice_push = NULL;
    return(microsec);
}

/**
 * Called by ccnr when a fdholder undergoes a substantive status change that
 * should be reported to interested parties.
 *
 * In the destroy case, this is called from the hash table finalizer,
 * so it shouldn't do much directly.  Inspecting the fdholder is OK, though.
 */
void
ccnr_face_status_change(struct ccnr_handle *ccnr, unsigned filedesc)
{
    struct ccn_indexbuf *chface = ccnr->chface;
    if (chface != NULL) {
        ccn_indexbuf_set_insert(chface, filedesc);
        if (ccnr->notice_push == NULL)
            ccnr->notice_push = ccn_schedule_event(ccnr->sched, 2000,
                                                   ccnr_notice_push,
                                                   NULL, 0);
    }
}

int
ccnr_internal_client_start(struct ccnr_handle *ccnr)
{
    if (ccnr->internal_client != NULL)
        return(-1);
    if (ccnr->face0 == NULL)
        abort();
    ccnr->internal_client = ccn_create();
    if (ccnr_init_repo_keystore(ccnr, ccnr->internal_client) < 0) {
        ccn_destroy(&ccnr->internal_client);
        return(-1);
    }
    ccnr->internal_client_refresh = ccn_schedule_event(ccnr->sched, 50000,
                         ccnr_internal_client_refresh,
                         NULL, CCN_INTEREST_LIFETIME_MICROSEC);
    return(0);
}

void
ccnr_internal_client_stop(struct ccnr_handle *ccnr)
{
    ccnr->notice = NULL; /* ccn_destroy will free */
    if (ccnr->notice_push != NULL)
        ccn_schedule_cancel(ccnr->sched, ccnr->notice_push);
    ccn_indexbuf_destroy(&ccnr->chface);
    ccn_destroy(&ccnr->internal_client);
    ccn_charbuf_destroy(&ccnr->service_ccnb);
    ccn_charbuf_destroy(&ccnr->neighbor_ccnb);
    if (ccnr->internal_client_refresh != NULL)
        ccn_schedule_cancel(ccnr->sched, ccnr->internal_client_refresh);
}

// XXX - these are very similar to the above.
// If we keep multiple internal handles around, this will need refactoring.


static int
ccnr_direct_client_refresh(struct ccn_schedule *sched,
               void *clienth,
               struct ccn_scheduled_event *ev,
               int flags)
{
    struct ccnr_handle *ccnr = clienth;
    int microsec = 0;
    if ((flags & CCN_SCHEDULE_CANCEL) == 0 &&
          ccnr->direct_client != NULL &&
          ccnr->direct_client_refresh == ev) {
        microsec = ccn_process_scheduled_operations(ccnr->direct_client);
        // XXX - This is not really right, since an incoming request can cause us to need to reschedule this event.
        if CCNSHOULDLOG(ccnr, refresh, CCNL_FINEST)
            ccnr_msg(ccnr, "direct_client_refresh %d in %d usec",
                     ccn_get_connection_fd(ccnr->direct_client), microsec);
        if (microsec > ev->evint)
            microsec = ev->evint;
        if (microsec == 0)
            microsec = CCN_INTEREST_LIFETIME_MICROSEC;
    }
    if (microsec <= 0 && ccnr->direct_client_refresh == ev)
        ccnr->direct_client_refresh = NULL;
    return(microsec);
}

int
ccnr_direct_client_start(struct ccnr_handle *ccnr)
{
    ccnr->direct_client = ccn_create();
    if (ccnr_init_repo_keystore(ccnr, ccnr->direct_client) < 0) {
        ccn_destroy(&ccnr->direct_client);
        return(-1);
    }
    ccnr->direct_client_refresh = ccn_schedule_event(ccnr->sched, 50000,
                         ccnr_direct_client_refresh,
                         NULL, CCN_INTEREST_LIFETIME_MICROSEC);
    return(0);
}

void
ccnr_direct_client_stop(struct ccnr_handle *ccnr)
{
    if (ccnr->notice_push != NULL)
        ccn_schedule_cancel(ccnr->sched, ccnr->notice_push);
    ccn_indexbuf_destroy(&ccnr->chface);
    ccn_destroy(&ccnr->direct_client);
    ccn_charbuf_destroy(&ccnr->service_ccnb);
    ccn_charbuf_destroy(&ccnr->neighbor_ccnb);
    if (ccnr->direct_client_refresh != NULL)
        ccn_schedule_cancel(ccnr->sched, ccnr->direct_client_refresh);
}

