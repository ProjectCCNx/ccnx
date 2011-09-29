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
#include <ccn/hashtb.h>

#include "ccnr_private.h"

void r_store_init(struct ccnr_handle *h);
int r_store_final(struct ccnr_handle *h, int stable);
void r_store_set_content_timer(struct ccnr_handle *h,struct content_entry *content,struct ccn_parsed_ContentObject *pco);
void r_store_mark_stale(struct ccnr_handle *h,struct content_entry *content);
struct content_entry *r_store_next_child_at_level(struct ccnr_handle *h,struct content_entry *content,int level);
struct content_entry *r_store_content_next(struct ccnr_handle *h,struct content_entry *content);
int r_store_content_matches_interest_prefix(struct ccnr_handle *h,struct content_entry *content,const unsigned char *interest_msg, size_t interest_size);
struct content_entry *r_store_find_first_match_candidate(struct ccnr_handle *h,const unsigned char *interest_msg,const struct ccn_parsed_interest *pi);
ccnr_cookie r_store_enroll_content(struct ccnr_handle *h,struct content_entry *content);
struct content_entry *r_store_content_from_accession(struct ccnr_handle *h, ccnr_accession accession);
struct content_entry *r_store_content_from_cookie(struct ccnr_handle *h, ccnr_cookie cookie);

struct content_entry *r_store_lookup(struct ccnr_handle *h, const unsigned char *msg, const struct ccn_parsed_interest *pi, struct ccn_indexbuf *comps);
struct content_entry *r_store_lookup_ccnb(struct ccnr_handle *h, const unsigned char *namish, size_t size);
int r_store_content_field_access(struct ccnr_handle *h, struct content_entry *content, enum ccn_dtag dtag, const unsigned char **bufp, size_t *sizep);
void r_store_send_content(struct ccnr_handle *h, struct fdholder *fdholder, struct content_entry *content);
int r_store_name_append_components(struct ccn_charbuf *dst, struct ccnr_handle *h, struct content_entry *content, int skip, int count);
int r_store_content_flags(struct content_entry *content);
int r_store_content_change_flags(struct content_entry *content, int set, int clear);
int r_store_commit_content(struct ccnr_handle *h, struct content_entry *content);
void r_store_forget_content(struct ccnr_handle *h, struct content_entry **pentry);
void ccnr_debug_content(struct ccnr_handle *h, int lineno, const char *msg,
                        struct fdholder *fdholder,
                        struct content_entry *content);
int r_store_set_accession_from_offset(struct ccnr_handle *h, struct content_entry *content, struct fdholder *fdholder, off_t offset);
int r_store_content_trim(struct ccnr_handle *h, struct content_entry *content);
void r_store_trim(struct ccnr_handle *h, unsigned long limit);
ccnr_cookie r_store_content_cookie(struct ccnr_handle *h, struct content_entry *content);
ccnr_accession r_store_content_accession(struct ccnr_handle *h, struct content_entry *content);
const unsigned char *r_store_content_base(struct ccnr_handle *h, struct content_entry *content);
size_t r_store_content_size(struct ccnr_handle *h, struct content_entry *content);
void r_store_index_needs_cleaning(struct ccnr_handle *h);
struct ccn_charbuf *r_store_content_flatname(struct ccnr_handle *h, struct content_entry *content);
#endif
