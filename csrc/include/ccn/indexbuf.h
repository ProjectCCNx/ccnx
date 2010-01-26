/**
 * @file ccn/indexbuf.h
 * 
 * Expandable buffer of non-negative values.
 *
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

#ifndef CCN_INDEXBUF_DEFINED
#define CCN_INDEXBUF_DEFINED

#include <stddef.h>

struct ccn_indexbuf {
    size_t n;
    size_t limit;
    size_t *buf;
};

struct ccn_indexbuf *ccn_indexbuf_create(void);
void ccn_indexbuf_destroy(struct ccn_indexbuf **cbp);
size_t *ccn_indexbuf_reserve(struct ccn_indexbuf *c, size_t n);
int ccn_indexbuf_append(struct ccn_indexbuf *c, const size_t *p, size_t n);
int ccn_indexbuf_append_element(struct ccn_indexbuf *c, size_t v);
int ccn_indexbuf_member(struct ccn_indexbuf *x, size_t val);
void ccn_indexbuf_remove_element(struct ccn_indexbuf *x, size_t val);
int ccn_indexbuf_set_insert(struct ccn_indexbuf *x, size_t val);
int ccn_indexbuf_remove_first_match(struct ccn_indexbuf *x, size_t val);
void ccn_indexbuf_move_to_end(struct ccn_indexbuf *x, size_t val);
void ccn_indexbuf_move_to_front(struct ccn_indexbuf *x, size_t val);

#endif
