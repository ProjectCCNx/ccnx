/**
 * @file ccn_aes_keystore.c
 * @brief Support for aes (symmetric) keystore access.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
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
#include <unistd.h>
#include <string.h>
#include <ctype.h>
#include <fcntl.h>
#include <errno.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/digest.h>
#include <openssl/bn.h>
#include <openssl/evp.h>
#include <openssl/sha.h>
#include <openssl/rand.h>
#include <openssl/aes.h>
#include <openssl/hmac.h>

#include <ccn/keystore.h>
#include <ccn/key.h>
#include <ccn/aeskeystoreasn1.h>

#define AES_KEYSTORE_VERSION 1L
#define IV_SIZE 16

static void create_filename_with_digest_suffix(struct ccn_charbuf *filename, unsigned char *digest, int digest_len);
static unsigned char *create_derived_key(char *key, unsigned int keylength, unsigned char *salt, 
			unsigned int saltlen);

struct ccn_aes_keystore {
    int initialized;
    ccn_symmetric_key *key;
};

int
ccn_aes_keystore_init(struct ccn_keystore **keystore, char *filename, char *password)
{
    FILE *fp = NULL;
    AESKeystore_info *ki = NULL;
    int ans = -1;
    struct ccn_aes_keystore *ks;
    int version;
    char oidstr[80];
    unsigned char *aes_key;
    unsigned char *mac_key;
    unsigned char check[SHA256_DIGEST_LENGTH];
    int check_start;
    EVP_CIPHER_CTX ctx;
    int length;
    int final_length;

    OpenSSL_add_all_algorithms();

    fp = fopen(filename, "rb");
    if (fp == NULL)
        goto Bail;

    ki = d2i_AESKeystore_fp(fp, NULL);
    fclose(fp);
    if (ki == NULL)
        goto Bail;
    version = ASN1_INTEGER_get(ki->version);
    if (version != AES_KEYSTORE_VERSION)
	goto Bail;
    OBJ_obj2txt(oidstr, sizeof(oidstr), ki->algorithm_oid, 0);
    if (strcasecmp(oidstr, CCN_SIGNING_DEFAULT_DIGEST_ALGORITHM))
        goto Bail;
    if (ki->encrypted_key->length < IV_SIZE + (SHA256_DIGEST_LENGTH * 2) + AES_BLOCK_SIZE)
	goto Bail;
    
    aes_key = create_derived_key(password, strlen(password), (unsigned char *)"\0", 1);
    mac_key = create_derived_key(password, strlen(password), (unsigned char *)"\1", 1);
    
    check_start = ki->encrypted_key->length - SHA256_DIGEST_LENGTH;
    HMAC(EVP_sha256(), mac_key, SHA256_DIGEST_LENGTH, ki->encrypted_key->data, check_start, check, NULL);
    if (memcmp(&ki->encrypted_key->data[check_start], check, SHA256_DIGEST_LENGTH))
	goto Bail;
    (*keystore) = (struct ccn_keystore *)malloc(sizeof(struct ccn_aes_keystore));
    ks = (struct ccn_aes_keystore *)(*keystore);
    if (!keystore)
	goto Bail;
    ks->key = (ccn_symmetric_key *)malloc(sizeof(ccn_symmetric_key));
    if (!ks->key)
	goto Bail;
    ks->key->key = malloc(sizeof(CCN_SECRET_KEY_LENGTH/8));
    if (!ks->key->key)
	goto Bail;
    EVP_CIPHER_CTX_init(&ctx);
    if (!EVP_DecryptInit(&ctx, EVP_aes_256_cbc(), aes_key, ki->encrypted_key->data))
	goto Bail;
    if (!EVP_DecryptUpdate(&ctx, ks->key->key, &length, &ki->encrypted_key->data[IV_SIZE], 
		ki->encrypted_key->length - IV_SIZE - SHA256_DIGEST_LENGTH)) {
	goto Bail;
    if (!EVP_DecryptFinal(&ctx, ks->key->key + length, &final_length))
	goto Bail;
    }
    ans = 0;
    goto out;

Bail:
    ans = -1;
    ccn_aes_keystore_destroy((struct ccn_keystore **)&keystore);
out:
    return (ans);
}

void
ccn_aes_keystore_destroy(struct ccn_keystore **p)
{
    struct ccn_aes_keystore *ks = (struct ccn_aes_keystore *) (*p);
    if (ks != NULL) {
        if (ks->key != NULL)
	   free(ks->key);
        free(ks);
    }
}

/**
 * Create an AES keystore file
 * @param filename  the name of the keystore file to be created.
 * @param password  the import/export password for the keystore.
 * @param key       the key to encrypt in the keystore
 * @returns 0 on success, -1 on failure
 */
int
ccn_aes_keystore_file_init(char *filename, char *password, unsigned char *key, int keylength)
{
    FILE *fp = NULL;
    int fd = -1;
    int ans = -1;
    AESKeystore_info *keystore = NULL;
    int nid;
    unsigned char *aes_key = NULL;
    unsigned char *mac_key = NULL;
    EVP_CIPHER_CTX ctx;
    unsigned char *encrypted_key = NULL;
    unsigned char *p;
    int ekl = IV_SIZE + keylength/8 + SHA256_DIGEST_LENGTH + AES_BLOCK_SIZE;
    int encrypt_length;
    struct ccn_digest *digest = NULL;

    OpenSSL_add_all_algorithms();
    
    fd = open(filename, O_CREAT | O_WRONLY | O_TRUNC, 0600);
    if (fd == -1)
        goto Bail;
    fp = fdopen(fd, "wb");
    if (fp == NULL)
        goto Bail;

    aes_key = create_derived_key(password, strlen(password), (unsigned char *)"\0", 1);
    mac_key = create_derived_key(password, strlen(password), (unsigned char *)"\1", 1);
    
    encrypted_key = malloc(ekl);
    if (!encrypted_key)
	goto Bail;
    RAND_bytes(encrypted_key, IV_SIZE);
    EVP_CIPHER_CTX_init(&ctx);
    if (!EVP_EncryptInit(&ctx, EVP_aes_256_cbc(), aes_key, encrypted_key))
	goto Bail;
    p = encrypted_key + IV_SIZE;
    if (!EVP_EncryptUpdate(&ctx, p, &encrypt_length, key, keylength/8))
	goto Bail; 
    p += encrypt_length;
    if (!EVP_EncryptFinal(&ctx, p, &encrypt_length))
        goto Bail;
    p += encrypt_length;
    HMAC(EVP_sha256(), mac_key, SHA256_DIGEST_LENGTH, encrypted_key, p - encrypted_key, p, NULL);

    if (!(keystore = AESKeystore_info_new()))
	goto Bail;
    if (!(keystore->version = ASN1_INTEGER_new()))
	goto Bail;
    if (!ASN1_INTEGER_set(keystore->version, AES_KEYSTORE_VERSION))
	goto Bail;
    keystore->algorithm_oid = OBJ_txt2obj(CCN_SIGNING_DEFAULT_DIGEST_ALGORITHM, 0);
    nid = OBJ_obj2nid(keystore->algorithm_oid);
    if (nid == NID_undef)
	goto Bail;	// Shouldn't happen now but could later if we support more algorithms
    if (!ASN1_OCTET_STRING_set(keystore->encrypted_key, encrypted_key, ekl))
        goto Bail;
    i2d_AESKeystore_fp(fp, keystore);
    ans = 0;
    goto cleanup;
    
Bail:
    ans = -1;
cleanup:
    ccn_digest_destroy(&digest);
    if (fp != NULL)
	fclose(fp);
    if (encrypted_key)
	free(encrypted_key);
    if (keystore) {
	AESKeystore_info_free(keystore);
    }
    if (fd != -1)
        close(fd);
    return (ans);
}

struct ccn_pkey *
get_key_from_aes_keystore(struct ccn_keystore *ks) 
{
    struct ccn_aes_keystore *ak;
    ak = (struct ccn_aes_keystore *)ks;
    return (struct ccn_pkey *)ak->key;
}

/* Create the filename based on SHA256 digest of the key */
int 
create_aes_filename_from_key(struct ccn_charbuf *filename, unsigned char *key, int keylength) 
{
    unsigned char md_value[keylength/8];
    unsigned int md_len = keylength/8;
    int res = 0;
    struct ccn_digest *digest;

    memcpy(md_value, key, keylength/8);
    digest = ccn_digest_create(CCN_DIGEST_SHA256);
    ccn_digest_init(digest);
    res |= ccn_digest_update(digest, md_value, md_len);
    res |= ccn_digest_final(digest, md_value, md_len);
    if (res < 0) 
        return 0;
    create_filename_with_digest_suffix(filename, md_value, md_len);
    return 1;
}

static void
create_filename_with_digest_suffix(struct ccn_charbuf *filename, unsigned char *digest, int digest_len)
{
    int i;

    ccn_charbuf_append_string(filename, "-");
    for (i = 0; i < digest_len; i++) {
        ccn_charbuf_putf(filename, "%02X", digest[i]);
    }
}

static unsigned char *create_derived_key(char *key, unsigned int keylength, unsigned char *salt, 
			unsigned int saltlen) 
{
    unsigned char *ans = malloc(SHA256_DIGEST_LENGTH);
    HMAC(EVP_sha256(), key, keylength, salt, saltlen, ans, NULL);
    return ans;
}
