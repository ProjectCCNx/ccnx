/**
 * @file ccn/seqwriter.h
 * @brief
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2010, 2013 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */
 
#ifndef CCN_SEQWRITER_DEFINED
#define CCN_SEQWRITER_DEFINED

#include <stddef.h>
struct ccn_seqwriter;
struct ccn;
struct ccn_charbuf;

struct ccn_seqwriter *ccn_seqw_create(struct ccn *h, struct ccn_charbuf *name);
int ccn_seqw_possible_interest(struct ccn_seqwriter *w);
int ccn_seqw_batch_start(struct ccn_seqwriter *w);
int ccn_seqw_get_name(struct ccn_seqwriter *w, struct ccn_charbuf *nv);
int ccn_seqw_write(struct ccn_seqwriter *w, const void *buf, size_t size);
int ccn_seqw_batch_end(struct ccn_seqwriter *w);
int ccn_seqw_set_block_limits(struct ccn_seqwriter *w, int l, int h);
int ccn_seqw_set_freshness(struct ccn_seqwriter *w, int freshness);
int ccn_seqw_set_key_digest(struct ccn_seqwriter *w, const unsigned char *key, int keylen);
int ccn_seqw_close(struct ccn_seqwriter *w);

#endif
