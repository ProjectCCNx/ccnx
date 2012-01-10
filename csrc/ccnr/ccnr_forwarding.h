/**
 * @file ccnr_forwarding.h
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
 
#ifndef CCNR_FORWARDING_DEFINED
#define CCNR_FORWARDING_DEFINED

#include <ccn/ccn.h>
#include <ccn/hashtb.h>

#include "ccnr_private.h"

int r_fwd_nameprefix_longest_match(struct ccnr_handle *h,const unsigned char *msg,struct ccn_indexbuf *comps,int ncomps);
int r_fwd_is_duplicate_flooded(struct ccnr_handle *h,unsigned char *msg,struct ccn_parsed_interest *pi,unsigned filedesc);
int r_fwd_propagate_interest(struct ccnr_handle *h,struct fdholder *fdholder,unsigned char *msg,struct ccn_parsed_interest *pi,struct nameprefix_entry *npe);
void r_fwd_append_plain_nonce(struct ccnr_handle *h,struct fdholder *fdholder,struct ccn_charbuf *cb);
void r_fwd_append_debug_nonce(struct ccnr_handle *h,struct fdholder *fdholder,struct ccn_charbuf *cb);
void r_fwd_update_forward_to(struct ccnr_handle *h,struct nameprefix_entry *npe);
void r_fwd_reg_uri_list(struct ccnr_handle *h,struct ccn_charbuf *uris,unsigned filedesc,int flags,int expires);
int r_fwd_reg_uri(struct ccnr_handle *h,const char *uri,unsigned filedesc,int flags,int expires);
int r_fwd_nameprefix_seek(struct ccnr_handle *h,struct hashtb_enumerator *e,const unsigned char *msg,struct ccn_indexbuf *comps,int ncomps);
void r_fwd_age_forwarding_needed(struct ccnr_handle *h);
void r_fwd_reap_needed(struct ccnr_handle *h,int init_delay_usec);
void r_fwd_adjust_npe_predicted_response(struct ccnr_handle *h,struct nameprefix_entry *npe,int up);
void r_fwd_finalize_propagating(struct hashtb_enumerator *e);
void r_fwd_finalize_nameprefix(struct hashtb_enumerator *e);

#endif
