/**
 * @file ccnd_internal_client.c
 *
 * Internal client of ccnd, handles requests for
 * inspecting and controlling operation of the ccnd;
 * requests and responses themselves use ccn protocols.
 *
 * Part of ccnd - the CCNx Daemon.
 *
 * Copyright (C) 2009-2013 Palo Alto Research Center, Inc.
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
#include <ccn/hashtb.h>
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
static void adjacency_timed_reset(struct ccnd_handle *ccnd, unsigned faceid);

/**
 * Creates a key object using the service discovery name profile.
 */
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
    ccnb_element_begin(sp.template_ccnb, CCN_DTAG_SignedInfo);
    ccnb_element_begin(sp.template_ccnb, CCN_DTAG_KeyLocator);
    ccnb_element_begin(sp.template_ccnb, CCN_DTAG_KeyName);
    ccn_charbuf_append_charbuf(sp.template_ccnb, name);
    ccnb_element_end(sp.template_ccnb);
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

/* These are used in face->adjstate to track our state */
#define ADJ_SOL_SENT (1U << 0)
#define ADJ_SOL_RECV (1U << 1)
#define ADJ_OFR_SENT (1U << 2)
#define ADJ_OFR_RECV (1U << 3)
#define ADJ_CRQ_SENT (1U << 4)
#define ADJ_CRQ_RECV (1U << 5)
#define ADJ_DAT_SENT (1U << 6)
#define ADJ_DAT_RECV (1U << 7)
#define ADJ_TIMEDWAIT (1U << 8)
#define ADJ_PINGING  (1U << 9)
#define ADJ_RETRYING (1U << 10)
#define ADJ_ACTIVE   (1U << 11)

/**
 * Update face->adjstate by setting / clearing the indicated bits.
 *
 * If a bit is in both masks, it is set.
 * @returns the old values, or -1 for an error.
 */
int
adjstate_change_db(struct ccnd_handle *ccnd, struct face *face,
                int set, int clear, int line)
{
    int new;
    int old;
    
    if (face == NULL)
        return(-1);
    old = face->adjstate;
    new = (old & ~clear) | set;
    if (new != old) {
        face->adjstate = new;
        if (ccnd->debug & (2 | 4)) {
            /* display the bits in face->adjstate */
            char f[] = "sSoOcCdDTPRA\0";
            int i;
            for (i = 0; f[i] != 0; i++)
                if (((new >> i) & 1) == 0)
                    f[i] = '.';
            ccnd_msg(ccnd, "ic.%d adjstate %u %s %#x", line,
                     face->faceid, f, face->flags);
        }
    }
    return(old);
}
#define adjstate_change(h, f, s, c) adjstate_change_db(h, f, s, c, __LINE__)
/**
 * Append the URI representation of the adjacency prefix for face to the
 * charbuf cb.
 * @returns 0 for success, -1 for error.
 */
static int
append_adjacency_uri(struct ccnd_handle *ccnd,
                     struct ccn_charbuf *cb, struct face *face)
{
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *comp = NULL;
    int res;
    
    if (face->guid == NULL)
        return(-1);
    comp = ccn_charbuf_create();
    name = ccn_charbuf_create();
    ccn_name_from_uri(name, "ccnx:/%C1.M.FACE");
    ccn_charbuf_append_value(comp, CCN_MARKER_CONTROL, 1);
    ccn_charbuf_append_string(comp, ".M.G");
    ccn_charbuf_append_value(comp, 0, 1);
    ccnd_append_face_guid(ccnd, comp, face);
    ccn_name_append(name, comp->buf, comp->length);
    res = ccn_uri_append(cb, name->buf, name->length, 1);
    ccn_charbuf_destroy(&comp);
    ccn_charbuf_destroy(&name);
    return(res < 0 ? -1 : 0);
}

#define ADJ_REFRESH_SEC 120
#define ADJ_MICROS (ADJ_REFRESH_SEC * 1000000)
/**
 * Scheduled event to refresh adjacency
 */
static int
adjacency_do_refresh(struct ccn_schedule *sched,
                     void *clienth,
                     struct ccn_scheduled_event *ev,
                     int flags)
{
    struct ccnd_handle *ccnd = clienth;
    struct face *face = NULL;
    unsigned both;
    
    face = ccnd_face_from_faceid(ccnd, ev->evint);
    if (face == NULL)
        return(0);
    if ((flags & CCN_SCHEDULE_CANCEL) != 0) {
        adjstate_change(ccnd, face, 0, ADJ_ACTIVE);
        return(0);
    }
    both = ADJ_DAT_RECV | ADJ_DAT_SENT;
    if ((face->adjstate & both) == both) {
        ccnd_adjacency_offer_or_commit_req(ccnd, face);
        if ((face->adjstate & ADJ_PINGING) != 0)
            return((ADJ_MICROS + nrand48(ccnd->seed) % ADJ_MICROS) / 2);
    }
    adjstate_change(ccnd, face, 0, ADJ_ACTIVE);
    return(0);
}

/**
 * Register the adjacency prefix with the given forwarding flags.
 */
static void
ccnd_register_adjacency(struct ccnd_handle *ccnd, struct face *face,
                        unsigned forwarding_flags)
{
    struct ccn_charbuf *uri = NULL;
    unsigned both;
    int res;
    int adj = 0;
    int lifetime = 0;
    
    if ((forwarding_flags & CCN_FORW_ACTIVE) != 0) {
        adj = CCN_FACE_ADJ;
        lifetime = ADJ_REFRESH_SEC; /* seconds */
    }
    both = ADJ_DAT_RECV | ADJ_DAT_SENT;
    if ((face->adjstate & both) != both)
        return;
    uri = ccn_charbuf_create();
    res = append_adjacency_uri(ccnd, uri, face);
    if (res >= 0)
        res = ccnd_reg_uri(ccnd, ccn_charbuf_as_string(uri), face->faceid,
                           forwarding_flags, lifetime);
    if (res >= 0) {
        if ((face->flags & CCN_FACE_ADJ) != adj) {
            face->flags ^= CCN_FACE_ADJ;
            ccnd_face_status_change(ccnd, face->faceid);
        }
        if (lifetime != 0 && (face->adjstate & ADJ_ACTIVE) == 0) {
            ccn_schedule_event(ccnd->sched, lifetime * 1000000,
                               adjacency_do_refresh, NULL, face->faceid);
            adjstate_change(ccnd, face, ADJ_ACTIVE, 0);
        }
    }
    ccn_charbuf_destroy(&uri);
}

/**
 * Scheduled event for getting rid of an old guid cob.
 */
static int
ccnd_flush_guid_cob(struct ccn_schedule *sched,
                    void *clienth,
                    struct ccn_scheduled_event *ev,
                    int flags)
{
    struct ccnd_handle *ccnd = clienth;
    struct face *face = NULL;
    
    if ((flags & CCN_SCHEDULE_CANCEL) != 0)
        return(0);
    face = ccnd_face_from_faceid(ccnd, ev->evint);
    if (face != NULL)
        ccn_charbuf_destroy(&face->guid_cob);
    return(0);
}

/**
 * Create the adjacency content object for our endpoint of the face.
 */
static void
ccnd_init_face_guid_cob(struct ccnd_handle *ccnd, struct face *face)
{
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    struct ccn *h = ccnd->internal_client;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *payload = NULL;
    struct ccn_charbuf *comp = NULL;
    struct ccn_charbuf *cob = NULL;
    int res;
    int seconds = 60; /* freshness in the object */
    int nfresh = 20; /* flush it after this many freshness periods */
    
    if (face->guid == NULL || face->guid_cob != NULL)
        return;
    if ((face->adjstate & (ADJ_OFR_SENT | ADJ_OFR_RECV)) == 0)
        return;
    name = ccn_charbuf_create();
    payload = ccn_charbuf_create();
    comp = ccn_charbuf_create();
    cob = ccn_charbuf_create();
    
    ccn_name_from_uri(name, "ccnx:/%C1.M.FACE");
    /* %C1.G.%00<guid> */
    ccn_charbuf_reset(comp);
    ccn_charbuf_append_value(comp, CCN_MARKER_CONTROL, 1);
    ccn_charbuf_append_string(comp, ".M.G");
    ccn_charbuf_append_value(comp, 0, 1);
    ccnd_append_face_guid(ccnd, comp, face);
    ccn_name_append(name, comp->buf, comp->length);
    ccn_name_from_uri(name, "%C1.M.NODE");
    /* %C1.K.%00<ccndid> */
    ccn_charbuf_reset(comp);
    ccn_charbuf_append_value(comp, CCN_MARKER_CONTROL, 1);
    ccn_charbuf_append_string(comp, ".M.K");
    ccn_charbuf_append_value(comp, 0, 1);
    ccn_charbuf_append(comp, ccnd->ccnd_id, sizeof(ccnd->ccnd_id));
    ccn_name_append(name, comp->buf, comp->length);
    ccn_charbuf_reset(comp);
    ccn_charbuf_putf(comp, "face~%u", face->faceid);
    ccn_name_append(name, comp->buf, comp->length);
    ccn_create_version(h, name, CCN_V_NOW, 0, 0);
    ccn_name_from_uri(name, "%00");
    sp.sp_flags |= CCN_SP_FINAL_BLOCK;
    sp.freshness = seconds;
    res = ccn_sign_content(h, cob, name, &sp, payload->buf, payload->length);
    if (res != 0)
        ccn_charbuf_destroy(&cob);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&payload);
    ccn_charbuf_destroy(&comp);
    ccn_charbuf_destroy(&sp.template_ccnb);
    face->guid_cob = cob;
    if (cob != NULL)
        ccn_schedule_event(ccnd->sched, nfresh * seconds * 1000000 - 800000,
                           ccnd_flush_guid_cob, NULL, face->faceid);
}

/**
 * Isolate the lower and upper bounds for the guid component from Exclude
 *
 * This is used as part of the adjacency protocol.
 */
static int
extract_bounds(const unsigned char *ccnb, struct ccn_parsed_interest *pi,
               const unsigned char **plo, const unsigned char **phi)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = NULL;
    int res = -1;
    int x;
    int y;
    int z;
    size_t sz;
    
    /* We are interested only in the Exclude element. */
    ccnb = ccnb + pi->offset[CCN_PI_B_Exclude];
    sz = pi->offset[CCN_PI_E_Exclude] - pi->offset[CCN_PI_B_Exclude];
    if (sz != 0) {
        d = ccn_buf_decoder_start(&decoder, ccnb, sz);
        if (ccn_buf_match_dtag(d, CCN_DTAG_Exclude)) {
            ccn_buf_advance(d);
            if (ccn_buf_match_dtag(d, CCN_DTAG_Any)) {
                ccn_buf_advance(d);
                ccn_buf_check_close(d);
            }
            else return(-1);
            x = d->decoder.token_index;
            ccn_parse_required_tagged_BLOB(d, CCN_DTAG_Component, 8, 70);
            y = d->decoder.token_index;
            ccn_parse_required_tagged_BLOB(d, CCN_DTAG_Component, 8, 70);
            z = d->decoder.token_index;
            if (ccn_buf_match_dtag(d, CCN_DTAG_Any)) {
                ccn_buf_advance(d);
                ccn_buf_check_close(d);
            }
            else return(-1);
            ccn_buf_check_close(d);
            if (d->decoder.state < 0)
                return (-1);
            if (y - x != z - y)
                return(-1);
            res = ccn_ref_tagged_BLOB(CCN_DTAG_Component, ccnb, x, y, plo, &sz);
            if (res < 0) return(-1);
            res = ccn_ref_tagged_BLOB(CCN_DTAG_Component, ccnb, y, z, phi, &sz);
            if (res < 0) return(-1);
            return(sz);
        }
    }
    return(-1);
}

/**
 * Handle the data that comes back in response to interest sent by
 * send_adjacency_solicit.
 *
 * We don't actually need to do much here, since the protocol is actually
 * looking for an interest from the other side.
 */
static enum ccn_upcall_res
solicit_response(struct ccn_closure *selfp,
                   enum ccn_upcall_kind kind,
                   struct ccn_upcall_info *info)
{
    struct face *face = NULL;
    struct ccnd_handle *ccnd = selfp->data;
    
    switch (kind) {
        case CCN_UPCALL_FINAL:
            free(selfp);
            return(CCN_UPCALL_RESULT_OK);
        default:
            face = ccnd_face_from_faceid(ccnd, selfp->intdata);
            if (face == NULL)
                return(CCN_UPCALL_RESULT_ERR);
            if ((face->adjstate & (ADJ_SOL_SENT)) != 0)
                adjacency_timed_reset(ccnd, face->faceid);
            return(CCN_UPCALL_RESULT_ERR);
    }
}

/**
 * Send an adjacency solitiation interest, to elicit an offer from the
 * other side.
 */
static int
send_adjacency_solicit(struct ccnd_handle *ccnd, struct face *face)
{
    struct ccn_charbuf *name;
    struct ccn_charbuf *c;
    struct ccn_charbuf *g;
    struct ccn_charbuf *templ;
    struct ccn_closure *action = NULL;
    int i;
    int ans = -1;

    if (face == NULL || face->guid != NULL || face->adjstate != 0)
        return(-1);
    /* Need to poke the client library here so that it gets the curren time */
    ccn_process_scheduled_operations(ccnd->internal_client);
    name = ccn_charbuf_create();
    c = ccn_charbuf_create();
    g = ccn_charbuf_create();
    templ = ccn_charbuf_create();
    /* Construct a proposed partial guid, without marker bytes */
    ccn_charbuf_reset(g);
    ccn_charbuf_append_value(g, 0, 1); /* 1 reserved byte of zero */
    /* The first half is chosen by our side */
    for (i = 0; i < 6; i++)
        ccn_charbuf_append_value(g, nrand48(ccnd->seed) & 0xff, 1);
    /* The second half will be chosen by the other side */
    for (i = 0; i < 6; i++)
        ccn_charbuf_append_value(g, 0, 1);
    /* Construct the interest */
    ccn_charbuf_reset(templ);
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccn_name_from_uri(name, "ccnx:/%C1.M.FACE");
    ccn_charbuf_append_charbuf(templ, name);
    /* This interest excludes all but a range of possible guid components */
    ccnb_element_begin(templ, CCN_DTAG_Exclude);
    ccnb_tagged_putf(templ, CCN_DTAG_Any, "");
    ccn_charbuf_reset(c);
    ccn_charbuf_append_string(c, "\xC1.M.G");
    ccn_charbuf_append_value(c, 0, 1);
    ccn_charbuf_append(c, g->buf, g->length);
    ccnb_append_tagged_blob(templ, CCN_DTAG_Component, c->buf, c->length);
    ccn_charbuf_reset(c);
    ccn_charbuf_append_string(c, "\xC1.M.G");
    ccn_charbuf_append_value(c, 0, 1);
    ccn_charbuf_append(c, g->buf, g->length - 6);
    for (i = 0; i < 6; i++)
        ccn_charbuf_append_value(c, 0xff, 1);
    ccnb_append_tagged_blob(templ, CCN_DTAG_Component, c->buf, c->length);
    ccnb_tagged_putf(templ, CCN_DTAG_Any, "");
    ccnb_element_end(templ); /* Exclude */
    /* We don't want to get confused by cached content */
    ccnb_tagged_putf(templ, CCN_DTAG_AnswerOriginKind, "%d", 0);
    /* Only talk to direct peers */
    ccnb_tagged_putf(templ, CCN_DTAG_Scope, "2");
    /* Bypass the FIB - send to just the face we want */
    ccnb_tagged_putf(templ, CCN_DTAG_FaceID, "%u", face->faceid);
    ccnb_element_end(templ); /* Interest */
    action = calloc(1, sizeof(*action));
    if (action != NULL) {
        action->p = &solicit_response;
        action->intdata = face->faceid;
        action->data = ccnd;
        ans = ccn_express_interest(ccnd->internal_client, name, action, templ);
        /* Use the guid slot to hold our proposal */
        if (ans >= 0)
            ans = ccnd_set_face_guid(ccnd, face, g->buf, g->length);
        if (ans >= 0) {
            adjstate_change(ccnd, face, ADJ_SOL_SENT, 0);
            ccnd_internal_client_has_somthing_to_say(ccnd);
        }
        ans = (ans < 0) ? -1 : 0;
    }
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&c);
    ccn_charbuf_destroy(&g);
    ccn_charbuf_destroy(&templ);
    return(ans);
}

/**
 * Scheduled event to call send_adjacency_solicit.
 */
static int
ccnd_do_solicit(struct ccn_schedule *sched,
                void *clienth,
                struct ccn_scheduled_event *ev,
                int flags)
{
    struct ccnd_handle *ccnd = clienth;
    struct face *face = NULL;
    unsigned faceid;
    unsigned check, want;
    
    if ((flags & CCN_SCHEDULE_CANCEL) != 0)
        return(0);
    
    faceid = ev->evint;
    face = ccnd_face_from_faceid(ccnd, faceid);
    if (face == NULL)
        return(0);
    check = CCN_FACE_CONNECTING | CCN_FACE_UNDECIDED | CCN_FACE_NOSEND |
            CCN_FACE_GG | CCN_FACE_MCAST | CCN_FACE_PASSIVE | CCN_FACE_NORECV |
            CCN_FACE_BC | CCN_FACE_ADJ;
    want = 0;
    if (face->adjstate == 0 && (face->flags & check) == want)
        send_adjacency_solicit(ccnd, face);
    return(0);
}

/**
 * Answer an adjacency guid request from any face, based on the guid
 * in the name.
 *
 * @returns CCN_UPCALL_RESULT_INTEREST_CONSUMED if an answer was sent,
 *  otherwise -1.
 */
static int
ccnd_answer_by_guid(struct ccnd_handle *ccnd, struct ccn_upcall_info *info)
{
    struct face *face = NULL;
    unsigned char mb[6] = "\xC1.M.G\x00";
    const unsigned char *p = NULL;
    size_t size = 0;
    unsigned faceid;
    int res;
    
    res = ccn_name_comp_get(info->interest_ccnb, info->interest_comps, 1,
                            &p, &size);
    if (res < 0)
        return(-1);
    if (size < sizeof(mb))
        return(-1);
    if (memcmp(p, mb, sizeof(mb)) != 0)
        return(-1);
    faceid = ccnd_faceid_from_guid(ccnd, p + sizeof(mb), size - sizeof(mb));
    if (faceid == CCN_NOFACEID)
        return(-1);
    face = ccnd_face_from_faceid(ccnd, faceid);
    if (face == NULL)
        return(-1);
    if ((face->flags & CCN_FACE_ADJ) == 0)
        return(-1);
    if (face->guid_cob == NULL)
        ccnd_init_face_guid_cob(ccnd, face);
    if (face->guid_cob == NULL)
        return(-1);
    res = -1;
    if (ccn_content_matches_interest(face->guid_cob->buf,
                                     face->guid_cob->length,
                                     1,
                                     NULL,
                                     info->interest_ccnb,
                                     info->pi->offset[CCN_PI_E],
                                     info->pi
                                     )) {
        ccn_put(info->h, face->guid_cob->buf, face->guid_cob->length);
        res = CCN_UPCALL_RESULT_INTEREST_CONSUMED;
    }
    return(res);
}

/**
 * Handle the data coming back from an adjacency offer or commit request.
 */
static enum ccn_upcall_res
incoming_adjacency(struct ccn_closure *selfp,
                   enum ccn_upcall_kind kind,
                   struct ccn_upcall_info *info)
{
    struct face *face = NULL;
    struct ccnd_handle *ccnd = selfp->data;
    switch (kind) {
        case CCN_UPCALL_FINAL:
            free(selfp);
            return(CCN_UPCALL_RESULT_OK);
        case CCN_UPCALL_CONTENT:
            face = ccnd_face_from_faceid(ccnd, selfp->intdata);
            if (face == NULL)
                return(CCN_UPCALL_RESULT_ERR);
            if ((face->adjstate & (ADJ_TIMEDWAIT)) != 0)
                return(CCN_UPCALL_RESULT_ERR);
            /* XXX - this should scrutinize the data to make sure it is OK */
            if ((face->adjstate & (ADJ_OFR_SENT | ADJ_CRQ_SENT)) != 0)
                adjstate_change(ccnd, face, ADJ_DAT_RECV, 0);
            adjstate_change(ccnd, face, 0, ADJ_PINGING | ADJ_RETRYING);
            if ((face->adjstate & (ADJ_CRQ_RECV)) != 0 &&
                (face->adjstate & (ADJ_DAT_SENT)) == 0 &&
                face->guid_cob != 0) {
                ccn_put(info->h, face->guid_cob->buf,
                                 face->guid_cob->length);
                adjstate_change(ccnd, face, ADJ_DAT_SENT, 0);
                if ((face->adjstate & (ADJ_DAT_RECV)) == 0)
                    ccnd_adjacency_offer_or_commit_req(ccnd, face);
            }
            ccnd_register_adjacency(ccnd, face,
                                    CCN_FORW_CHILD_INHERIT | CCN_FORW_ACTIVE);
            return(CCN_UPCALL_RESULT_OK);
        case CCN_UPCALL_INTEREST_TIMED_OUT:
            face = ccnd_face_from_faceid(ccnd, selfp->intdata);
            if (face == NULL)
                return(CCN_UPCALL_RESULT_ERR);
            if ((face->adjstate & (ADJ_RETRYING | ADJ_TIMEDWAIT)) == 0) {
                /* Retry one time */
                adjstate_change(ccnd, face, ADJ_RETRYING, 0);
                return(CCN_UPCALL_RESULT_REEXPRESS);
            }
            adjacency_timed_reset(ccnd, face->faceid);
            return(CCN_UPCALL_RESULT_OK);
        default:
            face = ccnd_face_from_faceid(ccnd, selfp->intdata);
            if (face != NULL)
                adjacency_timed_reset(ccnd, face->faceid);
            return(CCN_UPCALL_RESULT_ERR);
    }
}

/**
 * Express an interest to pull adjacency information from the other side
 */
void
ccnd_adjacency_offer_or_commit_req(struct ccnd_handle *ccnd, struct face *face)
{
    struct ccn_charbuf *name;
    struct ccn_charbuf *c;
    struct ccn_charbuf *templ;
    struct ccn_closure *action = NULL;
    
    if (face == NULL || face->guid == NULL)
        return;
    if ((face->adjstate & (ADJ_SOL_SENT | ADJ_TIMEDWAIT)) != 0)
        return;
    if ((face->adjstate & (ADJ_PINGING)) != 0)
        return;
    /* Need to poke the client library here so that it gets the current time */
    ccn_process_scheduled_operations(ccnd->internal_client);
    name = ccn_charbuf_create();
    c = ccn_charbuf_create();
    templ = ccn_charbuf_create();
    ccn_name_from_uri(name, "ccnx:/%C1.M.FACE");
    ccn_charbuf_reset(c);
    ccn_charbuf_append_string(c, "\xC1.M.G");
    ccn_charbuf_append_value(c, 0, 1);
    ccnd_append_face_guid(ccnd, c, face);
    ccn_name_append(name, c->buf, c->length);
    ccn_name_from_uri(name, "%C1.M.NODE");
    ccn_charbuf_reset(templ);
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccn_charbuf_append_charbuf(templ, name);
    ccnb_element_begin(templ, CCN_DTAG_Exclude);
    ccn_charbuf_reset(c);
    ccn_charbuf_append_string(c, "\xC1.M.K");
    ccn_charbuf_append_value(c, 0, 1);
    ccn_charbuf_append(c, ccnd->ccnd_id, sizeof(ccnd->ccnd_id));
    ccnb_append_tagged_blob(templ, CCN_DTAG_Component, c->buf, c->length);
    ccnb_element_end(templ); /* Exclude */
    ccnb_tagged_putf(templ, CCN_DTAG_AnswerOriginKind, "%d", 0);
    ccnb_tagged_putf(templ, CCN_DTAG_Scope, "2");
    ccnb_tagged_putf(templ, CCN_DTAG_FaceID, "%u", face->faceid);
    ccnb_element_end(templ); /* Interest */
    action = calloc(1, sizeof(*action));
    if (action != NULL) {
        action->p = &incoming_adjacency;
        action->intdata = face->faceid;
        action->data = ccnd;
        adjstate_change(ccnd, face, ADJ_PINGING, ADJ_RETRYING);
        ccn_express_interest(ccnd->internal_client, name, action, templ);
        if ((face->adjstate & ADJ_OFR_RECV) != 0)
            adjstate_change(ccnd, face, ADJ_CRQ_SENT, 0);
        else
            adjstate_change(ccnd, face, ADJ_OFR_SENT, 0);
    }
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&c);
    ccn_charbuf_destroy(&templ);
}

/**
 * Determine whether an offer matches up with our solicitation.
 */
static void
check_offer_matches_my_solicit(struct ccnd_handle *ccnd, struct face *face,
                               struct ccn_upcall_info *info)
{
    const unsigned char *p = NULL;
    size_t size = 0;
    int res;
    const char *mg = "\xC1.M.G";
    const char *mn = "\xC1.M.NODE";
    
    if (info->pi->prefix_comps != 3)
        return;
    if ((face->adjstate & ADJ_SOL_SENT) == 0)
        return;
    if (face->guid == NULL)
        return;
    res = ccn_name_comp_get(info->interest_ccnb, info->interest_comps, 2,
                            &p, &size);
    if (res < 0)
        return;
    if (size != strlen(mn) || 0 != memcmp(p, mn, size))
        return;
    res = ccn_name_comp_get(info->interest_ccnb, info->interest_comps, 1,
                            &p, &size);
    if (res < 0)
        return;
    res = strlen(mg) + 1;
    if (size != res + face->guid[0] || face->guid[0] <= 6)
        return;
    if (0 != memcmp(p, mg, res))
        return;
    if (0 != memcmp(p + res, face->guid + 1, face->guid[0] - 6))
        return;
    ccnd_forget_face_guid(ccnd, face);
    ccnd_set_face_guid(ccnd, face, p + res, size - res);
    adjstate_change(ccnd, face, ADJ_OFR_RECV, ADJ_SOL_SENT);
}

/**
 * Schedule negotiation of a link guid if appropriate
 */
static void
schedule_adjacency_negotiation(struct ccnd_handle *ccnd, unsigned faceid)
{
    struct face *face = ccnd_face_from_faceid(ccnd, faceid);
    unsigned check, want;
    int delay;
    
    if (face == NULL)
        return;
    check = CCN_FACE_CONNECTING | CCN_FACE_UNDECIDED | CCN_FACE_NOSEND |
            CCN_FACE_GG | CCN_FACE_MCAST | CCN_FACE_PASSIVE | CCN_FACE_NORECV |
            CCN_FACE_BC | CCN_FACE_ADJ;
    want = 0;
    if (ccnd->sched != NULL && (face->flags & check) == want) {
        /* If face creation was initiated remotely, dally a bit longer. */
        delay = 2000 + nrand48(ccnd->seed) % 131072U;
        if ((face->flags & CCN_FACE_PERMANENT) == 0)
            delay += 200000;
        ccn_schedule_event(ccnd->sched, delay, ccnd_do_solicit, NULL, faceid);
    }
}

/**
 * Scheduled event for recovering from a broken adjacency negotiation
 */
static int
adjacency_do_reset(struct ccn_schedule *sched,
                   void *clienth,
                   struct ccn_scheduled_event *ev,
                   int flags)
{
    struct ccnd_handle *ccnd = clienth;
    struct face *face = NULL;
    
    if ((flags & CCN_SCHEDULE_CANCEL) != 0)
        return(0);
    face = ccnd_face_from_faceid(ccnd, ev->evint);
    if (face == NULL)
        return(0);
    if ((face->adjstate & ADJ_TIMEDWAIT) == 0)
        return(0);
    if (face->adjstate != ADJ_TIMEDWAIT) {
        adjstate_change(ccnd, face, ADJ_TIMEDWAIT, ~ADJ_ACTIVE);
        ccnd_forget_face_guid(ccnd, face);
        return(666666);
    }
    adjstate_change(ccnd, face, 0, ~0);
    schedule_adjacency_negotiation(ccnd, face->faceid);
    return(0);
}

/**
 * Schedule recovery from a broken adjacency negotiation
 */
static void
adjacency_timed_reset(struct ccnd_handle *ccnd, unsigned faceid)
{
    struct face *face = ccnd_face_from_faceid(ccnd, faceid);
    
    if (face == NULL || ccnd->sched == NULL)
        return;
    if ((face->flags & CCN_FACE_ADJ) != 0) {
        ccnd_face_status_change(ccnd, faceid);
        face->flags &= ~CCN_FACE_ADJ;
    }
    adjstate_change(ccnd, face, ADJ_TIMEDWAIT, ~ADJ_ACTIVE);
    ccnd_forget_face_guid(ccnd, face);
    ccn_schedule_event(ccnd->sched, 9000000 + nrand48(ccnd->seed) % 8000000U,
                       adjacency_do_reset, NULL, faceid);
}

static int
clean_guest(struct ccn_schedule *sched,
            void *clienth,
            struct ccn_scheduled_event *ev,
            int flags)
{
    struct ccnd_handle *ccnd = clienth;
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    unsigned faceid;
    int res;
    
    if ((flags & CCN_SCHEDULE_CANCEL) != 0)
        return(0);
    faceid = ev->evint;
    hashtb_start(ccnd->guest_tab, e);
    res = hashtb_seek(e, &faceid, sizeof(unsigned), 0);
    if (res < 0)
        return(-1);
    hashtb_delete(e);
    hashtb_end(e);
    return(0);
}

static enum ccn_upcall_res
ccnd_req_guest(struct ccn_closure *selfp,
               enum ccn_upcall_kind kind,
               struct ccn_upcall_info *info)
{
    struct ccnd_handle *ccnd = selfp->data;
    struct hashtb_enumerator ee;
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    struct hashtb_enumerator *e = &ee;
    const char *guest_uri = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *uri = NULL;
    struct face *reqface = NULL;
    struct guest_entry *g = NULL;
    const unsigned char *p = NULL;
    size_t size = 0;
    size_t start = 0;
    size_t end = 0;
    int res;
    
    guest_uri = getenv("CCND_PREFIX");
    if (guest_uri == NULL || guest_uri[0] == 0)
        return(CCN_UPCALL_RESULT_ERR);
    reqface = ccnd_face_from_faceid(ccnd, ccnd->interest_faceid);
    if (reqface == NULL)
        return(CCN_UPCALL_RESULT_ERR);
    if ((reqface->flags & CCN_FACE_GG) != 0)
        return(CCN_UPCALL_RESULT_ERR);
    name = ccn_charbuf_create();
    if (name == NULL)
        return(CCN_UPCALL_RESULT_ERR);
    res = ccn_name_from_uri(name, guest_uri);
    if (res < 0) {
        ccn_charbuf_destroy(&name);
        return(CCN_UPCALL_RESULT_ERR);
    }
    hashtb_start(ccnd->guest_tab, e);
    res = hashtb_seek(e, &reqface->faceid, sizeof(unsigned), 0);
    if (res < 0) {
        ccn_charbuf_destroy(&name);
        return(CCN_UPCALL_RESULT_ERR);
    }
    g = e->data;
    hashtb_end(e);
    if (g->cob != NULL) {
        if (ccn_content_matches_interest(g->cob->buf,
                                         g->cob->length,
                                         1,
                                         NULL,
                                         info->interest_ccnb,
                                         info->pi->offset[CCN_PI_E],
                                         info->pi)) {
            ccn_put(info->h, g->cob->buf, g->cob->length);
            ccn_charbuf_destroy(&name);
            return(CCN_UPCALL_RESULT_INTEREST_CONSUMED);
        }
        /* We have a cob cached; no new one until the old one expires */
        ccn_charbuf_destroy(&name);
        return(CCN_UPCALL_RESULT_ERR);
    }
    if (info->interest_comps->n != 4) {
        ccn_charbuf_destroy(&name);
        return(CCN_UPCALL_RESULT_ERR);
    }
    res = ccn_name_comp_get(info->interest_ccnb, info->interest_comps, 2,
                            &p, &size);
    if (res < 0) {
        ccn_charbuf_destroy(&name);
        return(CCN_UPCALL_RESULT_ERR);
    }
    ccn_name_append(name, p, size);
    uri = ccn_charbuf_create();
    ccn_uri_append(uri, name->buf, name->length, 1);
    ccnd_reg_uri(ccnd, ccn_charbuf_as_string(uri), reqface->faceid,
                 CCN_FORW_CHILD_INHERIT | CCN_FORW_ACTIVE,
                 0x7FFFFFFF);
    g->cob = ccn_charbuf_create();
    ccn_charbuf_reset(name);
    start = info->pi->offset[CCN_PI_B_Name];
    end = info->interest_comps->buf[info->pi->prefix_comps];
    ccn_charbuf_append(name, info->interest_ccnb + start, end - start);
    ccnb_element_end(name);
    ccn_create_version(info->h, name, CCN_V_NOW, 0, 0);
    ccn_name_from_uri(name, "%00");
    sp.sp_flags = CCN_SP_FINAL_BLOCK;
    sp.freshness = 5;
    res = ccn_sign_content(info->h, g->cob, name, &sp, uri->buf, uri->length);
    if (res < 0) {
        ccn_charbuf_destroy(&name);
        ccn_charbuf_destroy(&g->cob);
        ccn_charbuf_destroy(&uri);
        return(CCN_UPCALL_RESULT_ERR);
    }
    ccn_schedule_event(ccnd->sched, sp.freshness * 1000000,
                       clean_guest, NULL, reqface->faceid);
    if (g->cob != NULL &&
        ccn_content_matches_interest(g->cob->buf,
                                     g->cob->length,
                                     1,
                                     NULL,
                                     info->interest_ccnb,
                                     info->pi->offset[CCN_PI_E],
                                     info->pi)) {
        ccn_put(info->h, g->cob->buf, g->cob->length);
        ccn_charbuf_destroy(&name);
        ccn_charbuf_destroy(&uri);
        return(CCN_UPCALL_RESULT_INTEREST_CONSUMED);
    }
    return(CCN_UPCALL_RESULT_OK);
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
#define OP_ADJACENCY   0x0900
#define OP_GUEST       0x0A00
#define OP_SETSTRATEGY 0x0B00
#define OP_GETSTRATEGY 0x0C00
#define OP_REMSTRATEGY 0x0D00

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
    struct face *face = NULL;
    const char *v = NULL;
    
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
        selfp->intdata != OP_NOTICE &&
        selfp->intdata != OP_ADJACENCY &&
        selfp->intdata != OP_GUEST)
        return(CCN_UPCALL_RESULT_OK);
    if (info->matched_comps >= info->interest_comps->n)
        goto Bail;
    if (selfp->intdata != OP_PING &&
        selfp->intdata != OP_NOTICE &&
        selfp->intdata != OP_SERVICE &&
        selfp->intdata != OP_ADJACENCY &&
        selfp->intdata != OP_GUEST &&
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
        case OP_SETSTRATEGY:
            v = "setstrategy";
            reply_body = ccn_charbuf_create();
            res = ccnd_req_strategy(ccnd, final_comp, final_size, v, reply_body);
            break;
        case OP_GETSTRATEGY:
            v = "getstrategy";
            reply_body = ccn_charbuf_create();
            res = ccnd_req_strategy(ccnd, final_comp, final_size, v, reply_body);
            break;
        case OP_REMSTRATEGY:
            v = "removestrategy";
            reply_body = ccn_charbuf_create();
            res = ccnd_req_strategy(ccnd, final_comp, final_size, v, reply_body);
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
        case OP_ADJACENCY:
            if (info->pi->prefix_comps >= 2 && (info->pi->answerfrom & CCN_AOK_CS) != 0) {
                res = ccnd_answer_by_guid(ccnd, info);
                if (res == CCN_UPCALL_RESULT_INTEREST_CONSUMED)
                    goto Finish;
            }
            face = ccnd_face_from_faceid(ccnd, ccnd->interest_faceid);
            if (face == NULL)
                goto Bail;
            if (info->pi->prefix_comps == 1 && face->guid == NULL) {
                const unsigned char *lo = NULL;
                const unsigned char *hi = NULL;
                int size = 0;
                unsigned char mb[6] = "\xC1.M.G\x00";
                
                size = extract_bounds(info->interest_ccnb, info->pi, &lo, &hi);
                if (size > (int)sizeof(mb) &&
                    0 == memcmp(mb, lo, sizeof(mb)) &&
                    0 == memcmp(mb, hi, sizeof(mb))) {
                    size -= sizeof(mb);
                    lo += sizeof(mb);
                    hi += sizeof(mb);
                    // XXX - we may want to be selective about proceeding
                    if ((face->adjstate & ADJ_SOL_SENT) != 0) {
                        /* The solicitations crossed in the mail. Arbitrate. */
                        if (face->guid != NULL && size >= face->guid[0] &&
                            memcmp(lo, face->guid + 1, face->guid[0]) > 0) {
                            ccnd_forget_face_guid(ccnd, face);
                            adjstate_change(ccnd, face, 0, ADJ_SOL_SENT);
                        }
                    }
                    adjstate_change(ccnd, face, ADJ_SOL_RECV, ADJ_TIMEDWAIT);
                    ccnd_generate_face_guid(ccnd, face, size, lo, hi);
                    if (face->guid != NULL) {
                        ccnd_adjacency_offer_or_commit_req(ccnd, face);
                        res = CCN_UPCALL_RESULT_INTEREST_CONSUMED;
                        goto Finish;
                    }
                }
            }
            check_offer_matches_my_solicit(ccnd, face, info);
            if (face->guid_cob == NULL)
                ccnd_init_face_guid_cob(ccnd, face);
            if (face->guid_cob != NULL &&
                ccn_content_matches_interest(face->guid_cob->buf,
                                             face->guid_cob->length,
                                             1,
                                             NULL,
                                             info->interest_ccnb,
                                             info->pi->offset[CCN_PI_E],
                                             info->pi
                                             )) {
                if (info->pi->prefix_comps == 3)
                    adjstate_change(ccnd, face, ADJ_CRQ_RECV, 0);
                if ((face->adjstate & (ADJ_DAT_RECV | ADJ_OFR_RECV)) != 0) {
                    ccn_put(info->h, face->guid_cob->buf,
                            face->guid_cob->length);                    
                    adjstate_change(ccnd, face, ADJ_DAT_SENT, 0);
                    if ((face->adjstate & (ADJ_DAT_RECV)) == 0)
                        ccnd_adjacency_offer_or_commit_req(ccnd, face);
                }
                ccnd_register_adjacency(ccnd, face,
                      CCN_FORW_CHILD_INHERIT | CCN_FORW_ACTIVE);
                res = CCN_UPCALL_RESULT_INTEREST_CONSUMED;
                goto Finish;
            }
            goto Bail;
        case OP_GUEST:
            res = ccnd_req_guest(selfp, kind, info);
            goto Finish;
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
    ccnb_element_end(name);
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
    res = ccn_chk_signing_params(ccnd->internal_client, NULL, &sp, NULL, NULL, NULL, NULL);
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
    int n;
    
    // XXX - text version for trying out stream stuff - replace with ccnb
    if (face == NULL)
        ccn_charbuf_putf(msg, "destroyface(%u);\n", faceid);
    else {
        ccn_charbuf_putf(msg, "newface(%u, 0x%x", faceid, face->flags);
        n = 2;
        if (face->addr != NULL &&
            (face->flags & (CCN_FACE_INET | CCN_FACE_INET6)) != 0) {
            ccn_charbuf_putf(msg, ", ");
            n++;
            port = ccn_charbuf_append_sockaddr(msg, face->addr);
            if (port < 0)
                msg->length--;
            else if (port > 0)
                ccn_charbuf_putf(msg, ":%d", port);
        }
        if ((face->flags & CCN_FACE_ADJ) != 0) {
            for (; n < 4; n++)
                ccn_charbuf_putf(msg, ", ");
            append_adjacency_uri(ccnd, msg, face);
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
 *  Fix up any builtin face attributes that may have changed
 */
static void
adjust_builtin_faceattr(struct ccnd_handle *h, unsigned faceid)
{
    struct face *face;
    unsigned clear;
    unsigned set;
    unsigned checkflags;
    
    face = ccnd_face_from_faceid(h, faceid);
    if (face == NULL)
        return;
    clear = FAM_VALID | FAM_APP | FAM_BCAST | FAM_DC;
    set = 0;
    checkflags = CCN_FACE_UNDECIDED | CCN_FACE_PASSIVE | CCN_FACE_NOSEND;
    if ((face->flags & checkflags) == 0)
        set |= FAM_VALID;
    if ((face->flags & CCN_FACE_GG) != 0)
        set |= FAM_APP;
    if ((face->flags & CCN_FACE_MCAST) != 0)
        set |= FAM_BCAST;
    if ((face->flags & CCN_FACE_DC) != 0)
        set |= FAM_DC;
    face->faceattr_packed = (face->faceattr_packed & ~clear) | set;
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
    
    adjust_builtin_faceattr(ccnd, faceid);
    if (chface != NULL) {
        ccn_indexbuf_set_insert(chface, faceid);
        if (ccnd->notice_push == NULL)
            ccnd->notice_push = ccn_schedule_event(ccnd->sched, 2000,
                                                   ccnd_notice_push,
                                                   NULL, 0);
    }
    schedule_adjacency_negotiation(ccnd, faceid);
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
    
    ccnd_uri_listen(ccnd, "ccnx:/ccnx/" CCND_ID_TEMPL "/setstrategy",
                    &ccnd_answer_req, MUST_VERIFY1 + OP_SETSTRATEGY);
    ccnd_uri_listen(ccnd, "ccnx:/ccnx/" CCND_ID_TEMPL "/getstrategy",
                    &ccnd_answer_req, MUST_VERIFY1 + OP_GETSTRATEGY);
    ccnd_uri_listen(ccnd, "ccnx:/ccnx/" CCND_ID_TEMPL "/removestrategy",
                    &ccnd_answer_req, MUST_VERIFY1 + OP_REMSTRATEGY);
    
    ccnd_uri_listen(ccnd, "ccnx:/ccnx/" CCND_ID_TEMPL "/" CCND_NOTICE_NAME,
                    &ccnd_answer_req, OP_NOTICE);
    ccnd_uri_listen(ccnd, "ccnx:/%C1.M.S.localhost/%C1.M.SRV/ccnd",
                    &ccnd_answer_req, OP_SERVICE);
    ccnd_uri_listen(ccnd, "ccnx:/%C1.M.S.neighborhood",
                    &ccnd_answer_req, OP_SERVICE);
    ccnd_uri_listen(ccnd, "ccnx:/%C1.M.S.neighborhood/guest",
                    &ccnd_answer_req, OP_GUEST);
    ccnd_uri_listen(ccnd, "ccnx:/%C1.M.FACE",
                    &ccnd_answer_req, OP_ADJACENCY);
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
