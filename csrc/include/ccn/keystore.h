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
 * Copyright (C) 2009,2013 Palo Alto Research Center, Inc.
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
#include <ccn/ccn.h>
#include <ccn/charbuf.h>

#define CCN_SECRET_KEY_LENGTH 256      /* We only support HMAC-SHA256 right now */
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

/*
 * Shared data across keystores
 */
typedef struct keystore_header_st {
    int initialized;
    ssize_t (*digest_length_func)(struct ccn_keystore *p);
    const unsigned char *(*digest_func)(struct ccn_keystore *p);
    const struct ccn_pkey *(*key_func)(struct ccn_keystore *p);
    const char *(*digest_algorithm_func)(struct ccn_keystore *p);
    void (*destroy_func)(struct ccn_keystore **p);
} keystore_header;

struct ccn_keystore *ccn_keystore_create(void);
void ccn_keystore_destroy(struct ccn_keystore **p);
int ccn_keystore_init(struct ccn_keystore *p, char *name, char *password);
const struct ccn_pkey *ccn_keystore_key(struct ccn_keystore *p);
const struct ccn_pkey *ccn_keystore_public_key(struct ccn_keystore *p);
const char *ccn_keystore_digest_algorithm(struct ccn_keystore *p);
ssize_t ccn_keystore_key_digest_length(struct ccn_keystore *p);
const unsigned char *ccn_keystore_key_digest(struct ccn_keystore *p);
const struct ccn_certificate *ccn_keystore_certificate(struct ccn_keystore *p);
int ccn_keystore_file_init(char *filename, char *password, char *subject, int keylength, int validity_days);
struct ccn_keystore *ccn_aes_keystore_create(void);
int ccn_aes_keystore_init(struct ccn_keystore *p, char *filename, const char *password);
int ccn_aes_keystore_file_init(char *filename, const char *password, unsigned char *key, int keylength);
int ccn_aes_keystore_file_init(char *filename, const char *password, unsigned char *key, int keylength);
void ccn_create_filename_with_digest_suffix(struct ccn_charbuf *filename, const unsigned char *key, int keylength);
void ccn_create_aes_filename_from_digest(struct ccn_charbuf *filename, const unsigned char *digest, int digest_len);
int ccn_create_aes_filename_from_key(struct ccn_charbuf *filename, unsigned char *key, int keylength);
void ccn_generate_symmetric_key(unsigned char *keybuf, int keylength);
struct ccn_charbuf *ccn_get_aes_keystore_path(struct ccn *h, char *suffix);

/* Deprecated functions after 0.7.1 */
/* Deprecated in favor of ccn_keystore_key */
const struct ccn_pkey *ccn_keystore_private_key(struct ccn_keystore *p) DEPRECATED;
/* Deprecated in favor of ccn_keystore_key_digest_length */
ssize_t ccn_keystore_public_key_digest_length(struct ccn_keystore *p) DEPRECATED;
/* Deprecated in favor of ccn_keystore_key_digest */
const unsigned char *ccn_keystore_public_key_digest(struct ccn_keystore *p) DEPRECATED;
#endif
