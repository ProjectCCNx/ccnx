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
#include <sync/SyncBase.h>
#include "ccnr_private.h"

#include "ccnr_proto.h"

#include "ccnr_dispatch.h"
#include "ccnr_forwarding.h"
#include "ccnr_io.h"
#include "ccnr_msg.h"
#include "ccnr_sendq.h"
#include "ccnr_store.h"
#include "ccnr_util.h"

#define REPO_SW "\301.R.sw"
#define REPO_SWC "\301.R.sw-c"
#define REPO_AF "\301.R.af"
#define NAME_BE "\301.E.be"

#define CCNR_MAX_RETRY 5
#define CCNR_PIPELINE 4

static enum ccn_upcall_res
r_proto_start_write(struct ccn_closure *selfp,
                    enum ccn_upcall_kind kind,
                    struct ccn_upcall_info *info,
                    int marker_comp);

static enum ccn_upcall_res
r_proto_start_write_checked(struct ccn_closure *selfp,
                            enum ccn_upcall_kind kind,
                            struct ccn_upcall_info *info,
                            int marker_comp);

static enum ccn_upcall_res
r_proto_begin_enumeration(struct ccn_closure *selfp,
                          enum ccn_upcall_kind kind,
                          struct ccn_upcall_info *info,
                          int marker_comp);

static enum ccn_upcall_res
r_proto_continue_enumeration(struct ccn_closure *selfp,
                             enum ccn_upcall_kind kind,
                             struct ccn_upcall_info *info,
                             int marker_comp);

static enum ccn_upcall_res
r_proto_bulk_import(struct ccn_closure *selfp,
                             enum ccn_upcall_kind kind,
                             struct ccn_upcall_info *info,
                             int marker_comp);

static int
name_comp_equal(const unsigned char *data,
                   const struct ccn_indexbuf *indexbuf,
                   unsigned int i, const void *val, size_t length);

static int
name_comp_equal_prefix(const unsigned char *data,
                    const struct ccn_indexbuf *indexbuf,
                    unsigned int i, const void *buf, size_t length);

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
    int marker_comp;
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
    /* commands will potentially generate new content, test if new content is ok */
    if ((info->pi->answerfrom & CCN_AOK_NEW) == 0) {
        goto Bail;
    }
    
    /* check for command markers */
    ncomps = info->interest_comps->n;
    if (((marker_comp = ncomps - 2) >= 0) &&
        0 == name_comp_equal(info->interest_ccnb, info->interest_comps, marker_comp, NAME_BE, strlen(NAME_BE))) {
        if ((ccnr->debug & 8) != 0)
            ccnr_debug_ccnb(ccnr, __LINE__, "name_enumeration", NULL,
                            info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        res = r_proto_begin_enumeration(selfp, kind, info, marker_comp);
        goto Finish;
    } else if (((marker_comp = ncomps - 3) >= 0) &&
               0 == name_comp_equal(info->interest_ccnb, info->interest_comps, marker_comp, NAME_BE, strlen(NAME_BE)) &&
               0 == name_comp_equal(info->interest_ccnb, info->interest_comps, marker_comp + 1, ccnr->ccnr_keyid->buf, ccnr->ccnr_keyid->length)) {
        if ((ccnr->debug & 8) != 0)
            ccnr_debug_ccnb(ccnr, __LINE__, "name_enumeration_repoid", NULL,
                            info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        res = r_proto_begin_enumeration(selfp, kind, info, marker_comp);
        goto Finish;
    } else if (((marker_comp = ncomps - 5) >= 0) &&
               0 == name_comp_equal(info->interest_ccnb, info->interest_comps, marker_comp, NAME_BE, strlen(NAME_BE)) &&
               0 == name_comp_equal(info->interest_ccnb, info->interest_comps, marker_comp + 1, ccnr->ccnr_keyid->buf, ccnr->ccnr_keyid->length)) {
        if ((ccnr->debug & 8) != 0)
            ccnr_debug_ccnb(ccnr, __LINE__, "name_enumeration_continuation",
                            NULL, info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        res = r_proto_continue_enumeration(selfp, kind, info, marker_comp);
        goto Finish;
    } else if (((marker_comp = ncomps - 3) > 0) &&
               0 == name_comp_equal(info->interest_ccnb, info->interest_comps, marker_comp, REPO_SW, strlen(REPO_SW))) {
        if ((ccnr->debug & 8) != 0)
            ccnr_debug_ccnb(ccnr, __LINE__, "repo_start_write", NULL,
                            info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        res = r_proto_start_write(selfp, kind, info, marker_comp);
        goto Finish;
    } else if (((marker_comp = ncomps - 5) > 0) &&
               0 == name_comp_equal(info->interest_ccnb, info->interest_comps, marker_comp, REPO_SWC, strlen(REPO_SWC))) {
        if ((ccnr->debug & 8) != 0)
            ccnr_debug_ccnb(ccnr, __LINE__, "repo_start_write_checked",
                            NULL, info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        res = r_proto_start_write_checked(selfp, kind, info, marker_comp);
        goto Finish;
    } else if (((marker_comp = 0) == 0) &&
               0 == name_comp_equal_prefix(info->interest_ccnb, info->interest_comps, marker_comp, REPO_AF, strlen(REPO_AF))) {
        if ((ccnr->debug & 8) != 0)
            ccnr_debug_ccnb(ccnr, __LINE__, "repo_bulk_import",
                            NULL, info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        res = r_proto_bulk_import(selfp, kind, info, marker_comp);
        goto Finish;
    }
    goto Bail;
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

// XXX these should probably be rationalized and added to ccn_name_util.c
/**
 * Compare a name component at index i to bytes in buf and return 0 if they are equal
 * length and equal value.
 */
static int
name_comp_equal(const unsigned char *data,
                   const struct ccn_indexbuf *indexbuf,
                   unsigned int i, const void *buf, size_t length)
{
    const unsigned char *comp_ptr;
    size_t comp_size;
    
    if (ccn_name_comp_get(data, indexbuf, i, &comp_ptr, &comp_size) != 0)
        return(1);
    if ((comp_size != length) ||
        memcmp(comp_ptr, buf, length) != 0)
        return(1);
    return(0);
}

/**
 * Compare a name component at index i to bytes in buf and return 0 if they are equal
 * in the first length bytes.  The name component must contain at least length bytes
 * for this comparison to return equality.
 */
static int
name_comp_equal_prefix(const unsigned char *data,
                   const struct ccn_indexbuf *indexbuf,
                   unsigned int i, const void *buf, size_t length)
{
    const unsigned char *comp_ptr;
    size_t comp_size;
    
    if (ccn_name_comp_get(data, indexbuf, i, &comp_ptr, &comp_size) != 0)
        return(1);
    if (comp_size < length ||
        memcmp(comp_ptr, buf, length) != 0)
        return(1);
    return(0);
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
r_proto_append_repo_info(struct ccnr_handle *ccnr,
                         struct ccn_charbuf *rinfo,
                         struct ccn_charbuf *names) {
    // XXX - this is hardwired at present - should come from .meta/* in repo dir
    int res;
    struct ccn_charbuf *name = ccn_charbuf_create();
    if (name == NULL) return (-1);
    res = ccnb_element_begin(rinfo, CCN_DTAG_RepositoryInfo);
    res |= ccnb_tagged_putf(rinfo, CCN_DTAG_Version, "%s", "1.1");
    res |= ccnb_tagged_putf(rinfo, CCN_DTAG_Type, "%s", (names != NULL) ? "DATA" : "INFO");
    res |= ccnb_tagged_putf(rinfo, CCN_DTAG_RepositoryVersion, "%s", "2.0");

    res |= ccnb_element_begin(name, CCN_DTAG_GlobalPrefixName); // same structure as Name
    res |= ccnb_element_end(name);
    res |= ccn_name_append_str(name, "parc.com");
    res |= ccn_name_append_str(name, "csl");
    res |= ccn_name_append_str(name, "ccn");
    res |= ccn_name_append_str(name, "Repos");
    res |= ccn_charbuf_append_charbuf(rinfo, name);
    res |= ccnb_tagged_putf(rinfo, CCN_DTAG_LocalName, "%s", "Repository");
    if (names != NULL) {
        res |= ccn_charbuf_append_charbuf(rinfo, names);
    }
    // There is an optional CCN_DTAG_InfoString in the encoding here, like the LocalName
    res |= ccnb_element_end(rinfo); // CCN_DTAG_RepositoryInfo
    ccn_charbuf_destroy(&name);
    return (res);
}
struct expect_content {
    struct ccnr_handle *ccnr;
    int tries; /** counter so we can give up eventually */
    int done;
    intmax_t outstanding[CCNR_PIPELINE];
    intmax_t final;
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
static intmax_t
segment_from_component(const unsigned char *ccnb, size_t start, size_t stop)
{
    const unsigned char *data = NULL;
    size_t len = 0;
    intmax_t segment;
    int i;

    if (start < stop) {
		ccn_ref_tagged_BLOB(CCN_DTAG_Component, ccnb, start, stop, &data, &len);
		if (len > 0 && data != NULL) {
			// parse big-endian encoded number
			segment = 0;
            for (i = 0; i < len; i++) {
				segment = segment * 256 + data[i];
			}
			return(segment);
		}
	}
	return(-1);
    
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
    int i;
    int empty_slots;
    intmax_t segment;

    if (kind == CCN_UPCALL_FINAL) {
        if (md != NULL) {
            selfp->data = NULL;
            free(md);
            md = NULL;
        }
        return(CCN_UPCALL_RESULT_OK);
    }
    if (md == NULL) {
        return(CCN_UPCALL_RESULT_ERR);
    }
    if (md->done)
        return(CCN_UPCALL_RESULT_ERR);
    ccnr = (struct ccnr_handle *)md->ccnr;
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT) {
        if (md->tries > CCNR_MAX_RETRY) {
            ccnr_msg(ccnr, "r_proto_expect_content: retry count exceeded");
            return(CCN_UPCALL_RESULT_ERR);
        }
        md->tries++;
        return(CCN_UPCALL_RESULT_REEXPRESS);
    }
    if (kind == CCN_UPCALL_CONTENT_UNVERIFIED)
        return(CCN_UPCALL_RESULT_VERIFY);
    if (kind != CCN_UPCALL_CONTENT)
        return(CCN_UPCALL_RESULT_ERR);
    
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    ib = info->interest_ccnb;
    ic = info->interest_comps;
    
    content = process_incoming_content(ccnr, r_io_fdholder_from_fd(ccnr, ccn_get_connection_fd(info->h)), (void *)ccnb, ccnb_size);
    if (content == NULL) {
        ccnr_msg(ccnr, "r_proto_expect_content: failed to process incoming content");
        return(CCN_UPCALL_RESULT_ERR);
    }
    // XXX - here we need to check if this is something we *should* be storing, according to our policy
    if ((content->flags & CCN_CONTENT_ENTRY_STABLE) == 0) {
        // Need to actually append to the active repo data file
        r_sendq_face_send_queue_insert(ccnr, r_io_fdholder_from_fd(ccnr, ccnr->active_out_fd), content);
        // XXX - it would be better to do this after the write succeeds
        content->flags |= CCN_CONTENT_ENTRY_STABLE;
        if (content->accession >= ccnr->notify_after) {
            // XXX - ugh, content_entry doesn't have the data in exactly the format we want.  Rethink this?
            struct ccn_indexbuf *comps = r_util_indexbuf_obtain(ccnr);
            struct ccn_charbuf *cb = r_util_charbuf_obtain(ccnr);
            ccn_charbuf_append(cb, content->key, content->size);
            ccn_indexbuf_reserve(comps, content->ncomps);
            for (i = 0; i < content->ncomps; i++)
                ccn_indexbuf_append_element(comps, content->comps[i]);
            res = SyncNotifyContent(ccnr->sync_handle, 0, content->accession,
                                    cb, comps);
            r_util_indexbuf_release(ccnr, comps);
            r_util_charbuf_release(ccnr, cb);
        }
    }
    md->tries = 0;
    segment = segment_from_component(ib, ic->buf[ic->n - 2], ic->buf[ic->n - 1]);

    // XXX - need to save the keys (or do it when they arrive in the key snooper)
    // XXX The test below should get replace by ccn_is_final_block() when it is available
    if (is_final(info) == 1)
        md->final = segment;
    
    /* retire the current segment and any segments beyond the final one */
    empty_slots = 0;
    for (i = 0; i < CCNR_PIPELINE; i++) {
        if (md->outstanding[i] == segment || ((md->final > -1) && (md->outstanding[i] > md->final)))
            md->outstanding[i] = -1;
        if (md->outstanding[i] == -1)
            empty_slots++;
    
    }
    md->done = (md->final > -1) && (empty_slots == CCNR_PIPELINE);
    
    if (md->final > -1) {
        return (CCN_UPCALL_RESULT_OK);
    }

    name = ccn_charbuf_create();
    if (ic->n < 2) abort();    
    templ = make_template(md, info);
    /* fill the pipeline with new requests */
    for (i = 0; i < CCNR_PIPELINE; i++) {
        if (md->outstanding[i] == -1) {
            ccn_name_init(name);
            res = ccn_name_append_components(name, ib, ic->buf[0], ic->buf[ic->n - 2]);
            if (res < 0) abort();
            ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, ++(selfp->intdata));
            res = ccn_express_interest(info->h, name, selfp, templ);
            if (res < 0) abort();
            md->outstanding[i] = selfp->intdata;
        }
    }
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&name);
    
    return(CCN_UPCALL_RESULT_OK);
}

static enum ccn_upcall_res
r_proto_start_write(struct ccn_closure *selfp,
                    enum ccn_upcall_kind kind,
                    struct ccn_upcall_info *info,
                    int marker_comp)
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
    int i;
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    
    // XXX - Check for valid nonce
    // XXX - Check for pubid - if present and not ours, do not respond.
    // Check for answer origin kind.
    // If Exclude is there, there might be something fishy going on.
    
    ccnr = (struct ccnr_handle *)selfp->data;
    name = ccn_charbuf_create();
    
    /* Generate our reply */
    msg = ccn_charbuf_create();
    reply_body = ccn_charbuf_create();
    r_proto_append_repo_info(ccnr, reply_body, NULL);
    start = info->pi->offset[CCN_PI_B_Name];
    end = info->interest_comps->buf[info->pi->prefix_comps];
    ccn_charbuf_append(name, info->interest_ccnb + start, end - start);
    ccn_charbuf_append_closer(name);
    res = ccn_sign_content(info->h, msg, name, &sp,
                           reply_body->buf, reply_body->length);
    if (res < 0)
        goto Bail;
    if ((ccnr->debug & 128) != 0)
        ccnr_debug_ccnb(ccnr, __LINE__, "r_proto_start_write response", NULL,
                        msg->buf, msg->length);
    res = ccn_put(info->h, msg->buf, msg->length);
    if (res < 0) {
        // note the error somehow.
    }

    /* Send an interest for segment 0 */
    incoming = calloc(1, sizeof(*incoming));
    incoming->p = &r_proto_expect_content;
    expect_content = calloc(1, sizeof(*expect_content));
    expect_content->ccnr = ccnr;
    expect_content->final = -1;
    for (i = 0; i < CCNR_PIPELINE; i++)
        expect_content->outstanding[i] = -1;
    incoming->data = expect_content;
    templ = make_template(expect_content, NULL);
    ic = info->interest_comps;
    ccn_name_init(name);
    ccn_name_append_components(name, info->interest_ccnb, ic->buf[0], ic->buf[marker_comp]);
    ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, 0);
    expect_content->outstanding[0] = 0;
    res = ccn_express_interest(info->h, name, incoming, templ);
    if (res < 0) {
        ans = CCN_UPCALL_RESULT_ERR;
        goto Bail;
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
                            struct ccn_upcall_info *info,
                            int marker_comp)
{
    struct ccnr_handle *ccnr = NULL;
    enum ccn_upcall_res ans = CCN_UPCALL_RESULT_OK;
    struct ccn_indexbuf *ic = NULL;
    struct content_entry *content = NULL;
    struct ccn_parsed_interest parsed_interest = {0};
    struct ccn_parsed_interest *pi = &parsed_interest;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *interest = NULL;
    struct ccn_indexbuf *comps = NULL;
    struct ccn_charbuf *msg = NULL;
    struct ccn_charbuf *reply_body = NULL;
    int start = 0;
    int end = 0;
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    int res = 0;
    
    ccnr = (struct ccnr_handle *)selfp->data;
    name = ccn_charbuf_create();
    ccn_name_init(name);
    ic = info->interest_comps;
    ccn_name_append_components(name, info->interest_ccnb, ic->buf[0], ic->buf[marker_comp]);
    ccn_name_append_components(name, info->interest_ccnb, ic->buf[marker_comp + 2], ic->buf[ic->n - 1]);
    // Make an interest for the exact item we're checking
    interest = ccn_charbuf_create();
    ccnb_element_begin(interest, CCN_DTAG_Interest);
    ccn_charbuf_append_charbuf(interest, name);
    ccnb_element_end(interest); /* </Interest> */
    // Parse it
    comps = ccn_indexbuf_create();
    res = ccn_parse_interest(interest->buf, interest->length, pi, comps);
    if (res < 0)
        abort();
    ccnr_debug_ccnb(ccnr, __LINE__, "r_proto_checked_start_write looking for", NULL,
                    interest->buf, interest->length);
    content = r_store_lookup(ccnr, interest->buf, pi, comps);
    if (content == NULL) {
        ccnr_msg(ccnr, "r_proto_start_write_checked: NOT PRESENT");
        return(r_proto_start_write(selfp, kind, info, marker_comp));
    }
    // what's the return value if the item is in the repository already?
    // if it does have it -- getRepoInfo(interest.name(), null, target_names)
    // response has local name as the full name of the thing we claim to have --
    // take the command marker and nonce out of the middle of the incoming interest,
    // which is what we have in the "name" of the interest we created to check the content.
    ///// begin copied code
    /* Generate our reply */
    msg = ccn_charbuf_create();
    reply_body = ccn_charbuf_create();
    r_proto_append_repo_info(ccnr, reply_body, name);
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
        ccnr_debug_ccnb(ccnr, __LINE__, "r_proto_checked_start_write PRESENT", NULL,
                        msg->buf, msg->length);
    res = ccn_put(info->h, msg->buf, msg->length);
    if (res < 0) {
        // note the error somehow.
    }
    //// end of copied code
Bail:
    return(ans);
}

/**
 * Returns 1 if the Exclude in the interest described by the info parameter
 * would exclude the full name in name.
 */
static int
r_proto_check_exclude(struct ccnr_handle *ccnr,
                      struct ccn_upcall_info *info,
                      struct ccn_charbuf *name)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = NULL;
    const unsigned char *comp = NULL;
    size_t comp_size;
    size_t name_comp_size;
    struct ccn_indexbuf *name_comps = NULL;
    const unsigned char *name_string = NULL;
    int nc;
    int ci;
    int res;
    int ans = 0;
    
    if (info->pi->offset[CCN_PI_B_Exclude] < info->pi->offset[CCN_PI_E_Exclude]) {
        d = ccn_buf_decoder_start(&decoder,
                                  info->interest_ccnb + info->pi->offset[CCN_PI_B_Exclude],
                                  info->pi->offset[CCN_PI_E_Exclude] -
                                  info->pi->offset[CCN_PI_B_Exclude]);
        
        // handle easy case of <Exclude><Component>...</Exclude>
        // XXX - this may need to be better, but not necessarily complete
        if (ccn_buf_match_dtag(d, CCN_DTAG_Exclude)) {
            ccn_buf_advance(d);
        } else 
            goto Bail;
        // there may be something to check, so get the components of the name
        name_comps = ccn_indexbuf_create();
        nc = ccn_name_split(name, name_comps);
        // the component in the name we are matching is last plus one of the interest
        // but ci includes an extra value for the end of the last component
        ci = info->interest_comps->n;
        res = ccn_name_comp_get(name->buf, name_comps, ci - 1, &name_string, &name_comp_size);
        if (res < 0)
            goto Bail;
        while (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
            ccn_buf_advance(d);
            comp_size = 0;
            if (ccn_buf_match_blob(d, &comp, &comp_size))
                ccn_buf_advance(d);
            ccn_buf_check_close(d);
            if (comp_size == name_comp_size) {
                res = memcmp(comp, name_string, comp_size);
                if (res == 0) {
                    ans = 1;
                    goto Bail; /* One of the explicit excludes */
                }
                if (res > 0)
                    break;
            }
        }
    }
    
Bail:
    ccn_indexbuf_destroy(&name_comps);
    ccnr_msg(ccnr, "r_proto_check_exclude: do %s exclude", (ans == 1) ? "" : "not");
    return(ans);
}

static enum ccn_upcall_res
r_proto_begin_enumeration(struct ccn_closure *selfp,
                          enum ccn_upcall_kind kind,
                          struct ccn_upcall_info *info,
                          int marker_comp)
{
    struct ccnr_handle *ccnr = NULL;
    enum ccn_upcall_res ans = CCN_UPCALL_RESULT_ERR;
    struct ccn_parsed_interest parsed_interest = {0};
    struct ccn_parsed_interest *pi = &parsed_interest;
    struct hashtb_enumerator enumerator = {0};
    struct hashtb_enumerator *e = &enumerator;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *interest = NULL;
    struct ccn_indexbuf *comps = NULL;
    int res;
    struct content_entry *content = NULL;
    struct enum_state *es = NULL;
    
    ccnr = (struct ccnr_handle *)selfp->data;
    // looks like struct content_entry * r_store_find_first_match_candidate(struct ccnr_handle *h,
    //                                  const unsigned char *interest_msg,
    //                                 const struct ccn_parsed_interest *pi)
    // we need to check r_store_content_matches_interest_prefix
    // Then it is r_store_next_child_at_level
    // write it out using ccn_seqwriter calls, saving state
    // be_trim == 2 indicates that we found %C1.E.be/%C1.M.K%00<our id>
    // if we get a non-specific request it may have an exclude of our repo id, so we shouldn't reply
    // if we get a specific request it may have an exclude of the version(s) we previously generated
    
    // Construct a name up to but not including the begin enumeration marker component
    name = ccn_charbuf_create();
    ccn_name_init(name);
    ccn_name_append_components(name, info->interest_ccnb,
                               info->interest_comps->buf[0],
                               info->interest_comps->buf[marker_comp]);
    // Make an interest for the part of the namespace we are after, from the name
    interest = ccn_charbuf_create();
    ccnb_element_begin(interest, CCN_DTAG_Interest);
    ccn_charbuf_append_charbuf(interest, name);
    ccnb_element_end(interest); /* </Interest> */
    
    // Parse it
    comps = ccn_indexbuf_create();
    res = ccn_parse_interest(interest->buf, interest->length, pi, comps);
    if (res < 0)
        abort();
    // do we have anything that matches this enumeration request? If not, ignore the request
    content = r_store_find_first_match_candidate(ccnr, interest->buf, pi);
    if (content == NULL)
        goto Bail;
    if (!r_store_content_matches_interest_prefix(ccnr, content, interest->buf, comps, comps->n - 1))
        goto Bail;
    // Look for a previous enumeration under this prefix
    ccnr_debug_ccnb(ccnr, __LINE__, "begin enum: hash name", NULL,
                    name->buf, name->length);
    hashtb_start(ccnr->enum_state_tab, e);
    res = hashtb_seek(e, name->buf, name->length, 0);
    es = e->data;
    // Do not restart an active enumeration, it is probably a duplicate interest
    if (es->active == 1)
        goto Bail;
    // Continue to construct the name under which we will respond: %C1.E.be
    ccn_name_append_components(name, info->interest_ccnb,
                               info->interest_comps->buf[marker_comp],
                               info->interest_comps->buf[marker_comp + 1]);
    // Append the repository key id %C1.K.%00<repoid>
    ccn_name_append(name, ccnr->ccnr_keyid->buf, ccnr->ccnr_keyid->length);
    
    if (res == HT_NEW_ENTRY || es->starting_accession != ccnr->accession) {
        // this is a new enumeration, the time is now.
        ccnr_msg(ccnr, "making entry with new version");
        res = ccn_create_version(info->h, name, CCN_V_NOW, 0, 0);
        if (es->name != NULL)
            ccn_charbuf_destroy(&es->name);
        es->name = ccn_charbuf_create();
        ccn_charbuf_append_charbuf(es->name, name);
        es->starting_accession = ccnr->accession; // XXX - a conservative indicator of change
    }
    
    // check the exclude
    ccnr_debug_ccnb(ccnr, __LINE__, "begin enum: interest", NULL,
                    info->interest_ccnb, info->pi->offset[CCN_PI_E]);
    ccnr_debug_ccnb(ccnr, __LINE__, "begin enum: result name", NULL,
                    es->name->buf, es->name->length);
    
    if (r_proto_check_exclude(ccnr, info, es->name) > 0)
        goto Bail;
    
    es->reply_body = ccn_charbuf_create();
    ccnb_element_begin(es->reply_body, CCN_DTAG_Collection);
    es->w = ccn_seqw_create(info->h, es->name);
    es->content = content;
    es->interest = interest;
    es->interest_comps = comps;
    es->next_segment = 0;
    es->active = 1;
    
    hashtb_end(e);
    ans = r_proto_continue_enumeration(selfp, kind, info, marker_comp);
    return(ans);
    
Bail:
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&interest);
    ccn_indexbuf_destroy(&comps);
    return(ans);
}

static enum ccn_upcall_res
r_proto_continue_enumeration(struct ccn_closure *selfp,
                             enum ccn_upcall_kind kind,
                             struct ccn_upcall_info *info,
                             int marker_comp) {
    // XXX - watch out for pipelined interests for the enumerations -- there
    // MUST be an active enumeration continuation before we do anything here.
    // Should chop 1 component off interest -- which will look like
    // ccnx:/.../%C1.E.be/%C1.M.K%00.../%FD.../%00%02
    struct ccn_charbuf *hashkey = NULL;
    struct enum_state *es = NULL;
    struct ccnr_handle *ccnr = NULL;
    struct hashtb_enumerator enumerator = {0};
    struct hashtb_enumerator *e = &enumerator;
    int res = 0;
    int ans = CCN_UPCALL_RESULT_ERR;

    ccnr = (struct ccnr_handle *)selfp->data;
    hashkey=ccn_charbuf_create();
    ccn_name_init(hashkey);
    ccn_name_append_components(hashkey, info->interest_ccnb,
                               info->interest_comps->buf[0],
                               info->interest_comps->buf[marker_comp]);
    hashtb_start(ccnr->enum_state_tab, e);
    res = hashtb_seek(e, hashkey->buf, hashkey->length, 0);
    ccn_charbuf_destroy(&hashkey);
    if (res != HT_OLD_ENTRY) {
        goto Bail;
    }
    es = e->data;
    if (es->active == 0) {
        hashtb_end(e);
        return(ans);
    }
    
    ccnr_msg(ccnr, "processing an enumeration continuation for segment %jd", es->next_segment);
    while (es->content != NULL && r_store_content_matches_interest_prefix(ccnr, es->content, es->interest->buf, es->interest_comps, es->interest_comps->n - 1)) {
        ccnb_element_begin(es->reply_body, CCN_DTAG_Link);
        ccnb_element_begin(es->reply_body, CCN_DTAG_Name);
        ccnb_element_end(es->reply_body);
        ccn_name_append_components(es->reply_body, es->content->key,
                                   es->content->comps[es->interest_comps->n - 1],
                                   es->content->comps[es->interest_comps->n]);
        ccnb_element_end(es->reply_body); /* </Link> */
        es->content = r_store_next_child_at_level(ccnr, es->content, es->interest_comps->n - 1);
        if (es->reply_body->length >= 4096) {
            res = ccn_seqw_write(es->w, es->reply_body->buf, 4096);
            if (res <= 0) {
                abort();
            }
            es->next_segment++;
            memmove(es->reply_body->buf, es->reply_body->buf + res, es->reply_body->length - res);
            es->reply_body->length -= res;
            hashtb_end(e);
            return (CCN_UPCALL_RESULT_OK);
        }
    }
    // we will only get here if we are finishing an in-progress enumeration
    ccnb_element_end(es->reply_body); /* </Collection> */
    res = ccn_seqw_write(es->w, es->reply_body->buf, es->reply_body->length);
    if (res != es->reply_body->length) {
        abort();
    }
    es->active = 0;
    ans = CCN_UPCALL_RESULT_OK;
    
Bail:
    if (es != NULL) {
        // leave the name 
        ccn_charbuf_destroy(&es->interest);
        ccn_charbuf_destroy(&es->reply_body);
        ccn_indexbuf_destroy(&es->interest_comps);
        res = ccn_seqw_close(es->w);
        if (res < 0) abort();
        es->w = NULL;
    }
    hashtb_end(e);
    return(ans);
}

static enum ccn_upcall_res
r_proto_bulk_import(struct ccn_closure *selfp,
                          enum ccn_upcall_kind kind,
                          struct ccn_upcall_info *info,
                          int marker_comp)
{
    return(CCN_UPCALL_RESULT_ERR);
}