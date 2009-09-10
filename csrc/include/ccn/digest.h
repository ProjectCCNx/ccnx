/**
 * @file ccn/digest.h
 * 
 * Message digest interface.
 *
 * This is a veneer so that the ccn code can use various underlying
 * implementations of the message digest functions without muss and fuss.
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

#ifndef CCN_DIGEST_DEFINED
#define CCN_DIGEST_DEFINED

#include <stddef.h>

struct ccn_digest;

/* These ids are not meant to be stable across versions */
enum ccn_digest_id {
    CCN_DIGEST_DEFAULT,
    CCN_DIGEST_SHA1,
    CCN_DIGEST_SHA224,
    CCN_DIGEST_SHA256, /* This is our current favorite */
    CCN_DIGEST_SHA384,
    CCN_DIGEST_SHA512
};

struct ccn_digest *ccn_digest_create(enum ccn_digest_id);
void ccn_digest_destroy(struct ccn_digest **);
enum ccn_digest_id ccn_digest_getid(struct ccn_digest *);
size_t ccn_digest_size(struct ccn_digest *);
void ccn_digest_init(struct ccn_digest *);
/* return codes are negative for errors */
int ccn_digest_update(struct ccn_digest *, const void *, size_t);
int ccn_digest_final(struct ccn_digest *, unsigned char *, size_t);

#endif
