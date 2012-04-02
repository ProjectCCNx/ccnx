/**
 * @file ccn/signing.h
 * 
 * Message signing interface.
 * This is a veneer so that the ccn code can use various underlying
 * implementations of the signature functions without muss and fuss.
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

#ifndef CCN_SIGNING_DEFINED
#define CCN_SIGNING_DEFINED

#include <stddef.h>
#include <ccn/charbuf.h>

/*
 * opaque type for signing context
 */
struct ccn_sigc;

/*
 * opaque type for public and private keys
 */
struct ccn_pkey;

/*
 * opaque type for signature
 */
struct ccn_signature;

/*
 * see ccn/ccn.h
 */
struct ccn_parsed_ContentObject;

struct ccn_sigc *ccn_sigc_create(void);
int ccn_sigc_init(struct ccn_sigc *ctx, const char *digest, const struct ccn_pkey *priv_key);
void ccn_sigc_destroy(struct ccn_sigc **);
int ccn_sigc_update(struct ccn_sigc *ctx, const void *data, size_t size);
int ccn_sigc_final(struct ccn_sigc *ctx, struct ccn_signature *signature, size_t *size, const struct ccn_pkey *priv_key);
size_t ccn_sigc_signature_max_size(struct ccn_sigc *ctx, const struct ccn_pkey *priv_key);
int ccn_verify_signature(const unsigned char *msg, size_t size, const struct ccn_parsed_ContentObject *co,
                         const struct ccn_pkey *verification_pubkey);
struct ccn_pkey *ccn_d2i_pubkey(const unsigned char *p, size_t size);
void ccn_pubkey_free(struct ccn_pkey *i_pubkey); /* use for result of ccn_d2i_pubkey */
size_t ccn_pubkey_size(const struct ccn_pkey *i_pubkey);

/*
 * ccn_append_pubkey_blob: append a ccnb-encoded blob of the external
 * public key, given the internal form
 * Returns -1 for error
 */
int ccn_append_pubkey_blob(struct ccn_charbuf *c, const struct ccn_pkey *i_pubkey);

#endif
