/**
 * @file ccnr_proto.c
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
 
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <sys/errno.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/ccn_private.h>
#include <ccn/hashtb.h>
#include <ccn/schedule.h>
#include <ccn/sockaddrutil.h>
#include <ccn/uri.h>
#include <ccn/coding.h>
#include <sync/SyncBase.h>
#include "ccnr_private.h"

#include "ccnr_proto.h"

#include "ccnr_dispatch.h"
#include "ccnr_forwarding.h"
#include "ccnr_init.h"
#include "ccnr_io.h"
#include "ccnr_msg.h"
#include "ccnr_sendq.h"
#include "ccnr_store.h"
#include "ccnr_sync.h"
#include "ccnr_util.h"

#define CCNR_MAX_RETRY 5

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
    if (CCNSHOULDLOG(ccnr, LM_128, CCNL_FINE))
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
        0 == r_util_name_comp_compare(info->interest_ccnb, info->interest_comps, marker_comp, NAME_BE, strlen(NAME_BE))) {
        if (CCNSHOULDLOG(ccnr, LM_8, CCNL_FINER))
            ccnr_debug_ccnb(ccnr, __LINE__, "name_enumeration", NULL,
                            info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        res = r_proto_begin_enumeration(selfp, kind, info, marker_comp);
        goto Finish;
    } else if (((marker_comp = ncomps - 3) >= 0) &&
               0 == r_util_name_comp_compare(info->interest_ccnb, info->interest_comps, marker_comp, NAME_BE, strlen(NAME_BE)) &&
               0 == r_util_name_comp_compare(info->interest_ccnb, info->interest_comps, marker_comp + 1, ccnr->ccnr_keyid->buf, ccnr->ccnr_keyid->length)) {
        if (CCNSHOULDLOG(ccnr, LM_8, CCNL_FINER))
            ccnr_debug_ccnb(ccnr, __LINE__, "name_enumeration_repoid", NULL,
                            info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        res = r_proto_begin_enumeration(selfp, kind, info, marker_comp);
        goto Finish;
    } else if (((marker_comp = ncomps - 5) >= 0) &&
               0 == r_util_name_comp_compare(info->interest_ccnb, info->interest_comps, marker_comp, NAME_BE, strlen(NAME_BE)) &&
               0 == r_util_name_comp_compare(info->interest_ccnb, info->interest_comps, marker_comp + 1, ccnr->ccnr_keyid->buf, ccnr->ccnr_keyid->length)) {
        if (CCNSHOULDLOG(ccnr, LM_8, CCNL_FINER))
            ccnr_debug_ccnb(ccnr, __LINE__, "name_enumeration_continuation",
                            NULL, info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        res = r_proto_continue_enumeration(selfp, kind, info, marker_comp);
        goto Finish;
    } else if (((marker_comp = ncomps - 3) > 0) &&
               0 == r_util_name_comp_compare(info->interest_ccnb, info->interest_comps, marker_comp, REPO_SW, strlen(REPO_SW))) {
        if (CCNSHOULDLOG(ccnr, LM_8, CCNL_FINER))
            ccnr_debug_ccnb(ccnr, __LINE__, "repo_start_write", NULL,
                            info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        res = r_proto_start_write(selfp, kind, info, marker_comp);
        goto Finish;
    } else if (((marker_comp = ncomps - 5) > 0) &&
               0 == r_util_name_comp_compare(info->interest_ccnb, info->interest_comps, marker_comp, REPO_SWC, strlen(REPO_SWC))) {
        if (CCNSHOULDLOG(ccnr, LM_8, CCNL_FINER))
            ccnr_debug_ccnb(ccnr, __LINE__, "repo_start_write_checked",
                            NULL, info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        res = r_proto_start_write_checked(selfp, kind, info, marker_comp);
        goto Finish;
    } else if (((marker_comp = 0) == 0) &&
               name_comp_equal_prefix(info->interest_ccnb, info->interest_comps, marker_comp, REPO_AF, strlen(REPO_AF))) {
        if (CCNSHOULDLOG(ccnr, LM_8, CCNL_FINER))
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
 * Compare a name component at index i to bytes in buf and return 1
 * if they are equal in the first length bytes.  The name component
 * must contain at least length bytes for this comparison to return
 * equality.
 * @returns 1 for equality, 0 for inequality.
 */
static int
name_comp_equal_prefix(const unsigned char *data,
                   const struct ccn_indexbuf *indexbuf,
                   unsigned int i, const void *buf, size_t length)
{
    const unsigned char *comp_ptr;
    size_t comp_size;
    
    if (ccn_name_comp_get(data, indexbuf, i, &comp_ptr, &comp_size) != 0)
        return(0);
    if (comp_size < length || memcmp(comp_ptr, buf, length) != 0)
        return(0);
    return(1);
}

PUBLIC void
r_proto_uri_listen(struct ccnr_handle *ccnr, struct ccn *ccn, const char *uri,
                   ccn_handler p, intptr_t intdata)
{
    struct ccn_charbuf *name;
    struct ccn_closure *closure = NULL;
    
    name = ccn_charbuf_create();
    ccn_name_from_uri(name, uri);
    if (p != NULL) {
        closure = calloc(1, sizeof(*closure));
        closure->p = p;
        closure->data = ccnr;
        closure->intdata = intdata;
    }
    ccn_set_interest_filter(ccn, name, closure);
    ccn_charbuf_destroy(&name);
}

// XXX - need an r_proto_uninit to uninstall the policy
PUBLIC void
r_proto_init(struct ccnr_handle *ccnr) {
    // nothing to do
}
/**
 * Install the listener for the namespaces that the parsed policy says to serve
 * 
 * Normal usage is to deactivate the old policy and then activate the new one
 */
PUBLIC void
r_proto_activate_policy(struct ccnr_handle *ccnr, struct ccnr_parsed_policy *pp) {
    int i;
    
    for (i = 0; i < pp->namespaces->n; i++) {
        if (CCNSHOULDLOG(ccnr, sdfdf, CCNL_INFO))
            ccnr_msg(ccnr, "Adding listener for policy namespace %s",
                     (char *)pp->store->buf + pp->namespaces->buf[i]);
        r_proto_uri_listen(ccnr, ccnr->direct_client,
                           (char *)pp->store->buf + pp->namespaces->buf[i],
                           r_proto_answer_req, 0);
    }
    if (CCNSHOULDLOG(ccnr, sdfdf, CCNL_INFO))
        ccnr_msg(ccnr, "Adding listener for policy global prefix %s",
                 (char *)pp->store->buf + pp->global_prefix_offset);
    r_proto_uri_listen(ccnr, ccnr->direct_client,
                       (char *)pp->store->buf + pp->global_prefix_offset,
                       r_proto_answer_req, 0);    
}
/**
 * Uninstall the listener for the namespaces that the parsed policy says to serve
 */
PUBLIC void
r_proto_deactivate_policy(struct ccnr_handle *ccnr, struct ccnr_parsed_policy *pp) {
    int i;

    if (CCNSHOULDLOG(ccnr, sdfdf, CCNL_INFO))
        ccnr_msg(ccnr, "Removing listener for policy global prefix %s",
                 (char *)pp->store->buf + pp->global_prefix_offset);
    r_proto_uri_listen(ccnr, ccnr->direct_client,
                       (char *)pp->store->buf + pp->global_prefix_offset,
                       NULL, 0);    
    for (i = 0; i < pp->namespaces->n; i++) {
        if (CCNSHOULDLOG(ccnr, sdfdf, CCNL_INFO))
            ccnr_msg(ccnr, "Removing listener for policy namespace %s",
                     (char *)pp->store->buf + pp->namespaces->buf[i]);
        r_proto_uri_listen(ccnr, ccnr->direct_client,
                           (char *)pp->store->buf + pp->namespaces->buf[i],
                           NULL, 0);
    }
    
}


/**
 * Construct a charbuf with an encoding of a RepositoryInfo
 */ 
PUBLIC int
r_proto_append_repo_info(struct ccnr_handle *ccnr,
                         struct ccn_charbuf *rinfo,
                         struct ccn_charbuf *names,
                         const char *info) {
    int res;
    struct ccn_charbuf *name = ccn_charbuf_create();
    if (name == NULL) return (-1);
    res = ccnb_element_begin(rinfo, CCN_DTAG_RepositoryInfo);
    res |= ccnb_tagged_putf(rinfo, CCN_DTAG_Version, "%s", "1.1");
    res |= ccnb_tagged_putf(rinfo, CCN_DTAG_Type, "%s", (names != NULL) ? "DATA" : "INFO");
    res |= ccnb_tagged_putf(rinfo, CCN_DTAG_RepositoryVersion, "%s", "2.0");
    res |= ccnb_element_begin(rinfo, CCN_DTAG_GlobalPrefixName); // same structure as Name
    res |= ccnb_element_end(rinfo);
    ccn_name_init(name);
    res |= ccn_name_from_uri(name, (char *)ccnr->parsed_policy->store->buf + ccnr->parsed_policy->global_prefix_offset);
    res |= ccn_name_append_components(rinfo, name->buf, 1, name->length - 1);
    res |= ccnb_tagged_putf(rinfo, CCN_DTAG_LocalName, "%s", "Repository");
    if (names != NULL)
        res |= ccn_charbuf_append_charbuf(rinfo, names);
    if (info != NULL)
        res |= ccnb_tagged_putf(rinfo, CCN_DTAG_InfoString, "%s", info);
    // There is an optional CCN_DTAG_InfoString in the encoding here, like the LocalName
    res |= ccnb_element_end(rinfo); // CCN_DTAG_RepositoryInfo
    ccn_charbuf_destroy(&name);
    return (res);
}

static struct ccn_charbuf *
r_proto_mktemplate(struct ccnr_expect_content *md, struct ccn_upcall_info *info,
                   int maxsuffix)
{
    struct ccn_charbuf *templ = ccn_charbuf_create();
    ccnb_element_begin(templ, CCN_DTAG_Interest); // same structure as Name
    ccnb_element_begin(templ, CCN_DTAG_Name);
    ccnb_element_end(templ); /* </Name> */
    // XXX - use pubid if possible
    // XXX - if start-write was scoped, use scope here?
    ccnb_tagged_putf(templ, CCN_DTAG_MaxSuffixComponents, "%d", maxsuffix);
    ccnb_element_end(templ); /* </Interest> */
    return(templ);
}

PUBLIC enum ccn_upcall_res
r_proto_expect_content(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    struct ccn_indexbuf *cc = NULL;
    int res;
    struct ccnr_expect_content *md = selfp->data;
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
        free(selfp);
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
            ccnr_debug_ccnb(ccnr, __LINE__, "fetch_failed", NULL,
                            info->interest_ccnb, info->pi->offset[CCN_PI_E]);
            return(CCN_UPCALL_RESULT_ERR);
        }
        md->tries++;
        return(CCN_UPCALL_RESULT_REEXPRESS);
    }
    if (kind == CCN_UPCALL_CONTENT_UNVERIFIED) {
        // XXX - Some forms of key locator can confuse libccn. Don't provoke it to fetch keys until that is hardened.
        if (CCNSHOULDLOG(ccnr, sdfdf, CCNL_FINE))
            ccnr_debug_ccnb(ccnr, __LINE__, "key_needed", NULL, info->content_ccnb, info->pco->offset[CCN_PCO_E]);
    }
    switch (kind) {
        case CCN_UPCALL_CONTENT:
        case CCN_UPCALL_CONTENT_UNVERIFIED:
#if (CCN_API_VERSION >= 4004)
        case CCN_UPCALL_CONTENT_RAW:
        case CCN_UPCALL_CONTENT_KEYMISSING:
#endif
            break;
        default:
            return(CCN_UPCALL_RESULT_ERR);
    }
    
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    cc = info->content_comps;
    
    content = process_incoming_content(ccnr, r_io_fdholder_from_fd(ccnr, ccn_get_connection_fd(info->h)),
                                       (void *)ccnb, ccnb_size, NULL);
    if (content == NULL) {
        ccnr_msg(ccnr, "r_proto_expect_content: failed to process incoming content");
        return(CCN_UPCALL_RESULT_ERR);
    }
    r_store_commit_content(ccnr, content);
    r_proto_initiate_key_fetch(ccnr, ccnb, info->pco, 0,
                               r_store_content_cookie(ccnr, content));
    
    md->tries = 0;
    segment = r_util_segment_from_component(ccnb, cc->buf[cc->n - 2], cc->buf[cc->n - 1]);

    if (ccn_is_final_block(info) == 1)
        md->final = segment;
    
    if (md->keyfetch != 0 && segment <= 0) {
        /* This should either be a key, or a link to get to it. */
        if (info->pco->type == CCN_CONTENT_LINK) {
            r_proto_initiate_key_fetch(ccnr, ccnb, info->pco, 1, md->keyfetch);
        }
        else if (info->pco->type == CCN_CONTENT_KEY) {
            if (CCNSHOULDLOG(ccnr, sdfdf, CCNL_FINE))
                ccnr_msg(ccnr, "key_arrived %u", (unsigned)(md->keyfetch));
            // XXX - should check that we got the right key.
        }
        else {
            // not a key or a link.  Log it so we have a clue.
            ccnr_msg(ccnr, "ERROR - got something else when trying to fetch key for item %u", (unsigned)(md->keyfetch));
        }
    }
    
    // Unsegmented content should skip pipeline processing.
    if (segment < 0) {
        if (md->expect_complete != NULL) {
            (md->expect_complete)(selfp, kind, info);
        }
        return(CCN_UPCALL_RESULT_OK);
    }
    
    /* retire the current segment and any segments beyond the final one */
    empty_slots = 0;
    for (i = 0; i < CCNR_PIPELINE; i++) {
        if (md->outstanding[i] == segment || ((md->final > -1) && (md->outstanding[i] > md->final)))
            md->outstanding[i] = -1;
        if (md->outstanding[i] == -1)
            empty_slots++;
    
    }
    md->done = (md->final > -1) && (empty_slots == CCNR_PIPELINE);
    // if there is a completion handler set up, and we've got all the blocks
    // call it -- note that this may not be the last block if they arrive out of order.
    if (md->done && (md->expect_complete != NULL))
        (md->expect_complete)(selfp, kind, info);
                              
    if (md->final > -1) {
        return (CCN_UPCALL_RESULT_OK);
    }

    name = ccn_charbuf_create();
    if (cc->n < 2) abort();    
    templ = r_proto_mktemplate(md, info, 1);
    /* fill the pipeline with new requests */
    for (i = 0; i < CCNR_PIPELINE; i++) {
        if (md->outstanding[i] == -1) {
            ccn_name_init(name);
            res = ccn_name_append_components(name, ccnb, cc->buf[0], cc->buf[cc->n - 2]);
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

static int
r_proto_policy_update(struct ccn_schedule *sched,
                      void *clienth,
                      struct ccn_scheduled_event *ev,
                      int flags)
{
    struct ccnr_handle *ccnr = clienth;
    struct ccn_charbuf *name = ev->evdata;
    struct content_entry *content = NULL;
    const unsigned char *content_msg = NULL;
    const unsigned char *vers = NULL;
    size_t vers_size = 0;
    struct ccn_parsed_ContentObject pco = {0};
    struct ccn_indexbuf *nc;
    struct ccn_charbuf *policy = NULL;
    struct ccn_charbuf *policy_link_cob = NULL;
    struct ccn_charbuf *policyFileName = NULL;
    const unsigned char *buf = NULL;
    size_t length = 0;
    struct ccnr_parsed_policy *pp;
    int segment = -1;
    int final = 0;
    int res;
    int ans = -1;
    int fd = -1;
    
    if ((flags & CCN_SCHEDULE_CANCEL) != 0) {
        ans = 0;
        goto Bail;
    }
    
    policy = ccn_charbuf_create();
    nc = ccn_indexbuf_create();
    do {
        ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, ++segment);
        content = r_store_lookup_ccnb(ccnr, name->buf, name->length);
        if (content == NULL) {
            ccnr_debug_ccnb(ccnr, __LINE__, "policy lookup failed for", NULL,
                            name->buf, name->length);
            goto Bail;
        }
        ccn_name_chop(name, NULL, -1);
        content_msg = r_store_content_base(ccnr, content);
        if (content_msg == NULL) {
            ccnr_debug_ccnb(ccnr, __LINE__, "Policy read failed for", NULL,
                            name->buf, name->length);
            goto Bail;            
        }
        res = ccn_parse_ContentObject(content_msg, r_store_content_size(ccnr, content), &pco, nc);
        res = ccn_ref_tagged_BLOB(CCN_DTAG_Content, content_msg,
                                  pco.offset[CCN_PCO_B_Content],
                                  pco.offset[CCN_PCO_E_Content],
                                  &buf, &length);
        ccn_charbuf_append(policy, buf, length);
        final = ccn_is_final_pco(content_msg, &pco, nc);
    } while (!final);
    
    pp = ccnr_parsed_policy_create();
    if (pp == NULL) {
        ccnr_msg(ccnr, "Parsed policy allocation error");
        goto Bail;
    }
    memmove(pp->version, vers, vers_size);
    if (r_proto_parse_policy(ccnr, policy->buf, policy->length, pp) < 0) {
        ccnr_msg(ccnr, "Malformed policy");
        goto Bail;
    }
    res = strcmp((char *)pp->store->buf + pp->global_prefix_offset,
                 (char *)ccnr->parsed_policy->store->buf + ccnr->parsed_policy->global_prefix_offset);
    if (0 != res) {
        ccnr_msg(ccnr, "Policy global prefix mismatch");
        goto Bail;
    }
    policy_link_cob = ccnr_init_policy_link_cob(ccnr, ccnr->direct_client, name);
    if (policy_link_cob != NULL) {
        ccn_charbuf_destroy(&ccnr->policy_link_cob);
        ccnr->policy_link_cob = policy_link_cob;
    }
    policyFileName = ccn_charbuf_create();
    ccn_charbuf_putf(policyFileName, "%s/repoPolicy", ccnr->directory);
    fd = open(ccn_charbuf_as_string(policyFileName), O_WRONLY | O_CREAT, 0666);
    if (fd < 0) {
        ccnr_msg(ccnr, "open policy: %s (errno = %d)", strerror(errno), errno);
        goto Bail;
    }
    lseek(fd, 0, SEEK_SET);
    res = write(fd, ccnr->policy_link_cob->buf, ccnr->policy_link_cob->length);
    if (res == -1) {
        ccnr_msg(ccnr, "write policy: %s (errno = %d)", strerror(errno), errno);
        goto Bail;
    }
    res = ftruncate(fd, ccnr->policy_link_cob->length);
    if (res == -1) {
        ccnr_msg(ccnr, "Policy truncate %u :%s (errno = %d)",
                 fd, strerror(errno), errno);
        goto Bail;
    }
    close(fd);
    fd = -1;
    r_proto_deactivate_policy(ccnr, ccnr->parsed_policy);
    ccnr_parsed_policy_destroy(&ccnr->parsed_policy);
    ccnr->parsed_policy = pp;
    r_proto_activate_policy(ccnr, pp);
    
    ans = 0;
    
Bail:
    ccn_charbuf_destroy(&name);
    ccn_indexbuf_destroy(&nc);
    ccn_charbuf_destroy(&policy);
    ccn_charbuf_destroy(&policyFileName);
    if (fd >= 0) close(fd);
    return (ans);
    
}    

static enum ccn_upcall_res
r_proto_policy_complete(struct ccn_closure *selfp,
                        enum ccn_upcall_kind kind,
                        struct ccn_upcall_info *info)
{
    struct ccnr_expect_content *md = selfp->data;
    struct ccnr_handle *ccnr = (struct ccnr_handle *)md->ccnr;
    const unsigned char *ccnb;
    size_t ccnb_size;
    const unsigned char *vers = NULL;
    size_t vers_size = 0;
    struct ccn_indexbuf *cc;
    struct ccn_charbuf *name;
    
    // the version of the new policy must be greater than the exist one
    // or we will not activate it and update the link to point to it.
    
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    cc = info->content_comps;
    ccn_name_comp_get(ccnb, cc, cc->n - 3, &vers, &vers_size);
    if (vers_size != 7 || vers[0] != CCN_MARKER_VERSION)
        return(CCN_UPCALL_RESULT_ERR);
    if (memcmp(vers, ccnr->parsed_policy->version, sizeof(ccnr->parsed_policy->version)) <= 0) {
        if (CCNSHOULDLOG(ccnr, LM_128, CCNL_INFO))
            ccnr_debug_ccnb(ccnr, __LINE__, "r_proto_policy_complete older policy ignored", NULL,
                            ccnb, ccnb_size);        
        return (CCN_UPCALL_RESULT_ERR);
    }
    // all components not including segment
    name = ccn_charbuf_create();
    if (name == NULL || ccn_name_init(name) < 0) {
        ccnr_msg(ccnr,"r_proto_policy_complete no memory to update policy");
        ccn_charbuf_destroy(&name);
        return (CCN_UPCALL_RESULT_ERR);
    }
    ccn_name_append_components(name, ccnb, cc->buf[0], cc->buf[cc->n - 2]);
    ccn_schedule_event(ccnr->sched, 500, r_proto_policy_update, name, 0);
    if (CCNSHOULDLOG(ccnr, LM_128, CCNL_FINEST))
        ccnr_msg(ccnr,"r_proto_policy_complete update scheduled");        
    
    return (CCN_UPCALL_RESULT_OK);
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
    struct ccnr_expect_content *expect_content = NULL;
    struct ccn_charbuf *reply_body = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_indexbuf *ic = NULL;
    enum ccn_upcall_res ans = CCN_UPCALL_RESULT_ERR;
    struct ccn_charbuf *msg = NULL;
    int res = 0;
    int start = 0;
    int end = 0;
    int is_policy = 0;
    intmax_t segment;
    int i;
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    
    // XXX - Check for valid nonce
    // XXX - Check for pubid - if present and not ours, do not respond.
    // Check for answer origin kind.
    // If Exclude is there, there might be something fishy going on.
    
    ccnr = (struct ccnr_handle *)selfp->data;
    if (ccnr->start_write_scope_limit < 3) {
        start = info->pi->offset[CCN_PI_B_Scope];
        end = info->pi->offset[CCN_PI_E_Scope];
        if (start == end || info->pi->scope > ccnr->start_write_scope_limit) {
            if (CCNSHOULDLOG(ccnr, LM_128, CCNL_INFO))
                ccnr_msg(ccnr, "r_proto_start_write: interest scope exceeds limit");
            return(CCN_UPCALL_RESULT_OK);
        }
    }
    // don't handle the policy file here
    start = info->pi->offset[CCN_PI_B_Name];
    end = info->interest_comps->buf[marker_comp - 1]; // not including version or marker
    name = ccn_charbuf_create();
    ccn_charbuf_append(name, info->interest_ccnb + start, end - start);
    ccnb_element_end(name);
    if (0 ==ccn_compare_names(name->buf, name->length,
                              ccnr->policy_name->buf, ccnr->policy_name->length))
        is_policy = 1;
    
    /* Generate our reply */
    start = info->pi->offset[CCN_PI_B_Name];
    end = info->interest_comps->buf[info->pi->prefix_comps];
    name->length = 0;
    ccn_charbuf_append(name, info->interest_ccnb + start, end - start);
    ccnb_element_end(name);
    msg = ccn_charbuf_create();
    reply_body = ccn_charbuf_create();
    r_proto_append_repo_info(ccnr, reply_body, NULL, NULL);
    sp.freshness = 12; /* seconds */
    res = ccn_sign_content(info->h, msg, name, &sp,
                           reply_body->buf, reply_body->length);
    if (res < 0)
        goto Bail;
    if (CCNSHOULDLOG(ccnr, LM_128, CCNL_FINE))
        ccnr_debug_ccnb(ccnr, __LINE__, "r_proto_start_write response", NULL,
                        msg->buf, msg->length);
    res = ccn_put(info->h, msg->buf, msg->length);
    if (res < 0) {
        ccnr_debug_ccnb(ccnr, __LINE__, "r_proto_start_write ccn_put FAILED", NULL,
                        msg->buf, msg->length);
        goto Bail;
    }

    /* Send an interest for the content */
    expect_content = calloc(1, sizeof(*expect_content));
    if (expect_content == NULL)
        goto Bail;
    expect_content->ccnr = ccnr;
    expect_content->final = -1;
    for (i = 0; i < CCNR_PIPELINE; i++)
        expect_content->outstanding[i] = -1;
    if (is_policy) {
        expect_content->expect_complete = &r_proto_policy_complete;
        if (CCNSHOULDLOG(ccnr, LM_128, CCNL_FINE))
            ccnr_msg(ccnr, "r_proto_start_write: is policy file");
    }
    incoming = calloc(1, sizeof(*incoming));
    if (incoming == NULL)
        goto Bail;
    incoming->p = &r_proto_expect_content;
    incoming->data = expect_content;
    ic = info->interest_comps;
    ccn_name_init(name);
    ccn_name_append_components(name, info->interest_ccnb, ic->buf[0], ic->buf[marker_comp]);
    /* when invoked from start-write-checked we have nonce, starting segment, and hash
     * the max suffix components is 0 since we have the hash
     */
    if (0 == r_util_name_comp_compare(info->interest_ccnb, info->interest_comps,
                                      marker_comp, REPO_SWC, strlen(REPO_SWC))) {
        segment = r_util_segment_from_component(info->interest_ccnb, ic->buf[marker_comp + 2], ic->buf[marker_comp + 3]);
        ccn_name_append_components(name, info->interest_ccnb, ic->buf[marker_comp + 2], ic->buf[marker_comp + 4]);
        templ = r_proto_mktemplate(expect_content, NULL, 0);
    } else {
        /* start-write does not specify starting segment, start at segment 0 */
        segment = 0;
        ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, segment);
        templ = r_proto_mktemplate(expect_content, NULL, 1);
    }
    if (segment >= 0) {
        expect_content->outstanding[segment % CCNR_PIPELINE] = segment;
        incoming->intdata = segment;
    }
    res = ccn_express_interest(info->h, name, incoming, templ);
    if (res >= 0) {
        /* upcall will free these when it is done. */
        incoming = NULL;
        expect_content = NULL;
        ans = CCN_UPCALL_RESULT_INTEREST_CONSUMED;
    }
    else {
        ccnr_debug_ccnb(ccnr, __LINE__, "r_proto_start_write ccn_express_interest FAILED", NULL,
                        name->buf, name->length);
        goto Bail;
    }
    
Bail:
    if (incoming != NULL)
        free(incoming);
    if (expect_content != NULL)
        free(expect_content);
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
    
    // XXX - do we need to disallow the policy file here too?
    ccnr = (struct ccnr_handle *)selfp->data;
    if (ccnr->start_write_scope_limit < 3) {
        start = info->pi->offset[CCN_PI_B_Scope];
        end = info->pi->offset[CCN_PI_E_Scope];
        if (start == end || info->pi->scope > ccnr->start_write_scope_limit) {
            if (CCNSHOULDLOG(ccnr, LM_128, CCNL_INFO))
                ccnr_msg(ccnr, "r_proto_start_write_checked: interest scope exceeds limit");
            return(CCN_UPCALL_RESULT_OK);
        }
    }
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
    if (CCNSHOULDLOG(ccnr, LM_128, CCNL_FINE))
        ccnr_debug_ccnb(ccnr, __LINE__, "r_proto_start_write_checked looking for", NULL,
                        interest->buf, interest->length);
    content = r_store_lookup(ccnr, interest->buf, pi, comps);
    ccn_charbuf_destroy(&interest);
    ccn_indexbuf_destroy(&comps);
    if (content == NULL) {
        ccn_charbuf_destroy(&name);
        if (CCNSHOULDLOG(ccnr, LM_128, CCNL_FINE))
            ccnr_msg(ccnr, "r_proto_start_write_checked: NOT PRESENT");
        // XXX - dropping into the start_write case means we do not check the provided digest when fetching, so this is not completely right.
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
    r_proto_append_repo_info(ccnr, reply_body, name, NULL);
    start = info->pi->offset[CCN_PI_B_Name];
    end = info->interest_comps->buf[info->pi->prefix_comps];
    name->length = 0;
    ccn_charbuf_append(name, info->interest_ccnb + start, end - start);
    ccnb_element_end(name);
    sp.freshness = 12; /* Seconds */
    res = ccn_sign_content(info->h, msg, name, &sp,
                           reply_body->buf, reply_body->length);
    if (res < 0)
        goto Bail;
    if (CCNSHOULDLOG(ccnr, LM_128, CCNL_FINE))
        ccnr_msg(ccnr, "r_proto_start_write_checked PRESENT");
    res = ccn_put(info->h, msg->buf, msg->length);
    if (res < 0) {
        // note the error somehow.
        ccnr_debug_ccnb(ccnr, __LINE__, "r_proto_start_write_checked ccn_put FAILED", NULL,
                        msg->buf, msg->length);
    }
    //// end of copied code
Bail:
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&reply_body);
    ccn_charbuf_destroy(&msg);
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
        if (ccn_name_split(name, name_comps) < 0)
            goto Bail;
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
    if (CCNSHOULDLOG(ccnr, LM_8, CCNL_FINE))
        ccnr_msg(ccnr, "r_proto_check_exclude: do%s exclude", (ans == 1) ? "" : " not");
    return(ans);
}

void
r_proto_finalize_enum_state(struct hashtb_enumerator *e)
{
    struct enum_state *es = e->data;
    unsigned i;
    
    ccn_charbuf_destroy(&es->name);
    ccn_charbuf_destroy(&es->interest); // unnecessary?
    ccn_charbuf_destroy(&es->reply_body);
    ccn_indexbuf_destroy(&es->interest_comps);
    for (i = 0; i < ENUM_N_COBS; i++)
        ccn_charbuf_destroy(&(es->cob[i]));
    return;
}

#define ENUMERATION_STATE_TICK_MICROSEC 1000000
/**
 * Remove expired enumeration table entries
 */
static int
reap_enumerations(struct ccn_schedule *sched,
                  void *clienth,
                  struct ccn_scheduled_event *ev,
                  int flags)
{
    struct ccnr_handle *ccnr = clienth;
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct enum_state *es = NULL;
    
    if ((flags & CCN_SCHEDULE_CANCEL) != 0) {
        ccnr->reap_enumerations = NULL;
        return(0);
    }
    hashtb_start(ccnr->enum_state_tab, e);
    for (es = e->data; es != NULL; es = e->data) {
        if (es->active != ES_ACTIVE &&
            r_util_timecmp(es->lastuse_sec + es->lifetime, es->lastuse_usec,
                           ccnr->sec, ccnr->usec) <= 0) {
                if (CCNSHOULDLOG(ccnr, LM_8, CCNL_FINER))
                    ccnr_debug_ccnb(ccnr, __LINE__, "reap enumeration state", NULL,
                                    es->name->buf, es->name->length);            		// remove the entry from the hash table, finalization frees data
                hashtb_delete(e);
            }
        hashtb_next(e);
    }
    hashtb_end(e);
    if (hashtb_n(ccnr->enum_state_tab) == 0) {
        ccnr->reap_enumerations = NULL;
        return(0);
    }
    return(ENUMERATION_STATE_TICK_MICROSEC);
}
static void
reap_enumerations_needed(struct ccnr_handle *ccnr)
{
    if (ccnr->reap_enumerations == NULL)
        ccnr->reap_enumerations = ccn_schedule_event(ccnr->sched,
                                                     ENUMERATION_STATE_TICK_MICROSEC,
                                                     reap_enumerations,
                                                     NULL, 0);
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
    struct ccn_charbuf *cob = NULL;
    struct ccn_charbuf *interest = NULL;
    struct ccn_indexbuf *comps = NULL;
    int res;
    struct content_entry *content = NULL;
    struct enum_state *es = NULL;
    
    ccnr = (struct ccnr_handle *)selfp->data;
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
    // Look for a previous enumeration under this prefix
    hashtb_start(ccnr->enum_state_tab, e);
    res = hashtb_seek(e, name->buf, name->length, 0);
    es = e->data;
    if (CCNSHOULDLOG(ccnr, LM_8, CCNL_FINE))
        ccnr_debug_ccnb(ccnr, __LINE__, "enumeration: begin hash key", NULL,
                        name->buf, name->length);
    // Do not restart an active enumeration, it is probably a duplicate interest
    // TODO: may need attention when es->active == ES_ACTIVE_PENDING_INACTIVE
    if (res == HT_OLD_ENTRY && es->active != ES_INACTIVE) {
        if (es->next_segment > 0)
            cob = es->cob[(es->next_segment - 1) % ENUM_N_COBS];
        if (cob && ccn_content_matches_interest(cob->buf, cob->length, 1, NULL,
                                         info->interest_ccnb, info->pi->offset[CCN_PI_E], info->pi)) {
            if (CCNSHOULDLOG(ccnr, LM_8, CCNL_FINER))
                ccnr_msg(ccnr, "enumeration: duplicate request for last cob");
            ccn_put(info->h, cob->buf, cob->length);
            es->cob_deferred[(es->next_segment - 1) % ENUM_N_COBS] = 0;
            ans = CCN_UPCALL_RESULT_INTEREST_CONSUMED;
        } else {
            if (CCNSHOULDLOG(ccnr, LM_8, CCNL_FINEST)) {
                ccnr_msg(ccnr, "enumeration: restart of active enumeration, or excluded");
                ccnr_debug_ccnb(ccnr, __LINE__, "enum    interest: ", NULL, info->interest_ccnb, info->pi->offset[CCN_PI_E]);
                if (cob != NULL)
                    ccnr_debug_ccnb(ccnr, __LINE__, "enum cob content: ", NULL, cob->buf, cob->length);
            }
            ans = CCN_UPCALL_RESULT_OK;
        }
        hashtb_end(e);
        goto Bail;
    }
    // Continue to construct the name under which we will respond: %C1.E.be
    ccn_name_append_components(name, info->interest_ccnb,
                               info->interest_comps->buf[marker_comp],
                               info->interest_comps->buf[marker_comp + 1]);
    // Append the repository key id %C1.K.%00<repoid>
    ccn_name_append(name, ccnr->ccnr_keyid->buf, ccnr->ccnr_keyid->length);
    
    if (res == HT_NEW_ENTRY || es->starting_cookie != ccnr->cookie) {
        // this is a new enumeration, the time is now.
        res = ccn_create_version(info->h, name, CCN_V_NOW, 0, 0);
        if (es->name != NULL)
            ccn_charbuf_destroy(&es->name);
        es->name = ccn_charbuf_create();
        ccn_charbuf_append_charbuf(es->name, name);
        es->starting_cookie = ccnr->cookie; // XXX - a conservative indicator of change
    }
    ccn_charbuf_destroy(&name);
    // check the exclude against the result name
    if (CCNSHOULDLOG(ccnr, LM_8, CCNL_FINE))
        ccnr_debug_ccnb(ccnr, __LINE__, "begin enum: result name", NULL,
                        es->name->buf, es->name->length);
    
    if (r_proto_check_exclude(ccnr, info, es->name) > 0) {
        hashtb_end(e);
        goto Bail;
    }

    // do we have anything that matches this enumeration request?
    content = r_store_find_first_match_candidate(ccnr, interest->buf, pi);
    if (content != NULL &&
        !r_store_content_matches_interest_prefix(ccnr, content, interest->buf, interest->length))
        content = NULL;
    ccn_charbuf_destroy(&es->cob[0]);
    es->cob[0] = ccn_charbuf_create();
    memset(es->cob_deferred, 0, sizeof(es->cob_deferred));
    ccn_charbuf_destroy(&es->reply_body);
    es->reply_body = ccn_charbuf_create();
    ccnb_element_begin(es->reply_body, CCN_DTAG_Collection);
    es->content = content;
    ccn_charbuf_destroy(&es->interest);
    es->interest = interest;
    interest = NULL;
    ccn_indexbuf_destroy(&es->interest_comps);
    es->interest_comps = comps;
    comps = NULL;
    es->next_segment = 0;
    es->lastuse_sec = ccnr->sec;
    es->lastuse_usec = ccnr->usec;
    if (content) {
        es->lifetime = 3 * ccn_interest_lifetime_seconds(info->interest_ccnb, pi);
        es->active = ES_ACTIVE;
    } else {
        es->lifetime = ccn_interest_lifetime_seconds(info->interest_ccnb, pi);
        es->active = ES_PENDING;
    }
    hashtb_end(e);
    reap_enumerations_needed(ccnr);
    if (content)
        ans = r_proto_continue_enumeration(selfp, kind, info, marker_comp);
    else
        ans = CCN_UPCALL_RESULT_OK;
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
    struct ccn_charbuf *result_name = NULL;
    struct ccn_charbuf *cob = NULL;
    struct ccn_indexbuf *ic = NULL;
    intmax_t segment;
    struct enum_state *es = NULL;
    struct ccnr_handle *ccnr = NULL;
    struct hashtb_enumerator enumerator = {0};
    struct hashtb_enumerator *e = &enumerator;
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    int cobs_deferred;
    int i;
    int res = 0;
    
    ccnr = (struct ccnr_handle *)selfp->data;
    ic = info->interest_comps;
    hashkey=ccn_charbuf_create();
    ccn_name_init(hashkey);
    ccn_name_append_components(hashkey, info->interest_ccnb,
                               info->interest_comps->buf[0],
                               info->interest_comps->buf[marker_comp]);
    hashtb_start(ccnr->enum_state_tab, e);
    res = hashtb_seek(e, hashkey->buf, hashkey->length, 0);
    ccn_charbuf_destroy(&hashkey);
    if (res != HT_OLD_ENTRY) {
        hashtb_end(e);
        return(CCN_UPCALL_RESULT_ERR);
    }
    es = e->data;
    if (es->active != ES_ACTIVE && es->active != ES_ACTIVE_PENDING_INACTIVE) {
        hashtb_end(e);
        return(CCN_UPCALL_RESULT_ERR);
    }
    // If there is a segment in the request, get the value.
    segment = r_util_segment_from_component(info->interest_ccnb,
                                            ic->buf[ic->n - 2],
                                            ic->buf[ic->n - 1]);
    if (CCNSHOULDLOG(ccnr, LM_8, CCNL_FINE))
        ccnr_msg(ccnr, "enumeration: requested %jd :: expected %jd", segment, es->next_segment);
    if (segment >= 0 && segment != es->next_segment) {
        // too far in the future for us to process
        if (segment > es->next_segment + (ENUM_N_COBS / 2)) {
            if (CCNSHOULDLOG(ccnr, LM_8, CCNL_FINER))
                ccnr_msg(ccnr, "enumeration: ignoring future segment requested %jd :: expected %jd", segment, es->next_segment);
            hashtb_end(e);
            return (CCN_UPCALL_RESULT_OK);
        }
        // if theres a possibility we could have it
        if (segment >= es->next_segment - ENUM_N_COBS) {
            cob = es->cob[segment % ENUM_N_COBS];
            if (cob &&
                ccn_content_matches_interest(cob->buf, cob->length, 1, NULL,
                                             info->interest_ccnb, info->pi->offset[CCN_PI_E], info->pi)) {
                    if (CCNSHOULDLOG(ccnr, LM_8, CCNL_FINER))
                        ccnr_msg(ccnr, "enumeration: putting cob for out-of-order segment %jd",
                                 segment);
                    ccn_put(info->h, cob->buf, cob->length);
                    es->cob_deferred[segment % ENUM_N_COBS] = 0;
                    if (es->active == ES_ACTIVE_PENDING_INACTIVE) {
                        for (i = 0, cobs_deferred = 0; i < ENUM_N_COBS; i++) {
                            cobs_deferred += es->cob_deferred[i];
                        }
                        if (cobs_deferred == 0)
                            goto EnumerationComplete;
                    }
                    hashtb_end(e);
                    return (CCN_UPCALL_RESULT_INTEREST_CONSUMED);
                }
        }
    }
NextSegment:
    if (CCNSHOULDLOG(ccnr, blah, CCNL_FINE))
        ccnr_msg(ccnr, "enumeration: generating segment %jd", es->next_segment);
    es->lastuse_sec = ccnr->sec;
    es->lastuse_usec = ccnr->usec;
    while (es->content != NULL &&
           r_store_content_matches_interest_prefix(ccnr, es->content,
                                                   es->interest->buf,
                                                   es->interest->length)) {
        int save = es->reply_body->length;
        ccnb_element_begin(es->reply_body, CCN_DTAG_Link);
        ccnb_element_begin(es->reply_body, CCN_DTAG_Name);
        ccnb_element_end(es->reply_body); /* </Name> */
        res = r_store_name_append_components(es->reply_body, ccnr, es->content, es->interest_comps->n - 1, 1);
        ccnb_element_end(es->reply_body); /* </Link> */
        if (res == 0) {
            /* The name matched exactly, need to skip. */
            es->reply_body->length = save;
            es->content = r_store_next_child_at_level(ccnr, es->content, es->interest_comps->n - 1);
            continue;
        }
        if (res != 1) {
            ccnr_debug_ccnb(ccnr, __LINE__, "oops", NULL, es->interest->buf, es->interest->length);
            ccnr_debug_content(ccnr, __LINE__, "oops", NULL, es->content);
            abort();
        }
        es->content = r_store_next_child_at_level(ccnr, es->content, es->interest_comps->n - 1);
        if (es->reply_body->length >= 4096) {
            result_name = ccn_charbuf_create();
            ccn_charbuf_append_charbuf(result_name, es->name);
            ccn_name_append_numeric(result_name, CCN_MARKER_SEQNUM, es->next_segment);
            sp.freshness = 60;
            cob = es->cob[es->next_segment % ENUM_N_COBS];
            if (cob == NULL) {
                cob = ccn_charbuf_create();
                es->cob[es->next_segment % ENUM_N_COBS] = cob;
            }
            cob->length = 0;
            res = ccn_sign_content(info->h, cob, result_name, &sp,
                                   es->reply_body->buf, 4096);
            ccn_charbuf_destroy(&result_name);
            if (segment == -1 || segment == es->next_segment) {
                if (CCNSHOULDLOG(ccnr, blah, CCNL_FINER))
                    ccnr_msg(ccnr, "enumeration: putting cob for segment %jd", es->next_segment);
                ccn_put(info->h, cob->buf, cob->length);
            } else {
                es->cob_deferred[es->next_segment % ENUM_N_COBS] = 1;
            }
            es->next_segment++;
            memmove(es->reply_body->buf, es->reply_body->buf + 4096, es->reply_body->length - 4096);
            es->reply_body->length -= 4096;
            if (segment >= es->next_segment)
                 goto NextSegment;
            hashtb_end(e);
            return (CCN_UPCALL_RESULT_INTEREST_CONSUMED);
        }
    }
    // we will only get here if we are finishing an in-progress enumeration
    ccnb_element_end(es->reply_body); /* </Collection> */
    result_name = ccn_charbuf_create();
    ccn_charbuf_append_charbuf(result_name, es->name);
    ccn_name_append_numeric(result_name, CCN_MARKER_SEQNUM, es->next_segment);
    sp.freshness = 60;
    sp.sp_flags |= CCN_SP_FINAL_BLOCK;
    cob = es->cob[es->next_segment % ENUM_N_COBS];
    if (cob == NULL) {
        cob = ccn_charbuf_create();
        es->cob[es->next_segment % ENUM_N_COBS] = cob;
    }
    cob->length = 0;
    res = ccn_sign_content(info->h, cob, result_name, &sp, es->reply_body->buf,
                           es->reply_body->length);
    ccn_charbuf_destroy(&result_name);    
    if (CCNSHOULDLOG(ccnr, blah, CCNL_FINER))
        ccnr_msg(ccnr, "enumeration: putting final cob for segment %jd", es->next_segment);
    ccn_put(info->h, cob->buf, cob->length);
    es->cob_deferred[es->next_segment % ENUM_N_COBS] = 0;
    for (i = 0, cobs_deferred = 0; i < ENUM_N_COBS; i++) {
        cobs_deferred += es->cob_deferred[i];
    }
    if (cobs_deferred > 0) {
        if (CCNSHOULDLOG(ccnr, blah, CCNL_FINER))
            ccnr_msg(ccnr, "enumeration: %d pending cobs, inactive pending complete",
                     cobs_deferred);
        es->active = ES_ACTIVE_PENDING_INACTIVE;
        hashtb_end(e);
        return (CCN_UPCALL_RESULT_INTEREST_CONSUMED);
    }
EnumerationComplete:
    if (CCNSHOULDLOG(ccnr, blah, CCNL_FINER))
        ccnr_msg(ccnr, "enumeration: inactive", es->next_segment);
    // The enumeration is complete, free charbufs but leave the name.
    es->active = ES_INACTIVE;
    ccn_charbuf_destroy(&es->interest);
    ccn_charbuf_destroy(&es->reply_body);
    for (i = 0; i < ENUM_N_COBS; i++)
        ccn_charbuf_destroy(&es->cob[i]);
    ccn_indexbuf_destroy(&es->interest_comps);
    hashtb_end(e);
    return(CCN_UPCALL_RESULT_INTEREST_CONSUMED);
}

void
r_proto_dump_enums(struct ccnr_handle *ccnr)
{
    struct enum_state *es = NULL;
    struct hashtb_enumerator enumerator = {0};
    struct hashtb_enumerator *e = &enumerator;
    
    for (hashtb_start(ccnr->enum_state_tab, e); e->data != NULL; hashtb_next(e)) {
        es = e->data;
        ccnr_msg(ccnr, "Enumeration active: %d, next segment %d, cookie %u",
                 es->active, es->next_segment, es->starting_cookie);
        ccnr_debug_ccnb(ccnr, __LINE__, "     enum name", NULL,
                        es->name->buf, es->name->length);
        
    }  
    hashtb_end(e);
}

static enum ccn_upcall_res
r_proto_bulk_import(struct ccn_closure *selfp,
                          enum ccn_upcall_kind kind,
                          struct ccn_upcall_info *info,
                          int marker_comp)
{
    enum ccn_upcall_res ans = CCN_UPCALL_RESULT_ERR;
    struct ccnr_handle *ccnr = NULL;
    struct ccn_charbuf *filename = NULL;
    struct ccn_charbuf *filename2 = NULL;
    const unsigned char *mstart = NULL;
    size_t mlength;
    struct ccn_indexbuf *ic = NULL;
    struct ccn_charbuf *msg = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *reply_body = NULL;
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    const char *infostring = "OK";
    int res;
    
    ccnr = (struct ccnr_handle *)selfp->data;
    ccn_name_comp_get(info->interest_ccnb, info->interest_comps, marker_comp,
                      &mstart, &mlength);
    if (mlength <= strlen(REPO_AF) + 1 || mstart[strlen(REPO_AF)] != '~') {
        infostring = "missing or malformed bulk import name component";
        ccnr_msg(ccnr, "r_proto_bulk_import: %s", infostring);
        goto Reply;
    }
    mstart += strlen(REPO_AF) + 1;
    mlength -= (strlen(REPO_AF) + 1);
    if (memchr(mstart, '/', mlength) != NULL) {
        infostring = "bulk import filename must not include directory";
        ccnr_msg(ccnr, "r_proto_bulk_import: %s", infostring);
        goto Reply;
    }
    filename = ccn_charbuf_create();
    ccn_charbuf_append_string(filename, "import/");
    ccn_charbuf_append(filename, mstart, mlength);
    res = r_init_map_and_process_file(ccnr, filename, 0);
    if (res == 1) {
        infostring = "unable to open bulk import file";
        ccnr_msg(ccnr, "r_proto_bulk_import: %s", infostring);
        goto Reply;
    }
    if (res < 0) {
        infostring = "error parsing bulk import file";
        ccnr_msg(ccnr, "r_proto_bulk_import: %s", infostring);
        goto Reply;
    }
    /* we think we can process it */
    filename->length = 0;
    ccn_charbuf_putf(filename, "%s/import/", ccnr->directory);
    ccn_charbuf_append(filename, mstart, mlength);
    filename2 = ccn_charbuf_create();
    ccn_charbuf_putf(filename2, "%s/import/.", ccnr->directory);
    ccn_charbuf_append(filename2, mstart, mlength);
    res = rename(ccn_charbuf_as_string(filename),
                 ccn_charbuf_as_string(filename2));
    if (res < 0) {
        infostring = "error renaming bulk import file";
        ccnr_msg(ccnr, "r_proto_bulk_import: %s", infostring);
        goto Reply;        
    }
    filename->length = 0;
    ccn_charbuf_append_string(filename, "import/.");
    ccn_charbuf_append(filename, mstart, mlength);
    res = r_init_map_and_process_file(ccnr, filename, 1);
    if (res < 0) {
        infostring = "error merging bulk import file";
        ccnr_msg(ccnr, "r_proto_bulk_import: %s", infostring);
        // fall through and unlink anyway
    }
    if (CCNSHOULDLOG(ccnr, sdfdf, CCNL_FINE))
        ccnr_msg(ccnr, "unlinking bulk import file %s", ccn_charbuf_as_string(filename2));   
    unlink(ccn_charbuf_as_string(filename2));

Reply:
    /* Generate our reply */
    name = ccn_charbuf_create();
    ccn_name_init(name);
    ic = info->interest_comps;
    ccn_name_append_components(name, info->interest_ccnb, ic->buf[0], ic->buf[ic->n - 1]);

    msg = ccn_charbuf_create();
    reply_body = ccn_charbuf_create();
    r_proto_append_repo_info(ccnr, reply_body, NULL, infostring);
    sp.freshness = 12; /* Seconds */
    res = ccn_sign_content(info->h, msg, name, &sp,
                           reply_body->buf, reply_body->length);
    if (res < 0)
        goto Bail;
    res = ccn_put(info->h, msg->buf, msg->length);
    if (res < 0) {
        ccnr_debug_ccnb(ccnr, __LINE__, "r_proto_bulk_import ccn_put FAILED", NULL,
                        msg->buf, msg->length);
        goto Bail;
    }
    ans = CCN_UPCALL_RESULT_INTEREST_CONSUMED;

Bail:
    if (filename != NULL) ccn_charbuf_destroy(&filename);
    if (filename2 != NULL) ccn_charbuf_destroy(&filename2);
    if (name != NULL) ccn_charbuf_destroy(&name);
    if (msg != NULL) ccn_charbuf_destroy(&msg);
    if (reply_body != NULL) ccn_charbuf_destroy(&reply_body);
    return (ans);
}

/* Construct a charbuf with an encoding of a Policy object 
 *
 *  <xs:complexType name="PolicyType">
 *      <xs:sequence>
 *      <xs:element name="PolicyVersion" type="xs:string"/> 
 *      <xs:element name="LocalName" type="xs:string"/>
 *      <xs:element name="GlobalPrefix" type="xs:string"/>
 *  <!-- 0 or more names -->
 *      <xs:element name="Namespace" type="xs:string" minOccurs="0" maxOccurs="unbounded"/>
 *      </xs:sequence>
 *  </xs:complexType>
 */ 
PUBLIC int
r_proto_policy_append_basic(struct ccnr_handle *ccnr,
                            struct ccn_charbuf *policy,
                            const char *version, const char *local_name,
                            const char *global_prefix)
{
    int res;
    res = ccnb_element_begin(policy, CCN_DTAG_Policy);
    res |= ccnb_tagged_putf(policy, CCN_DTAG_PolicyVersion, "%s", version);
    res |= ccnb_tagged_putf(policy, CCN_DTAG_LocalName, "%s", local_name);
    res |= ccnb_tagged_putf(policy, CCN_DTAG_GlobalPrefix, "%s", global_prefix);
    res |= ccnb_element_end(policy);
    return (res);
}
PUBLIC int
r_proto_policy_append_namespace(struct ccnr_handle *ccnr,
                                struct ccn_charbuf *policy,
                                const char *namespace)
{
    int res;
    if (policy->length < 2)
        return(-1);
    policy->length--;   /* remove the closer */
    res = ccnb_tagged_putf(policy, CCN_DTAG_Namespace, "%s", namespace);
    ccnb_element_end(policy);
    return(res);
}

/**
 * Parse a ccnb-encoded policy content object and fill in a ccn_parsed_policy
 * structure as the result.
 */
PUBLIC int
r_proto_parse_policy(struct ccnr_handle *ccnr, const unsigned char *buf, size_t length,
                     struct ccnr_parsed_policy *pp)
{
    int res = 0;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder, buf,
                                                      length);
    if (ccn_buf_match_dtag(d, CCN_DTAG_Policy)) {
        ccn_buf_advance(d);
        pp->policy_version_offset = ccn_parse_tagged_string(d, CCN_DTAG_PolicyVersion, pp->store);
        pp->local_name_offset = ccn_parse_tagged_string(d, CCN_DTAG_LocalName, pp->store);
        pp->global_prefix_offset = ccn_parse_tagged_string(d, CCN_DTAG_GlobalPrefix, pp->store);
        pp->namespaces->n = 0;
        while (ccn_buf_match_dtag(d, CCN_DTAG_Namespace)) {
            ccn_indexbuf_append_element(pp->namespaces, ccn_parse_tagged_string(d, CCN_DTAG_Namespace, pp->store));
        }
        ccn_buf_check_close(d);
    } else {
        return(-1);
    }
    return (res);
}

/**
 * Initiate a key fetch if necessary.
 * @returns -1 if error or no name, 0 if fetch was issued, 1 if already stored.
 */
int
r_proto_initiate_key_fetch(struct ccnr_handle *ccnr,
                           const unsigned char *msg,
                           struct ccn_parsed_ContentObject *pco,
                           int use_link,
                           ccnr_cookie a)
{
    /* 
     * Create a new interest in the key name, set up a callback that will
     * insert the key into repo.
     */
    int res;
    struct ccn_charbuf *key_name = NULL;
    struct ccn_closure *key_closure = NULL;
    struct ccn_charbuf *templ = NULL;
    struct ccnr_expect_content *expect_content = NULL;
    const unsigned char *namestart = NULL;
    int namelen = 0;
    int keynamelen;
    int i;
    
    keynamelen = (pco->offset[CCN_PCO_E_KeyName_Name] -
                  pco->offset[CCN_PCO_B_KeyName_Name]);
    if (use_link) {
        /* Try to follow a link instead of using keyname */
        if (pco->type == CCN_CONTENT_LINK) {
            /* For now we only pay attention to the Name in the Link. */
            const unsigned char *data = NULL;
            size_t data_size = 0;
            struct ccn_buf_decoder decoder;
            struct ccn_buf_decoder *d;
            res = ccn_content_get_value(msg, pco->offset[CCN_PCO_E], pco,
                                        &data, &data_size);
            if (res < 0)
                return(-1);
            d = ccn_buf_decoder_start(&decoder, data, data_size);
            if (ccn_buf_match_dtag(d, CCN_DTAG_Link)) {
                int start = 0;
                int end = 0;
                ccn_buf_advance(d);
                start = d->decoder.token_index;
                ccn_parse_Name(d, NULL);
                end = d->decoder.token_index;
                ccn_buf_check_close(d);
                if (d->decoder.state < 0)
                    return(-1);
                namestart = data + start;
                namelen = end - start;
                if (namelen == keynamelen &&
                    0 == memcmp(namestart, msg + pco->offset[CCN_PCO_B_KeyName_Name], namelen)) {
                    /*
                     * The link matches the key locator. There is no point
                     * in checking two times for the same thing.
                     */
                    if (CCNSHOULDLOG(ccnr, sdfdf, CCNL_FINE))
                        ccnr_debug_ccnb(ccnr, __LINE__, "keyfetch_link_opt",
                                        NULL, namestart, namelen);
                    return(-1);
                }
            }
        }
    }
    else {
        /* Use the KeyName if present */
        namestart = msg + pco->offset[CCN_PCO_B_KeyName_Name];
        namelen = (pco->offset[CCN_PCO_E_KeyName_Name] -
                   pco->offset[CCN_PCO_B_KeyName_Name]);
    }
    /*
     * If there is no KeyName or link, provided, we can't ask, so do not bother.
     */
    if (namelen == 0 || a == 0)
        return(-1);
    key_name = ccn_charbuf_create();
    ccn_charbuf_append(key_name, namestart, namelen);
    /* Construct an interest complete with Name so we can do lookup */
    templ = ccn_charbuf_create();
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccn_charbuf_append(templ, key_name->buf, key_name->length);
    ccnb_tagged_putf(templ, CCN_DTAG_MinSuffixComponents, "%d", 1);
    ccnb_tagged_putf(templ, CCN_DTAG_MaxSuffixComponents, "%d", 3);
    if (pco->offset[CCN_PCO_B_KeyName_Pub] < pco->offset[CCN_PCO_E_KeyName_Pub]) {
        ccn_charbuf_append(templ,
                           msg + pco->offset[CCN_PCO_B_KeyName_Pub],
                           (pco->offset[CCN_PCO_E_KeyName_Pub] - 
                            pco->offset[CCN_PCO_B_KeyName_Pub]));
    }
    ccnb_element_end(templ); /* </Interest> */
    /* See if we already have it - if so we declare we are done. */
    if (r_lookup(ccnr, templ, NULL) == 0) {
        res = 1;
        // Note - it might be that the thing we found is not really the thing
        // we were after.  For now we don't check.
    }
    else {
        /* We do not have it; need to ask */
        res = -1;
        expect_content = calloc(1, sizeof(*expect_content));
        if (expect_content == NULL)
            goto Bail;
        expect_content->ccnr = ccnr;
        expect_content->final = -1;
        for (i = 0; i < CCNR_PIPELINE; i++)
            expect_content->outstanding[i] = -1;
        /* inform r_proto_expect_content we are looking for a key. */
        expect_content->keyfetch = a;
        key_closure = calloc(1, sizeof(*key_closure));
        if (key_closure == NULL)
            goto Bail;
        key_closure->p = &r_proto_expect_content;
        key_closure->data = expect_content;
        res = ccn_express_interest(ccnr->direct_client, key_name, key_closure, templ);
        if (res >= 0) {
            if (CCNSHOULDLOG(ccnr, sdfdf, CCNL_FINE))
                ccnr_debug_ccnb(ccnr, __LINE__, "keyfetch_start",
                                NULL, templ->buf, templ->length);
            key_closure = NULL;
            expect_content = NULL;
            res = 0;
        }
    }
Bail:
    if (key_closure != NULL)
        free(key_closure);
    if (expect_content != NULL)
        free(expect_content);
    ccn_charbuf_destroy(&key_name);
    ccn_charbuf_destroy(&templ);
    return(res);
}

