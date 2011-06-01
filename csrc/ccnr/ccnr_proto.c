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

#include "ccnr_forwarding.h"
#include "ccnr_io.h"
#include "ccnr_msg.h"
#include "ccnr_sendq.h"
#include "ccnr_store.h"

#define REPO_SW "\301.R.sw"
#define REPO_SWC "\301.R.sw-c"
#define NAME_BE "\301.E.be"
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
    // XXX - Here is where we should check for command markers
    ncomps = info->interest_comps->n;
    if (ncomps >= 2 && 0 == ccn_name_comp_strcmp(info->interest_ccnb, info->interest_comps, ncomps - 2, NAME_BE)) {
        ccnr_debug_ccnb(ccnr, __LINE__, "name_enumeration", NULL, info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        goto Finish;
    } else if (ncomps >= 3 && 0 == ccn_name_comp_strcmp(info->interest_ccnb, info->interest_comps, ncomps - 3, REPO_SW)) {
        ccnr_debug_ccnb(ccnr, __LINE__, "repo_start_write", NULL, info->interest_ccnb, info->pi->offset[CCN_PI_E]);
        goto Finish;
    } else if (ncomps >= 5 && 0 == ccn_name_comp_strcmp(info->interest_ccnb, info->interest_comps, ncomps - 5, REPO_SWC)) {
        ccnr_debug_ccnb(ccnr, __LINE__, "repo_start_write_checked", NULL, info->interest_ccnb, info->pi->offset[CCN_PI_E]);
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
r_proto_append_repo_info(struct ccn_charbuf *rinfo) {
    int res;
    struct ccn_charbuf *name = ccn_charbuf_create();
    if (name == NULL) return (-1);
    res = ccnb_element_begin(rinfo, CCN_DTAG_RepositoryInfo);
    res |= ccnb_tagged_putf(rinfo, CCN_DTAG_Version, "%s", "1.1");
    res |= ccnb_tagged_putf(rinfo, CCN_DTAG_Type, "%s", "DATA");
    res |= ccnb_tagged_putf(rinfo, CCN_DTAG_RepositoryVersion, "%s", "2.0");

    res |= ccnb_element_begin(name, CCN_DTAG_GlobalPrefixName); // same structure as Name
    res |= ccnb_element_end(name);
    res |= ccn_name_append_str(name, "parc.com");
    res |= ccn_name_append_str(name, "csl");
    res |= ccn_name_append_str(name, "ccn");
    res |= ccn_name_append_str(name, "Repos");
    res |= ccn_charbuf_append_charbuf(rinfo, name);
    
    name->length = 0;
    res |= ccnb_element_begin(name, CCN_DTAG_LocalName);
    res |= ccnb_element_end(name);
    res |= ccn_name_append_str(name, "Repository");
    res |= ccn_charbuf_append_charbuf(rinfo, name);

    res |= ccnb_element_end(rinfo); // CCN_DTAG_RepositoryInfo
    ccn_charbuf_destroy(&name);
    return (res);
}
