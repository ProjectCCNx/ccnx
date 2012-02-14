/**
 * @file ccnd_internal_client.c
 *
 * Internal client of ccnd, handles requests for
 * inspecting and controlling operation of the ccnd;
 * requests and responses themselves use ccn protocols.
 *
 * Part of ccnd - the CCNx Daemon.
 *
 * Copyright (C) 2009-2011 Palo Alto Research Center, Inc.
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
#include <ccn/keystore.h>
#include <ccn/schedule.h>
#include <ccn/sockaddrutil.h>
#include <ccn/uri.h>
#include "ccnd_private.h"

#if 0
#define GOT_HERE ccnd_msg(ccnd, "at ccnd_internal_client.c:%d", __LINE__);
#else
#define GOT_HERE
#endif
#define CCND_NOTICE_NAME "notice.txt"

#ifndef CCND_TEST_100137
#define CCND_TEST_100137 0
#endif

#ifndef CCND_PING
/* The ping responder is deprecated, but enable it by default for now */
#define CCND_PING 1
#endif

static void ccnd_start_notice(struct ccnd_handle *ccnd);

static struct ccn_charbuf *
ccnd_init_service_ccnb(struct ccnd_handle *ccnd, const char *baseuri, int freshness)
{
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    struct ccn *h = ccnd->internal_client;
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
    ccn_create_version(h, name, 0, ccnd->starttime, ccnd->starttime_usec * 1000);
    sp.template_ccnb = ccn_charbuf_create();
    ccn_charbuf_append_tt(sp.template_ccnb, CCN_DTAG_SignedInfo, CCN_DTAG);
    ccn_charbuf_append_tt(sp.template_ccnb, CCN_DTAG_KeyLocator, CCN_DTAG);
    ccn_charbuf_append_tt(sp.template_ccnb, CCN_DTAG_KeyName, CCN_DTAG);
    ccn_charbuf_append_charbuf(sp.template_ccnb, name);
    ccn_charbuf_append_closer(sp.template_ccnb);
//    ccn_charbuf_append_tt(sp.template_ccnb, CCN_DTAG_PublisherPublicKeyDigest,
//                          CCN_DTAG);
//    ccn_charbuf_append_charbuf(sp.template_ccnb, pubid);
//    ccn_charbuf_append_closer(sp.template_ccnb);
    ccn_charbuf_append_closer(sp.template_ccnb);
    ccn_charbuf_append_closer(sp.template_ccnb);
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
 * Local interpretation of selfp->intdata
 */
#define MORECOMPS_MASK 0x007F
#define MUST_VERIFY    0x0080
#define MUST_VERIFY1   (MUST_VERIFY + 1)
#define OPER_MASK      0xFF00
#define OP_PING        0x0000
#define OP_NEWFACE     0x0200
#define OP_DESTROYFACE 0x0300
#define OP_PREFIXREG   0x0400
#define OP_SELFREG     0x0500
#define OP_UNREG       0x0600
#define OP_NOTICE      0x0700
#define OP_SERVICE     0x0800
/**
 * Common interest handler for ccnd_internal_client
 */
static enum ccn_upcall_res
ccnd_answer_req(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    struct ccn_charbuf *msg = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *keylocator = NULL;
    struct ccn_charbuf *signed_info = NULL;
    struct ccn_charbuf *reply_body = NULL;
    struct ccnd_handle *ccnd = NULL;
    int res = 0;
    int start = 0;
    int end = 0;
    int morecomps = 0;
    const unsigned char *final_comp = NULL;
    size_t final_size = 0;
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    
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
    ccnd = (struct ccnd_handle *)selfp->data;
    if ((ccnd->debug & 128) != 0)
        ccnd_debug_ccnb(ccnd, __LINE__, "ccnd_answer_req", NULL,
                        info->interest_ccnb, info->pi->offset[CCN_PI_E]);
    morecomps = selfp->intdata & MORECOMPS_MASK;
    if ((info->pi->answerfrom & CCN_AOK_NEW) == 0 &&
        selfp->intdata != OP_SERVICE &&
        selfp->intdata != OP_NOTICE)
        return(CCN_UPCALL_RESULT_OK);
    if (info->matched_comps >= info->interest_comps->n)
        goto Bail;
    if (selfp->intdata != OP_PING &&
        selfp->intdata != OP_NOTICE &&
        selfp->intdata != OP_SERVICE &&
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
            ccnd_debug_ccnb(ccnd, __LINE__, "co_parse_failed", NULL,
                            info->interest_ccnb, info->pi->offset[CCN_PI_E]);
            goto Bail;
        }
        res = ccn_verify_content(info->h, final_comp, &pco);
        if (res != 0) {
            ccnd_debug_ccnb(ccnd, __LINE__, "co_verify_failed", NULL,
                            info->interest_ccnb, info->pi->offset[CCN_PI_E]);
            goto Bail;
        }
    }
    sp.freshness = 10;
    switch (selfp->intdata & OPER_MASK) {
        case OP_PING:
            reply_body = ccn_charbuf_create();
            sp.freshness = (info->pi->prefix_comps == info->matched_comps) ? 60 : 5;
            res = 0;
            break;
        case OP_NEWFACE:
            reply_body = ccn_charbuf_create();
            res = ccnd_req_newface(ccnd, final_comp, final_size, reply_body);
            break;
        case OP_DESTROYFACE:
            reply_body = ccn_charbuf_create();
            res = ccnd_req_destroyface(ccnd, final_comp, final_size, reply_body);
            break;
        case OP_PREFIXREG:
            reply_body = ccn_charbuf_create();
            res = ccnd_req_prefixreg(ccnd, final_comp, final_size, reply_body);
            break;
        case OP_SELFREG:
            reply_body = ccn_charbuf_create();
            res = ccnd_req_selfreg(ccnd, final_comp, final_size, reply_body);
            break;
        case OP_UNREG:
            reply_body = ccn_charbuf_create();
            res = ccnd_req_unreg(ccnd, final_comp, final_size, reply_body);
            break;
        case OP_NOTICE:
            ccnd_start_notice(ccnd);
            goto Bail;
            break;
        case OP_SERVICE:
            if (ccnd->service_ccnb == NULL)
                ccnd->service_ccnb = ccnd_init_service_ccnb(ccnd, CCNDID_LOCAL_URI, 600);
            if (ccn_content_matches_interest(
                    ccnd->service_ccnb->buf,
                    ccnd->service_ccnb->length,
                    1,
                    NULL,
                    info->interest_ccnb,
                    info->pi->offset[CCN_PI_E],
                    info->pi
                )) {
                ccn_put(info->h, ccnd->service_ccnb->buf,
                                 ccnd->service_ccnb->length);
                res = CCN_UPCALL_RESULT_INTEREST_CONSUMED;
                goto Finish;
            }
            // XXX this needs refactoring.
            if (ccnd->neighbor_ccnb == NULL)
                ccnd->neighbor_ccnb = ccnd_init_service_ccnb(ccnd, CCNDID_NEIGHBOR_URI, 5);
            if (ccn_content_matches_interest(
                    ccnd->neighbor_ccnb->buf,
                    ccnd->neighbor_ccnb->length,
                    1,
                    NULL,
                    info->interest_ccnb,
                    info->pi->offset[CCN_PI_E],
                    info->pi
                )) {
                ccn_put(info->h, ccnd->neighbor_ccnb->buf,
                                 ccnd->neighbor_ccnb->length);
                res = CCN_UPCALL_RESULT_INTEREST_CONSUMED;
                goto Finish;
            }
            goto Bail;
            break;
        default:
            goto Bail;
    }
    if (res < 0)
        goto Bail;
    if (res == CCN_CONTENT_NACK)
        sp.type = res;
    msg = ccn_charbuf_create();
    name = ccn_charbuf_create();
    start = info->pi->offset[CCN_PI_B_Name];
    end = info->interest_comps->buf[info->pi->prefix_comps];
    ccn_charbuf_append(name, info->interest_ccnb + start, end - start);
    ccn_charbuf_append_closer(name);
    res = ccn_sign_content(info->h, msg, name, &sp,
                           reply_body->buf, reply_body->length);
    if (res < 0)
        goto Bail;
    if ((ccnd->debug & 128) != 0)
        ccnd_debug_ccnb(ccnd, __LINE__, "ccnd_answer_req_response", NULL,
                        msg->buf, msg->length);
    res = ccn_put(info->h, msg->buf, msg->length);
    if (res < 0)
        goto Bail;
    if (CCND_TEST_100137)
        ccn_put(info->h, msg->buf, msg->length);
    res = CCN_UPCALL_RESULT_INTEREST_CONSUMED;
    goto Finish;
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
ccnd_internal_client_refresh(struct ccn_schedule *sched,
               void *clienth,
               struct ccn_scheduled_event *ev,
               int flags)
{
    struct ccnd_handle *ccnd = clienth;
    int microsec = 0;
    if ((flags & CCN_SCHEDULE_CANCEL) == 0 &&
          ccnd->internal_client != NULL &&
          ccnd->internal_client_refresh == ev) {
        microsec = ccn_process_scheduled_operations(ccnd->internal_client);
        if (microsec > ev->evint)
            microsec = ev->evint;
    }
    if (microsec <= 0 && ccnd->internal_client_refresh == ev)
        ccnd->internal_client_refresh = NULL;
    return(microsec);
}

#define CCND_ID_TEMPL "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"

static void
ccnd_uri_listen(struct ccnd_handle *ccnd, const char *uri,
                ccn_handler p, intptr_t intdata)
{
    struct ccn_charbuf *name;
    struct ccn_charbuf *uri_modified = NULL;
    struct ccn_closure *closure;
    struct ccn_indexbuf *comps;
    const unsigned char *comp;
    size_t comp_size;
    size_t offset;
    int reg_wanted = 1;
    
    name = ccn_charbuf_create();
    ccn_name_from_uri(name, uri);
    comps = ccn_indexbuf_create();
    if (ccn_name_split(name, comps) < 0)
        abort();
    if (ccn_name_comp_get(name->buf, comps, 1, &comp, &comp_size) >= 0) {
        if (comp_size == 32 && 0 == memcmp(comp, CCND_ID_TEMPL, 32)) {
            /* Replace placeholder with our ccnd_id */
            offset = comp - name->buf;
            memcpy(name->buf + offset, ccnd->ccnd_id, 32);
            uri_modified = ccn_charbuf_create();
            ccn_uri_append(uri_modified, name->buf, name->length, 1);
            uri = (char *)uri_modified->buf;
            reg_wanted = 0;
        }
    }
    closure = calloc(1, sizeof(*closure));
    closure->p = p;
    closure->data = ccnd;
    closure->intdata = intdata;
    /* Register explicitly if needed or requested */
    if (reg_wanted)
        ccnd_reg_uri(ccnd, uri,
                     0, /* special faceid for internal client */
                     CCN_FORW_CHILD_INHERIT | CCN_FORW_ACTIVE,
                     0x7FFFFFFF);
    ccn_set_interest_filter(ccnd->internal_client, name, closure);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&uri_modified);
    ccn_indexbuf_destroy(&comps);
}

/**
 * Make a forwarding table entry for ccnx:/ccnx/CCNDID
 *
 * This one entry handles most of the namespace served by the
 * ccnd internal client.
 */
static void
ccnd_reg_ccnx_ccndid(struct ccnd_handle *ccnd)
{
    struct ccn_charbuf *name;
    struct ccn_charbuf *uri;
    
    name = ccn_charbuf_create();
    ccn_name_from_uri(name, "ccnx:/ccnx");
    ccn_name_append(name, ccnd->ccnd_id, 32);
    uri = ccn_charbuf_create();
    ccn_uri_append(uri, name->buf, name->length, 1);
    ccnd_reg_uri(ccnd, ccn_charbuf_as_string(uri),
                 0, /* special faceid for internal client */
                 (CCN_FORW_CHILD_INHERIT |
                  CCN_FORW_ACTIVE        |
                  CCN_FORW_CAPTURE       |
                  CCN_FORW_ADVERTISE     ),
                 0x7FFFFFFF);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&uri);
}

#ifndef CCN_PATH_VAR_TMP
#define CCN_PATH_VAR_TMP "/var/tmp"
#endif

/*
 * This is used to shroud the contents of the keystore, which mainly serves
 * to add integrity checking and defense against accidental misuse.
 * The file permissions serve for restricting access to the private keys.
 */
#ifndef CCND_KEYSTORE_PASS
#define CCND_KEYSTORE_PASS "\010\043\103\375\327\237\152\351\155"
#endif

int
ccnd_init_internal_keystore(struct ccnd_handle *ccnd)
{
    struct ccn_charbuf *temp = NULL;
    struct ccn_charbuf *cmd = NULL;
    struct ccn_charbuf *culprit = NULL;
    struct stat statbuf;
    const char *dir = NULL;
    int res = -1;
    char *keystore_path = NULL;
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    
    if (ccnd->internal_client == NULL)
        return(-1);
    temp = ccn_charbuf_create();
    cmd = ccn_charbuf_create();
    dir = getenv("CCND_KEYSTORE_DIRECTORY");
    if (dir != NULL && dir[0] == '/')
        ccn_charbuf_putf(temp, "%s/", dir);
    else
        ccn_charbuf_putf(temp, CCN_PATH_VAR_TMP "/.ccnx-user%d/", (int)geteuid());
    res = stat(ccn_charbuf_as_string(temp), &statbuf);
    if (res == -1) {
        if (errno == ENOENT)
            res = mkdir(ccn_charbuf_as_string(temp), 0700);
        if (res != 0) {
            culprit = temp;
            goto Finish;
        }
    }
    ccn_charbuf_putf(temp, ".ccnd_keystore_%s", ccnd->portstr);
    keystore_path = strdup(ccn_charbuf_as_string(temp));
    res = stat(keystore_path, &statbuf);
    if (res == 0)
        res = ccn_load_default_key(ccnd->internal_client, keystore_path, CCND_KEYSTORE_PASS);
    if (res >= 0)
        goto Finish;
    /* No stored keystore that we can access; create one. */
    res = ccn_keystore_file_init(keystore_path, CCND_KEYSTORE_PASS, "CCND-internal", 0, 0);
    if (res != 0) {
        culprit = temp;
        goto Finish;
    }
    res = ccn_load_default_key(ccnd->internal_client, keystore_path, CCND_KEYSTORE_PASS);
    if (res != 0)
        culprit = temp;
Finish:
    if (culprit != NULL) {
        ccnd_msg(ccnd, "%s: %s:\n", ccn_charbuf_as_string(culprit), strerror(errno));
        culprit = NULL;
    }
    res = ccn_chk_signing_params(ccnd->internal_client, NULL, &sp, NULL, NULL, NULL);
    if (res != 0)
        abort();
    memcpy(ccnd->ccnd_id, sp.pubid, sizeof(ccnd->ccnd_id));
    ccn_charbuf_destroy(&temp);
    ccn_charbuf_destroy(&cmd);
    if (keystore_path != NULL)
        free(keystore_path);
    return(res);
}

static int
post_face_notice(struct ccnd_handle *ccnd, unsigned faceid)
{
    struct face *face = ccnd_face_from_faceid(ccnd, faceid);
    struct ccn_charbuf *msg = ccn_charbuf_create();
    int res = -1;
    int port;
    
    // XXX - text version for trying out stream stuff - replace with ccnb
    if (face == NULL)
        ccn_charbuf_putf(msg, "destroyface(%u);\n", faceid);
    else {
        ccn_charbuf_putf(msg, "newface(%u, 0x%x", faceid, face->flags);
        if (face->addr != NULL &&
            (face->flags & (CCN_FACE_INET | CCN_FACE_INET6)) != 0) {
            ccn_charbuf_putf(msg, ", ");
            port = ccn_charbuf_append_sockaddr(msg, face->addr);
            if (port < 0)
                msg->length--;
            else if (port > 0)
                ccn_charbuf_putf(msg, ":%d", port);
        }
        ccn_charbuf_putf(msg, ");\n", faceid);
    }
    res = ccn_seqw_write(ccnd->notice, msg->buf, msg->length);
    ccn_charbuf_destroy(&msg);
    return(res);
}

static int
ccnd_notice_push(struct ccn_schedule *sched,
               void *clienth,
               struct ccn_scheduled_event *ev,
               int flags)
{
    struct ccnd_handle *ccnd = clienth;
    struct ccn_indexbuf *chface = NULL;
    int i = 0;
    int j = 0;
    int microsec = 0;
    int res = 0;
    
    if ((flags & CCN_SCHEDULE_CANCEL) == 0 &&
            ccnd->notice != NULL &&
            ccnd->notice_push == ev &&
            ccnd->chface != NULL) {
        chface = ccnd->chface;
        ccn_seqw_batch_start(ccnd->notice);
        for (i = 0; i < chface->n && res != -1; i++)
            res = post_face_notice(ccnd, chface->buf[i]);
        ccn_seqw_batch_end(ccnd->notice);
        for (j = 0; i < chface->n; i++, j++)
            chface->buf[j] = chface->buf[i];
        chface->n = j;
        if (res == -1)
            microsec = 3000;
    }
    if (microsec <= 0)
        ccnd->notice_push = NULL;
    return(microsec);
}

/**
 * Called by ccnd when a face undergoes a substantive status change that
 * should be reported to interested parties.
 *
 * In the destroy case, this is called from the hash table finalizer,
 * so it shouldn't do much directly.  Inspecting the face is OK, though.
 */
void
ccnd_face_status_change(struct ccnd_handle *ccnd, unsigned faceid)
{
    struct ccn_indexbuf *chface = ccnd->chface;
    if (chface != NULL) {
        ccn_indexbuf_set_insert(chface, faceid);
        if (ccnd->notice_push == NULL)
            ccnd->notice_push = ccn_schedule_event(ccnd->sched, 2000,
                                                   ccnd_notice_push,
                                                   NULL, 0);
    }
}

static void
ccnd_start_notice(struct ccnd_handle *ccnd)
{
    struct ccn *h = ccnd->internal_client;
    struct ccn_charbuf *name = NULL;
    struct face *face = NULL;
    int i;
    
    if (h == NULL)
        return;
    if (ccnd->notice != NULL)
        return;
    if (ccnd->chface != NULL) {
        /* Probably should not happen. */
        ccnd_msg(ccnd, "ccnd_internal_client.c:%d Huh?", __LINE__);
        ccn_indexbuf_destroy(&ccnd->chface);
    }
    name = ccn_charbuf_create();
    ccn_name_from_uri(name, "ccnx:/ccnx");
    ccn_name_append(name, ccnd->ccnd_id, 32);
    ccn_name_append_str(name, CCND_NOTICE_NAME);
    ccnd->notice = ccn_seqw_create(h, name);
    ccnd->chface = ccn_indexbuf_create();
    for (i = 0; i < ccnd->face_limit; i++) {
        face = ccnd->faces_by_faceid[i];
        if (face != NULL)
            ccn_indexbuf_set_insert(ccnd->chface, face->faceid);
    }
    if (ccnd->chface->n > 0)
        ccnd_face_status_change(ccnd, ccnd->chface->buf[0]);
    ccn_charbuf_destroy(&name);
}

int
ccnd_internal_client_start(struct ccnd_handle *ccnd)
{
    struct ccn *h;
    if (ccnd->internal_client != NULL)
        return(-1);
    if (ccnd->face0 == NULL)
        abort();
    ccnd->internal_client = h = ccn_create();
    if (ccnd_init_internal_keystore(ccnd) < 0) {
        ccn_destroy(&ccnd->internal_client);
        return(-1);
    }
#if (CCND_PING+0)
    ccnd_uri_listen(ccnd, "ccnx:/ccnx/ping",
                    &ccnd_answer_req, OP_PING);
    ccnd_uri_listen(ccnd, "ccnx:/ccnx/" CCND_ID_TEMPL "/ping",
                    &ccnd_answer_req, OP_PING);
#endif
    ccnd_uri_listen(ccnd, "ccnx:/ccnx/" CCND_ID_TEMPL "/newface",
                    &ccnd_answer_req, OP_NEWFACE + MUST_VERIFY1);
    ccnd_uri_listen(ccnd, "ccnx:/ccnx/" CCND_ID_TEMPL "/destroyface",
                    &ccnd_answer_req, OP_DESTROYFACE + MUST_VERIFY1);
    ccnd_uri_listen(ccnd, "ccnx:/ccnx/" CCND_ID_TEMPL "/prefixreg",
                    &ccnd_answer_req, OP_PREFIXREG + MUST_VERIFY1);
    ccnd_uri_listen(ccnd, "ccnx:/ccnx/" CCND_ID_TEMPL "/selfreg",
                    &ccnd_answer_req, OP_SELFREG + MUST_VERIFY1);
    ccnd_uri_listen(ccnd, "ccnx:/ccnx/" CCND_ID_TEMPL "/unreg",
                    &ccnd_answer_req, OP_UNREG + MUST_VERIFY1);
    ccnd_uri_listen(ccnd, "ccnx:/ccnx/" CCND_ID_TEMPL "/" CCND_NOTICE_NAME,
                    &ccnd_answer_req, OP_NOTICE);
    ccnd_uri_listen(ccnd, "ccnx:/%C1.M.S.localhost/%C1.M.SRV/ccnd",
                    &ccnd_answer_req, OP_SERVICE);
    ccnd_uri_listen(ccnd, "ccnx:/%C1.M.S.neighborhood",
                    &ccnd_answer_req, OP_SERVICE);
    ccnd_reg_ccnx_ccndid(ccnd);
    ccnd_reg_uri(ccnd, "ccnx:/%C1.M.S.localhost",
                 0, /* special faceid for internal client */
                 (CCN_FORW_CHILD_INHERIT |
                  CCN_FORW_ACTIVE        |
                  CCN_FORW_LOCAL         ),
                 0x7FFFFFFF);
    ccnd->internal_client_refresh \
    = ccn_schedule_event(ccnd->sched, 50000,
                         ccnd_internal_client_refresh,
                         NULL, CCN_INTEREST_LIFETIME_MICROSEC);
    return(0);
}

void
ccnd_internal_client_stop(struct ccnd_handle *ccnd)
{
    ccnd->notice = NULL; /* ccn_destroy will free */
    if (ccnd->notice_push != NULL)
        ccn_schedule_cancel(ccnd->sched, ccnd->notice_push);
    ccn_indexbuf_destroy(&ccnd->chface);
    ccn_destroy(&ccnd->internal_client);
    ccn_charbuf_destroy(&ccnd->service_ccnb);
    ccn_charbuf_destroy(&ccnd->neighbor_ccnb);
    if (ccnd->internal_client_refresh != NULL)
        ccn_schedule_cancel(ccnd->sched, ccnd->internal_client_refresh);
}
