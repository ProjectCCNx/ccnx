/**
 * @file ccn/header.h
 *
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009 Palo Alto Research Center, Inc.
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

#ifndef CCN_HEADER_DEFINED
#define CCN_HEADER_DEFINED

#include <stddef.h>
#include <ccn/charbuf.h>

struct ccn_header {
    uintmax_t start;
    uintmax_t count;
    uintmax_t block_size;
    uintmax_t length;
    struct ccn_charbuf *root_digest;
    struct ccn_charbuf *content_digest;
};

struct ccn_header *ccn_header_parse(const unsigned char *, size_t);

void ccn_header_destroy(struct ccn_header **);

int ccnb_append_header(struct ccn_charbuf *, const struct ccn_header *);

struct ccn_header *ccn_get_header(struct ccn *, struct ccn_charbuf *, int);

#endif
