/**
 * @file ccn/keystore.h
 *
 * KEYSTORE interface.
 *
 * This is a veneer so that the ccn code can avoid exposure to the
 * underlying keystore implementation types.
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

#ifndef CCN_KEYSTORE_DEFINED
#define CCN_KEYSTORE_DEFINED

#include <stddef.h>

/*
 * opaque type for key storage
 */
struct ccn_keystore;

/*
 * opaque type for public and private keys
 */
struct ccn_pkey;

/*
 * opaque type for (X509) certificates
 */
struct ccn_certificate;

struct ccn_keystore *ccn_keystore_create(void);
void ccn_keystore_destroy(struct ccn_keystore **p);
int ccn_keystore_init(struct ccn_keystore *p, char *name, char *password);
const struct ccn_pkey *ccn_keystore_private_key(struct ccn_keystore *p);
const struct ccn_pkey *ccn_keystore_public_key(struct ccn_keystore *p);
ssize_t ccn_keystore_public_key_digest_length(struct ccn_keystore *p);
const unsigned char *ccn_keystore_public_key_digest(struct ccn_keystore *p);
const struct ccn_certificate *ccn_keystore_certificate(struct ccn_keystore *p);

#endif
