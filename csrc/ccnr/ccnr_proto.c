/**
 * @file ccnr_proto.c
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
#include <ccn/coding.h>
#include "ccnr_private.h"

#include "ccnr_proto.h"

#include "ccnr_dispatch.h"
#include "ccnr_forwarding.h"
#include "ccnr_io.h"
#include "ccnr_msg.h"
#include "ccnr_sendq.h"
#include "ccnr_store.h"

#define REPO_SW "\301.R.sw"
#define REPO_SWC "\301.R.sw-c"
#define NAME_BE "\301.E.be"

#define CCNR_MAX_RETRY 5

static enum ccn_upcall_res
r_proto_start_write(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info);
                 
PUBLIC enum ccn_upcall_res
r_proto_answer_req(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    struct ccn_charbuf *msg = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *keylocator = NULL;
    struct ccn_charbuf *signed_info = NULL;
    struct ccn_charbuf *reply_body = NULL;
    struct ccnr_handle *ccnr = NULL;
    struct content_entry *content = NULL;
    int res = 0;
    int ncomps;
    // struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    
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
    if ((ccnr->debug & 128) != 0)
        ccnr_debug_ccnb(ccnr, __LINE__, "r_proto_answer_req", NULL,
                        info->interest_ccnb, info->pi->offset[CCN_PI_E]);
    
    content = r_store_lookup(ccnr, info->interest_ccnb, info->pi, info->interest_comps);
    if (content != NULL) {
        struct fdholder *fdholder = r_io_fdholder_from_fd(ccnr, ccn_get_connection_fd(info->h));
        if (fdholder != NULL)
            r_sendq_face_send_queue_insert(ccnr, r_io_fdholder_from_fd(ccnr, ccn_get_connection_fd(info->h)), content);
        res = CCN_UPCALL_RESULT_INTEREST_CONSUMED;
        goto Finish;
    }
    /* check for command markers */
    ncomps = info->interest_comps->n;
    if (ncomps >= 2 && 0 == ccn_name_comp_strcmp(info->interest_ccnb, info->interest_comps, ncomps - 2, NAME_BE)) {
        ccnr_debug_ccnb(ccnr, __LINE__, "name_enumeration", NULL, info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        res = r_proto_begin_enumeration(selfp, kind, info);
        goto Finish;
    } else if (ncomps >= 3 && 0 == ccn_name_comp_strcmp(info->interest_ccnb, info->interest_comps, ncomps - 3, REPO_SW)) {
        ccnr_debug_ccnb(ccnr, __LINE__, "repo_start_write", NULL, info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        res = r_proto_start_write(selfp, kind, info);
        goto Finish;
    } else if (ncomps >= 5 && 0 == ccn_name_comp_strcmp(info->interest_ccnb, info->interest_comps, ncomps - 5, REPO_SWC)) {
        ccnr_debug_ccnb(ccnr, __LINE__, "repo_start_write_checked", NULL, info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        res = r_proto_start_write_checked(selfp, kind, info);
        goto Finish;
    }
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

PUBLIC void
r_proto_uri_listen(struct ccnr_handle *ccnr, struct ccn *ccn, const char *uri,
                ccn_handler p, intptr_t intdata)
{
    struct ccn_charbuf *name;
    struct ccn_closure *closure;
    
    name = ccn_charbuf_create();
    ccn_name_from_uri(name, uri);
    closure = calloc(1, sizeof(*closure));
    closure->p = p;
    closure->data = ccnr;
    closure->intdata = intdata;
    ccn_set_interest_filter(ccn, name, closure);
    ccn_charbuf_destroy(&name);
}

PUBLIC void
r_proto_init(struct ccnr_handle *ccnr) {
    // This should be done for each of the prefixes served by the repo.
    r_proto_uri_listen(ccnr, ccnr->direct_client, "ccnx:/", r_proto_answer_req, 0);
}

/* Construct a charbuf with an encoding of a RepositoryInfo -- currently a
 * constant, but may need to vary
 *
 *  <RepositoryInfo>
 *  <Version>1.1</Version>
 *  <Type>INFO</Type>
 *  <RepositoryVersion>1.4</RepositoryVersion>
 *  <GlobalPrefixName>
 *  <Component ccnbencoding="text">parc.com</Component>
 *  <Component ccnbencoding="text">csl</Component>
 *  <Component ccnbencoding="text">ccn</Component>
 *  <Component ccnbencoding="text">Repos</Component>
 *  </GlobalPrefixName>
 *  <LocalName>Repository</LocalName>
 *  </RepositoryInfo>
*/ 
PUBLIC int
r_proto_append_repo_info(struct ccnr_handle *ccnr, struct ccn_charbuf *rinfo) {
    // XXX - this is hardwired at present - should come from .meta/* in repo dir
    int res;
    struct ccn_charbuf *name = ccn_charbuf_create();
    if (name == NULL) return (-1);
    res = ccnb_element_begin(rinfo, CCN_DTAG_RepositoryInfo);
    res |= ccnb_tagged_putf(rinfo, CCN_DTAG_Version, "%s", "1.1");
    res |= ccnb_tagged_putf(rinfo, CCN_DTAG_Type, "%s", "INFO");
    res |= ccnb_tagged_putf(rinfo, CCN_DTAG_RepositoryVersion, "%s", "2.0");

    res |= ccnb_element_begin(name, CCN_DTAG_GlobalPrefixName); // same structure as Name
    res |= ccnb_element_end(name);
    res |= ccn_name_append_str(name, "parc.com");
    res |= ccn_name_append_str(name, "csl");
    res |= ccn_name_append_str(name, "ccn");
    res |= ccn_name_append_str(name, "Repos");
    res |= ccn_charbuf_append_charbuf(rinfo, name);
    
    res |= ccnb_tagged_putf(rinfo, CCN_DTAG_LocalName, "%s", "Repository");

    res |= ccnb_element_end(rinfo); // CCN_DTAG_RepositoryInfo
    ccn_charbuf_destroy(&name);
    return (res);
}

struct expect_content {
    struct ccnr_handle *ccnr;
    int done;
    int tries; /** counter so we can give up eventually */
};

static struct ccn_charbuf *
make_template(struct expect_content *md, struct ccn_upcall_info *info)
{
    struct ccn_charbuf *templ = ccn_charbuf_create();
    ccnb_element_begin(templ, CCN_DTAG_Interest); // same structure as Name
    ccnb_element_begin(templ, CCN_DTAG_Name);
    ccnb_element_end(templ); /* </Name> */
    // XXX - use pubid if possible
    // XXX - if start-write was scoped, use scope here
    ccnb_tagged_putf(templ, CCN_DTAG_MinSuffixComponents, "%d", 1);
    ccnb_tagged_putf(templ, CCN_DTAG_MaxSuffixComponents, "%d", 1);
    ccnb_element_end(templ); /* </Interest> */
    return(templ);
}

static int
is_final(struct ccn_upcall_info *info)
{
    // XXX The test below is refactored into the library with 100496 as ccn_is_final_block()
    const unsigned char *ccnb;
    size_t ccnb_size;
    ccnb = info->content_ccnb;
    if (ccnb == NULL || info->pco == NULL)
        return(0);
    ccnb_size = info->pco->offset[CCN_PCO_E];
    if (info->pco->offset[CCN_PCO_B_FinalBlockID] !=
        info->pco->offset[CCN_PCO_E_FinalBlockID]) {
        const unsigned char *finalid = NULL;
        size_t finalid_size = 0;
        const unsigned char *nameid = NULL;
        size_t nameid_size = 0;
        struct ccn_indexbuf *cc = info->content_comps;
        ccn_ref_tagged_BLOB(CCN_DTAG_FinalBlockID, ccnb,
                            info->pco->offset[CCN_PCO_B_FinalBlockID],
                            info->pco->offset[CCN_PCO_E_FinalBlockID],
                            &finalid,
                            &finalid_size);
        if (cc->n < 2) return(-1);
        ccn_ref_tagged_BLOB(CCN_DTAG_Component, ccnb,
                            cc->buf[cc->n - 2],
                            cc->buf[cc->n - 1],
                            &nameid,
                            &nameid_size);
        if (finalid_size == nameid_size &&
            0 == memcmp(finalid, nameid, nameid_size))
            return(1);
    }
    return(0);
}

static enum ccn_upcall_res
r_proto_expect_content(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    const unsigned char *ib = NULL; /* info->interest_ccnb */
    struct ccn_indexbuf *ic = NULL;
    int res;
    struct expect_content *md = selfp->data;
    struct ccnr_handle *ccnr = NULL;
    struct content_entry *content = NULL;

    if (kind == CCN_UPCALL_FINAL) {
        if (md != NULL) {
            selfp->data = NULL;
            free(md);
            md = NULL;
        }
        return(CCN_UPCALL_RESULT_OK);
    }
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT) {
        if (md->tries > CCNR_MAX_RETRY) {
            // XXX - log something here
            return(CCN_UPCALL_RESULT_ERR);
        }
        md->tries++;
        return(CCN_UPCALL_RESULT_REEXPRESS);
    }
    if (kind == CCN_UPCALL_CONTENT_UNVERIFIED)
        return(CCN_UPCALL_RESULT_VERIFY);
    if (kind != CCN_UPCALL_CONTENT)
        return(CCN_UPCALL_RESULT_ERR);
    if (md == NULL)
        return(CCN_UPCALL_RESULT_ERR);
    
    ccnr = md->ccnr;
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    ib = info->interest_ccnb;
    ic = info->interest_comps;
    
    content = process_incoming_content(ccnr, r_io_fdholder_from_fd(ccnr, ccn_get_connection_fd(info->h)), (void *)ccnb, ccnb_size);
    if (content == NULL) {
        // something kinda bad must of happened
        return(CCN_UPCALL_RESULT_ERR);
    }
    if ((content->flags & CCN_CONTENT_ENTRY_STABLE) == 0) {
        // Need to actually append to the active repo data file
        r_sendq_face_send_queue_insert(ccnr, r_io_fdholder_from_fd(ccnr, ccnr->active_out_fd), content);
        // XXX - it would be better to do this after the write succeeds
        content->flags |= CCN_CONTENT_ENTRY_STABLE;
    }
    md->tries = 0;
    // XXX - need to save the keys

    // XXX The test below should get replace by ccn_is_final_block() when it is available
    if (is_final(info) == 1) md->done = 1;    
    if (md->done) {
        // ccn_set_run_timeout(info->h, 0);
        return(CCN_UPCALL_RESULT_OK);
    }
    
    /* Ask for the next fragment */
    name = ccn_charbuf_create();
    ccn_name_init(name);
    if (ic->n < 2) abort();
    res = ccn_name_append_components(name, ib, ic->buf[0], ic->buf[ic->n - 2]);
    if (res < 0) abort();
    ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, ++(selfp->intdata));
    templ = make_template(md, info);
    
    res = ccn_express_interest(info->h, name, selfp, templ);
    if (res < 0) abort();
    
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&name);
    
    return(CCN_UPCALL_RESULT_OK);
}

static enum ccn_upcall_res
r_proto_start_write(struct ccn_closure *selfp,
                    enum ccn_upcall_kind kind,
                    struct ccn_upcall_info *info)
{
    struct ccnr_handle *ccnr = NULL;
    struct ccn_charbuf *templ = NULL;
    struct ccn_closure *incoming = NULL;
    struct expect_content *expect_content = NULL;
    struct ccn_charbuf *reply_body = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_indexbuf *ic = NULL;
    enum ccn_upcall_res ans = CCN_UPCALL_RESULT_OK;
    struct ccn_charbuf *msg = NULL;
    int res = 0;
    int start = 0;
    int end = 0;
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    
    // XXX - Check for valid nonce
    // XXX - Check for pubid - if present and not ours, do not respond.
    // Check for answer origin kind.
    // If Exclude is there, there might be something fishy going on.
    
    ccnr = (struct ccnr_handle *)selfp->data;
    
    incoming = calloc(1, sizeof(*incoming));
    incoming->p = &r_proto_expect_content;
    expect_content = calloc(1, sizeof(*expect_content));
    expect_content->ccnr = ccnr;
    expect_content->done = 0;
    incoming->data = expect_content;
    /* Send an interest for segment 0 */
    templ = make_template(expect_content, NULL);
    name = ccn_charbuf_create();
    ccn_name_init(name);
    ic = info->interest_comps;
    if (ic->n < 3) abort();
    ccn_name_append_components(name, info->interest_ccnb, ic->buf[0], ic->buf[ic->n - 3]);
    ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, 0);
    res = ccn_express_interest(info->h, name, incoming, templ);
    if (res < 0) {
        ans = CCN_UPCALL_RESULT_ERR;
        goto Bail;
    }
    /* Generate our reply */
    msg = ccn_charbuf_create();
    reply_body = ccn_charbuf_create();
    r_proto_append_repo_info(ccnr, reply_body);
    start = info->pi->offset[CCN_PI_B_Name];
    end = info->interest_comps->buf[info->pi->prefix_comps];
    name->length = 0;
    ccn_charbuf_append(name, info->interest_ccnb + start, end - start);
    ccn_charbuf_append_closer(name);
    res = ccn_sign_content(info->h, msg, name, &sp,
                           reply_body->buf, reply_body->length);
    if (res < 0)
        goto Bail;
    if ((ccnr->debug & 128) != 0)
        ccnr_debug_ccnb(ccnr, __LINE__, "r_proto_start_write", NULL,
                        msg->buf, msg->length);
    res = ccn_put(info->h, msg->buf, msg->length);
    if (res < 0) {
        // note the error somehow.
    }
    
Bail:
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&reply_body);
    ccn_charbuf_destroy(&msg);
    return(ans);
}

static enum ccn_upcall_res
r_proto_start_write_checked(struct ccn_closure *selfp,
                    enum ccn_upcall_kind kind,
                    struct ccn_upcall_info *info)
{
    struct ccnr_handle *ccnr = NULL;
    enum ccn_upcall_res ans = CCN_UPCALL_RESULT_OK;

    return(ans);
}

static enum ccn_upcall_res
r_proto_begin_enumeration(struct ccn_closure *selfp,
                          enum ccn_upcall_kind kind,
                          struct ccn_upcall_info *info)
{
    struct ccnr_handle *ccnr = NULL;
    enum ccn_upcall_res ans = CCN_UPCALL_RESULT_OK;
    
    // looks like struct content_entry * r_store_find_first_match_candidate(struct ccnr_handle *h,
     //                                  const unsigned char *interest_msg,
      //                                 const struct ccn_parsed_interest *pi)
    // do we need to check r_store_content_matches_interest_prefix ?
    // Then is it r_store_content_skiplist_next while it matches, or r_store_next_child_at_level ?
    // write it out using ccn_seqwriter calls?
    // How to capture state at time of begin-enumeration?  What should version be?
    
    return(ans);
}
