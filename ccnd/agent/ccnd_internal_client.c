/*
 * ccnd_internal_client.c
 *  
 * Copyright (C) 2009 Palo Alto Research Center, Inc. All rights reserved.
 */

#include <stdint.h>
#include <stdlib.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/ccn_private.h>
#include <ccn/keystore.h>
#include <ccn/schedule.h>
#include <ccn/signing.h>
#include <ccn/uri.h>
#include "ccnd_private.h"

#define MORECOMPS_MASK  0x0FF
#define REG_SELF        0x100

static enum ccn_upcall_res
ccnd_answer_req(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    struct ccn_charbuf *temp = NULL;
    struct ccn_charbuf *msg = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *keylocator = NULL;
    struct ccn_charbuf *signed_info = NULL;
    struct ccn_keystore *keystore = NULL;
    struct ccn_charbuf *reply_body = NULL;
    struct ccnd *ccnd = NULL;
    unsigned char dummy = 0;
    int res = 0;
    int start = 0;
    int end = 0;
    int morecomps = 0;
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
    morecomps = selfp->intdata & MORECOMPS_MASK;
    ccnd = (struct ccnd *)selfp->data;
    if ((info->pi->answerfrom & CCN_AOK_NEW) == 0)
        return(CCN_UPCALL_RESULT_OK);
    if (info->matched_comps >= info->interest_comps->n)
        goto Bail;
    if (info->pi->prefix_comps != info->matched_comps + morecomps)
        goto Bail;
    
    if ((selfp->intdata & REG_SELF) != 0) {
        const unsigned char *final_comp = NULL;
        size_t final_size = 0;
        res = ccn_name_comp_get(info->interest_ccnb, info->interest_comps,
                                info->matched_comps, &final_comp, &final_size);
        if (res >= 0)
            reply_body = ccnd_reg_self(ccnd, final_comp, final_size);
        if (reply_body == NULL)
            goto Bail;
    }
    
    keystore = ccn_keystore_create();
    temp = ccn_charbuf_create();
    ccn_charbuf_putf(temp, "%s/.ccn/.ccn_keystore", getenv("HOME"));
    res = ccn_keystore_init(keystore,
                            ccn_charbuf_as_string(temp),
                            "Th1s1sn0t8g00dp8ssw0rd.");
    if (res != 0)
        goto Bail;
    msg = ccn_charbuf_create();
    name = ccn_charbuf_create();
    start = info->pi->offset[CCN_PI_B_Name];
    end = info->interest_comps->buf[info->pi->prefix_comps];
    ccn_charbuf_append(name, info->interest_ccnb + start, end - start);
    ccn_charbuf_append_closer(name);
    
    /* Construct a key locator containing the key itself */
    keylocator = ccn_charbuf_create();
    ccn_charbuf_append_tt(keylocator, CCN_DTAG_KeyLocator, CCN_DTAG);
    ccn_charbuf_append_tt(keylocator, CCN_DTAG_Key, CCN_DTAG);
    res = ccn_append_pubkey_blob(keylocator, ccn_keystore_public_key(keystore));
    ccn_charbuf_append_closer(keylocator); /* </Key> */
    ccn_charbuf_append_closer(keylocator); /* </KeyLocator> */
    if (res < 0)
        goto Bail;
    signed_info = ccn_charbuf_create();
    res = ccn_signed_info_create(signed_info,
                                 /*pubkeyid*/ccn_keystore_public_key_digest(keystore),
                                 /*publisher_key_id_size*/ccn_keystore_public_key_digest_length(keystore),
                                 /*datetime*/NULL,
                                 /*type*/CCN_CONTENT_DATA,
                                 /*freshness*/ 60,
                                 /*finalblockid*/NULL,
                                 keylocator);
    if (res < 0)
        goto Bail;
    res = ccn_encode_ContentObject(msg, name, signed_info,
                                   reply_body ? reply_body->buf : &dummy,
                                   reply_body ? reply_body->length : 0, NULL,
                                   ccn_keystore_private_key(keystore));
    if (res < 0)
        goto Bail;
    res = ccn_put(info->h, msg->buf, msg->length);
    if (res < 0)
        goto Bail;
    res = CCN_UPCALL_RESULT_INTEREST_CONSUMED;
    goto Finish;
Bail:
    res = CCN_UPCALL_RESULT_ERR;
Finish:
    ccn_charbuf_destroy(&temp);
    ccn_charbuf_destroy(&msg);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&keylocator);
    ccn_charbuf_destroy(&reply_body);
    ccn_charbuf_destroy(&signed_info);
    ccn_keystore_destroy(&keystore);
    return(res);
}

static int
ccnd_internal_client_refresh(struct ccn_schedule *sched,
               void *clienth,
               struct ccn_scheduled_event *ev,
               int flags)
{
    struct ccnd *ccnd = clienth;
    int microsec;
    if ((flags & CCN_SCHEDULE_CANCEL) != 0)
        return(0);
    if (ccnd->internal_client == NULL)
        return(0);
    microsec = ccn_process_scheduled_operations(ccnd->internal_client);
    if (microsec > ev->evint)
        microsec = ev->evint;
    return(microsec);
}

static void
ccnd_uri_listen(struct ccnd *ccnd, const char *uri, ccn_handler p, intptr_t intdata)
{
    struct ccn_charbuf *name;
    struct ccn_closure *closure;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    struct ccn_indexbuf *comps;
    
    name = ccn_charbuf_create();
    ccn_name_from_uri(name, uri);
    comps = ccn_indexbuf_create();
    d = ccn_buf_decoder_start(&decoder, name->buf, name->length);
    if (ccn_parse_Name(d, comps) < 0)
        abort();
    closure = calloc(1, sizeof(*closure));
    closure->p = p;
    closure->data = ccnd;
    closure->intdata = intdata;
    /* To bootstrap, we need to register explicitly */
    ccnd_reg_prefix(ccnd,
                    name->buf,
                    comps,
                    comps->n - 1,
                    0, /* special faceid for internal client */
                    CCN_FORW_CHILD_INHERIT,
                    0x7FFFFFFF);
    ccn_set_interest_filter(ccnd->internal_client, name, closure);
    ccn_charbuf_destroy(&name);
    ccn_indexbuf_destroy(&comps);
}

int
ccnd_internal_client_start(struct ccnd *ccnd)
{
    struct ccn *h;
    if (ccnd->internal_client != NULL)
        return(-1);
    if (ccnd->face0 == NULL)
        abort();
    ccnd->internal_client = h = ccn_create();
    ccnd_uri_listen(ccnd, "ccn:/ccn/ping", &ccnd_answer_req, 0);
    ccnd_uri_listen(ccnd, "ccn:/ccn/reg/self", &ccnd_answer_req, REG_SELF + 1);
    ccnd->internal_client_refresh =
     ccn_schedule_event(ccnd->sched, 1000000,
                        ccnd_internal_client_refresh,
                        NULL, CCN_INTEREST_LIFETIME_MICROSEC);
    return(0);
}

void
ccnd_internal_client_stop(struct ccnd *ccnd)
{
    ccn_destroy(&ccnd->internal_client);
    if (ccnd->internal_client_refresh != NULL) {
        ccnd->internal_client_refresh->evint = 0;
        ccnd->internal_client_refresh = NULL;
    }
        
}
