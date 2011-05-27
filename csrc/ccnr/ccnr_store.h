/**
 * @file ccnr_store.h
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
 
#ifndef CCNR_STORE_DEFINED
#define CCNR_STORE_DEFINED

#include <ccn/ccn.h>

#include "ccnr_private.h"

void r_store_set_content_timer(struct ccnr_handle *h,struct content_entry *content,struct ccn_parsed_ContentObject *pco);
void r_store_mark_stale(struct ccnr_handle *h,struct content_entry *content);
struct content_entry *r_store_next_child_at_level(struct ccnr_handle *h,struct content_entry *content,int level);
int r_store_remove_content(struct ccnr_handle *h,struct content_entry *content);
ccn_accession_t r_store_content_skiplist_next(struct ccnr_handle *h,struct content_entry *content);
int r_store_content_matches_interest_prefix(struct ccnr_handle *h,struct content_entry *content,const unsigned char *interest_msg,struct ccn_indexbuf *comps,int prefix_comps);
struct content_entry *r_store_find_first_match_candidate(struct ccnr_handle *h,const unsigned char *interest_msg,const struct ccn_parsed_interest *pi);
void r_store_finalize_content(struct hashtb_enumerator *content_enumerator);
void r_store_content_skiplist_insert(struct ccnr_handle *h,struct content_entry *content);
void r_store_enroll_content(struct ccnr_handle *h,struct content_entry *content);
struct content_entry *r_store_content_from_accession(struct ccnr_handle *h,ccn_accession_t accession);
struct content_entry *r_store_lookup(struct ccnr_handle *h, const unsigned char *msg, const struct ccn_parsed_interest *pi, struct ccn_indexbuf *comps);

#endif
