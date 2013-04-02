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
#include <ccn/ccn.h>
#include <openssl/bn.h>
#include <openssl/evp.h>
#include <openssl/sha.h>
#include <openssl/rand.h>
#include <openssl/aes.h>

#include <ccn/keystore.h>
#include <ccn/aeskeystoreasn1.h>

#define AES_KEYSTORE_VERSION 1L
#define IV_SIZE 16

static unsigned char *create_derived_key(unsigned char *key, unsigned int keylength, unsigned char *salt, 
			unsigned int saltlen);

struct ccn_aes_keystore {
    int initialized;
    char *digest_algorithm;
};

struct ccn_aes_keystore *
ccn_aes_keystore_create(void)
{
    struct ccn_aes_keystore *res = calloc(1, sizeof(*res));
    return (res);
}

void
ccn_aes_keystore_destroy(struct ccn_aes_keystore **p)
{
}

int
ccn_aes_keystore_init(struct ccn_aes_keystore *p, char *filename, char *password)
{
    return (0);
}

const struct ccn_pkey *
ccn_aes_keystore_secret_key(struct ccn_aes_keystore *p)
{
    if (0 == p->initialized)
        return (NULL);

    return NULL;
}

ccn_aes_keystore_digest_algorithm(struct ccn_aes_keystore *p)
{
    if (0 == p->initialized)
        return (NULL);
    return (p->digest_algorithm);
}

/**
 * Create an AES keystore file
 * @param filename  the name of the keystore file to be created.
 * @param password  the import/export password for the keystore.
 * @param key       the key to encrypt in the keystore
 * @param keylength the number of bits in the input secret key
 * @param fullname  0 if filename is only a header & needs the digest appended to it,
 *		    1, if fullname
 * @returns 0 on success, -1 on failure
 */
int
ccn_aes_keystore_file_init(char *filename, char *password, unsigned char *key,
                       int keylength, int fullname)
{
    FILE *fp = NULL;
    int fd = -1;
    int res;
    int i;
    int ans = -1;
    EVP_MD_CTX *mdctx;
    const EVP_MD *md;
    unsigned char md_value[keylength/8];
    unsigned int md_len = keylength/8;
    char *built_filename = NULL;
    int start_slot;
    AESKeystore_info *keystore = NULL;
    int nid;
    unsigned char *aes_key = NULL;
    unsigned char *mac_key = NULL;
    EVP_CIPHER_CTX ctx;
    unsigned char *encrypted_key = NULL;
    unsigned char *p;
    int ekl = IV_SIZE + keylength/8 + SHA256_DIGEST_LENGTH + AES_BLOCK_SIZE;
    int encrypt_length;

    OpenSSL_add_all_algorithms();
    
    if (!fullname) {
 	/* Create the filename based on SHA256 digest of the key */
	memcpy(md_value, key, keylength/8);
        built_filename = malloc(strlen(filename) + 2 + ((keylength/8) * 2));
        md = EVP_get_digestbyname(CCN_SIGNING_DEFAULT_DIGEST_ALGORITHM);   
        mdctx = EVP_MD_CTX_create();
        EVP_DigestInit(mdctx, md);
        EVP_DigestUpdate(mdctx, md_value, md_len);
        EVP_DigestFinal(mdctx, md_value, &md_len);
        strcpy(built_filename, filename);
	strcat(built_filename, "-");
        start_slot = strlen(built_filename);
        for (i = 0; i < md_len; i++) {
            snprintf(&built_filename[start_slot], 3, "%02X", md_value[i]);
	    start_slot += 2;
        }
        filename = built_filename;
    }
    
    fd = open(filename, O_CREAT | O_WRONLY | O_TRUNC, 0600);
    if (fd == -1)
        goto Bail;
    fp = fdopen(fd, "wb");
    if (fp == NULL)
        goto Bail;

    aes_key = create_derived_key(password, strlen(password), "\0", 1);
    mac_key = create_derived_key(password, strlen(password), "\1", 1);
    
    encrypted_key = malloc(ekl);
    if (!encrypted_key)
	goto Bail;
    /* RAND_bytes(encrypted_key, IV_SIZE); */
for (i = 0; i < IV_SIZE; i++)
encrypted_key[i] = i;
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
    if (fp != NULL)
	fclose(fp);
    if (built_filename)
        free(built_filename);
    if (encrypted_key)
	free(encrypted_key);
    if (keystore) {
	AESKeystore_info_free(keystore);
    }
    if (fd != -1)
        close(fd);
    return (ans);
}

static unsigned char *create_derived_key(unsigned char *key, unsigned int keylength, unsigned char *salt, 
			unsigned int saltlen) 
{
    unsigned char *ans = malloc(SHA256_DIGEST_LENGTH);
    HMAC(EVP_sha256(), key, keylength, salt, saltlen, ans, NULL);
    return ans;
}
