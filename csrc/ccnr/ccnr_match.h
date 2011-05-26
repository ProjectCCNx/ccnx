/**
 * @file ccnr_match.h
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
 
#ifndef CCNR_MATCH_DEFINED
#define CCNR_MATCH_DEFINED

#include <ccn/ccn.h>

#include "ccnr_private.h"

void r_match_consume_interest(struct ccnr_handle *h,struct propagating_entry *pe);

int r_match_match_interests(struct ccnr_handle *h,struct content_entry *content,struct ccn_parsed_ContentObject *pc,struct fdholder *fdholder,struct fdholder *from_face);
int r_match_consume_matching_interests(struct ccnr_handle *h,struct nameprefix_entry *npe,struct content_entry *content,struct ccn_parsed_ContentObject *pc,struct fdholder *fdholder);

#endif
