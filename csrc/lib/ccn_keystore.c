/**
 * @file ccn_keystore.c
 * @brief Support for keystore access.
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
#include <stdio.h>
#include <stdlib.h>
#include <openssl/pkcs12.h>
#include <openssl/sha.h>

#include <ccn/keystore.h>

struct ccn_keystore {
    int initialized;
    EVP_PKEY *private_key;
    EVP_PKEY *public_key;
    X509 *certificate;
    ssize_t pubkey_digest_length;
    unsigned char pubkey_digest[SHA256_DIGEST_LENGTH];
};

struct ccn_keystore *
ccn_keystore_create(void)
{
    struct ccn_keystore *res = calloc(1, sizeof(*res));
    return (res);
}

void
ccn_keystore_destroy(struct ccn_keystore **p)
{
    if (*p != NULL) {
        if ((*p)->private_key != NULL)
            EVP_PKEY_free((*p)->private_key);
        if ((*p)->public_key != NULL)
            EVP_PKEY_free((*p)->public_key);
        if ((*p)->certificate != NULL)
            X509_free((*p)->certificate);
        free(*p);
        *p = NULL;
    }
}

int
ccn_keystore_init(struct ccn_keystore *p, char *name, char *password)
{
    FILE *fp;
    PKCS12 *keystore;
    int res;

    OpenSSL_add_all_algorithms();
    fp = fopen(name, "rb");
    if (fp == NULL)
        return (-1);

    keystore = d2i_PKCS12_fp(fp, NULL);
    fclose(fp);
    if (keystore == NULL)
        return (-1);

    res = PKCS12_parse(keystore, password, &(p->private_key), &(p->certificate), NULL);
    PKCS12_free(keystore);
    if (res == 0) {
        return (-1);
    }
    p->public_key = X509_get_pubkey(p->certificate);
    /* cache the public key digest to avoid work later */
    if (1 != ASN1_item_digest(ASN1_ITEM_rptr(X509_PUBKEY), EVP_sha256(),
                              X509_get_X509_PUBKEY(p->certificate),
                              p->pubkey_digest, NULL)) return (-1);
    p->pubkey_digest_length = SHA256_DIGEST_LENGTH;
    p->initialized = 1;
    return (0);
}

const struct ccn_pkey *
ccn_keystore_private_key(struct ccn_keystore *p)
{
    if (0 == p->initialized)
        return (NULL);

    return ((const struct ccn_pkey *)(p->private_key));
}

const struct ccn_pkey *
ccn_keystore_public_key(struct ccn_keystore *p)
{
    if (0 == p->initialized)
        return (NULL);

    return ((const struct ccn_pkey *)(p->public_key));
}

ssize_t
ccn_keystore_public_key_digest_length(struct ccn_keystore *p)
{
    return ((0 == p->initialized) ? -1 : p->pubkey_digest_length);
}

const unsigned char *
ccn_keystore_public_key_digest(struct ccn_keystore *p)
{
    if (0 == p->initialized)
        return (NULL);
    return (p->pubkey_digest);
}

const struct ccn_certificate *
ccn_keystore_certificate(struct ccn_keystore *p)
{
    if (0 == p->initialized)
        return (NULL);

    return ((const void *)(p->certificate));
}
