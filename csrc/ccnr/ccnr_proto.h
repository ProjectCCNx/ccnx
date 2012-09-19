/**
 * @file ccnr_proto.h
 * 
 * Part of ccnr - CCNx Repository Daemon.
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
 
#ifndef CCNR_PROTO_DEFINED
#define CCNR_PROTO_DEFINED

#include "ccnr_private.h"

#define REPO_SW "\xC1.R.sw"
#define REPO_SWC "\xC1.R.sw-c"
#define REPO_AF "\xC1.R.af"
#define NAME_BE "\xC1.E.be"

struct ccnr_parsed_policy {
    unsigned char version[7];
    int policy_version_offset;
    int local_name_offset;
    int global_prefix_offset;
    struct ccn_indexbuf *namespaces;
    struct ccn_charbuf *store;
};

#define CCNR_PIPELINE 4
struct ccnr_expect_content {
    struct ccnr_handle *ccnr;
    int tries; /** counter so we can give up eventually */
    int done;
    ccnr_cookie keyfetch;
    intmax_t outstanding[CCNR_PIPELINE];
    intmax_t final;
    ccn_handler expect_complete;
};


void r_proto_init(struct ccnr_handle *ccnr);
void r_proto_uri_listen(struct ccnr_handle *ccnr, struct ccn *ccn, const char *uri,
                        ccn_handler p, intptr_t intdata);
int r_proto_append_repo_info(struct ccnr_handle *ccnr,
                             struct ccn_charbuf *rinfo,
                             struct ccn_charbuf *names,
                             const char *info);
int r_proto_policy_append_basic(struct ccnr_handle *ccnr,
                                struct ccn_charbuf *policy,
                                const char *version, const char *local_name,
                                const char *global_prefix);
int r_proto_policy_append_namespace(struct ccnr_handle *ccnr,
                                    struct ccn_charbuf *policy,
                                    const char *namespace);
enum ccn_upcall_res r_proto_expect_content(struct ccn_closure *selfp,
                                           enum ccn_upcall_kind kind,
                                           struct ccn_upcall_info *info);
int
r_proto_parse_policy(struct ccnr_handle *ccnr, const unsigned char *buf, size_t length,
                     struct ccnr_parsed_policy *pp);
void r_proto_activate_policy(struct ccnr_handle *ccnr, struct ccnr_parsed_policy *pp);
void r_proto_deactivate_policy(struct ccnr_handle *ccnr, struct ccnr_parsed_policy *pp);
int r_proto_initiate_key_fetch(struct ccnr_handle *ccnr,
                               const unsigned char *msg,
                               struct ccn_parsed_ContentObject *pco,
                               int use_link,
                               ccnr_cookie a);
void r_proto_finalize_enum_state(struct hashtb_enumerator *e);
#endif
