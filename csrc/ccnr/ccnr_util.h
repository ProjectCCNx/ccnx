/**
 * @file ccnr_util.h
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
 
#ifndef CCNR_UTIL_DEFINED
#define CCNR_UTIL_DEFINED

#include "ccnr_private.h"

void r_util_gettime(const struct ccn_gettime *self,struct ccn_timeval *result);
int r_util_timecmp(long secA, unsigned usecA, long secB, unsigned usecB);
void r_util_reseed(struct ccnr_handle *h);
void r_util_indexbuf_release(struct ccnr_handle *h,struct ccn_indexbuf *c);
struct ccn_indexbuf *r_util_indexbuf_obtain(struct ccnr_handle *h);
void r_util_charbuf_release(struct ccnr_handle *h,struct ccn_charbuf *c);
struct ccn_charbuf *r_util_charbuf_obtain(struct ccnr_handle *h);
intmax_t r_util_segment_from_component(const unsigned char *ccnb, size_t start, size_t stop);
int r_util_name_comp_compare(const unsigned char *data, const struct ccn_indexbuf *indexbuf, unsigned int i, const void *val, size_t length);
#endif
