/**
 * @file ccn_signing.c
 * @brief Support for signing.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009-2010, 2013 Palo Alto Research Center, Inc.
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
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/x509.h>
#include <openssl/hmac.h>
#include <ccn/merklepathasn1.h>
#include <ccn/ccn.h>
#include <ccn/signing.h>
#include <ccn/random.h>
#include <ccn/openssl_ex.h>

struct ccn_sigc {
    EVP_MD_CTX context;
    HMAC_CTX hmac_context;
    void *ctx_to_use;
    int key_len;
    const EVP_MD *md;
    int (*init_func)(void *ctx, const void *key, int len, const EVP_MD *md);
    int (*update_func)(void *ctx, const void *data, size_t count);
    int (*final_func)(void *ctx, unsigned char *sigret, unsigned int *siglen, EVP_PKEY *pkey);
    int (*verify_init_func)(void *ctx, const void *key, int len, const EVP_MD *md);
    int (*verify_final_func)(void *ctx, const unsigned char *sigbuf, unsigned int siglen, EVP_PKEY *pkey);
    void (*cleanup_func)(struct ccn_sigc *ctx);
};

#if !defined(OPENSSL_NO_EC) && !defined(OPENSSL_NO_ECDSA) && defined(NID_ecdsa_with_SHA256)
static int init256(EVP_MD_CTX *ctx)
{ return SHA256_Init(ctx->md_data); }
static int update256(EVP_MD_CTX *ctx,const void *data,size_t count)
{ return SHA256_Update(ctx->md_data,data,count); }
static int final256(EVP_MD_CTX *ctx,unsigned char *md)
{ return SHA256_Final(md,ctx->md_data); }

static const EVP_MD sha256ec_md=
{
    NID_sha256,
    NID_ecdsa_with_SHA256,
    SHA256_DIGEST_LENGTH,
    0,
    init256,
    update256,
    final256,
    NULL,
    NULL,
    EVP_PKEY_ECDSA_method,
    SHA256_CBLOCK,
    sizeof(EVP_MD *)+sizeof(SHA256_CTX),
};
#endif

#if defined(NEED_OPENSSL_1_0_COMPAT)
/*
 * In theory this should allow us to "seamlessly integrate" with OpenSSL 1.0.X (and eventually remove this) 
 */
void *EVP_PKEY_get0(EVP_PKEY *pkey)
{
    return pkey->pkey.ptr;
}

int ccn_PKEY_assign(EVP_PKEY *pkey, int type, char *key) {
    if (EVP_PKEY_assign(pkey, type, key) == 0)
        return (0);
    pkey->type = type; // Override type checking
    return (key != NULL);
}

#endif

int ccn_hmac_init(void *ctx, const void *key, int len, const EVP_MD *md) 
{ 
    HMAC_Init((HMAC_CTX *)ctx, ASN1_STRING_data((ASN1_STRING *)key), len, md);
    return (1);
}

int ccn_evp_sign_init(void *ctx, const void *key, int len, const EVP_MD *md)
{
    return EVP_SignInit_ex((EVP_MD_CTX *)ctx, md, NULL);
}

int ccn_evp_verify_init(void *ctx, const void *key, int len, const EVP_MD *md)
{
    return EVP_VerifyInit((EVP_MD_CTX *)ctx, md);
}

int ccn_hmac_final(void *ctx, unsigned char *sigret, unsigned int *siglen, EVP_PKEY *pkey) 
{
    HMAC_Final((HMAC_CTX *)ctx, sigret, siglen);
    return (1);
}

int ccn_hmac_verify_final(void *ctx, const unsigned char *sigret, unsigned int siglen, 
			EVP_PKEY *pkey) 
{
    unsigned char buf[EVP_MAX_MD_SIZE];
    unsigned int len = siglen;

    HMAC_Final((HMAC_CTX *)ctx, (unsigned char *)buf, &siglen);
    if (memcmp(buf, sigret, len))
	return (0);
    return (1);
}

static void cleanup_evp_ctx(struct ccn_sigc *sigc) {
    EVP_MD_CTX_cleanup(&(sigc->context));
}

static void cleanup_hmac_ctx(struct ccn_sigc *sigc) {
    HMAC_CTX_cleanup(&(sigc->hmac_context));
}

static int
sigc_from_digest_and_pkey(struct ccn_sigc *sigc, const char *digest, const struct ccn_pkey *pkey)
{
    int md_nid;
    int pkey_type;

    /* This encapsulates knowledge that the default digest algorithm for
     * signing is SHA256.  See also CCN_SIGNING_DEFAULT_DIGEST_ALGORITHM
     * in ccn/ccn.h.  We could call OBJ_txt2nid(), but this would be rather
     * inefficient.  If there were a place to stand for overall signing
     * initialization then that would be an appropriate place to do so.
     */
    if (digest == NULL) {
        md_nid = NID_sha256;
    }
    else {
        /* figure out what algorithm the OID represents */
        md_nid = OBJ_txt2nid(digest);
        if (md_nid == NID_undef) {
            fprintf(stderr, "not a DigestAlgorithm I understand right now: %s\n", digest);
            return (0);
        }
    }

#if defined(NEED_OPENSSL_1_0_COMPAT)
    pkey_type = ((EVP_PKEY *)pkey)->type;
#else
    pkey_type = EVP_PKEY_type(((EVP_PKEY *)pkey)->type);
#endif
    if (md_nid == NID_hmac || pkey_type == NID_hmac) {
    	sigc->init_func = (int (*)(void *ctx, const void *, int, const EVP_MD *))ccn_hmac_init;
    	sigc->verify_init_func = (int (*)(void *ctx, const void *, int, const EVP_MD *))ccn_hmac_init;
    	sigc->update_func = (int (*)(void *, const void *, size_t))HMAC_Update;
    	sigc->final_func = ccn_hmac_final;
    	sigc->verify_final_func = ccn_hmac_verify_final;
	sigc->md = EVP_sha256();
	sigc->key_len = 32;
        sigc->cleanup_func = cleanup_hmac_ctx;
        sigc->ctx_to_use = &sigc->hmac_context;
        HMAC_CTX_init(&sigc->hmac_context);
	return (1);
    }

    sigc->init_func = ccn_evp_sign_init;
    sigc->verify_init_func = ccn_evp_verify_init;
    sigc->update_func = (int (*)(void *, const void *, size_t))EVP_DigestUpdate;
    sigc->final_func = (int (*)(void *, unsigned char *, unsigned int *, EVP_PKEY *))EVP_SignFinal;
    sigc->verify_final_func = (int (*)(void *, const unsigned char *, unsigned int, EVP_PKEY *))EVP_VerifyFinal;
    sigc->cleanup_func = cleanup_evp_ctx;
    EVP_MD_CTX_init(&sigc->context);
    sigc->ctx_to_use = &sigc->context;
    switch (pkey_type) {
        case EVP_PKEY_RSA:
        case EVP_PKEY_DSA:
#if !defined(OPENSSL_NO_EC)
        case EVP_PKEY_EC:
#endif
            break;
        default:
            fprintf(stderr, "not a Key type I understand right now: NID %d\n", pkey_type);
            return(0);
    }
    /*
     * In OpenSSL 0.9.8 the digest algorithm and key type determine the
     * digest and signature methods that are in the staticly defined
     * EVP_MD structures, so we need to get the correct predefined one, or
     * create our own.
     * In OpenSSL 1.0.0 the EVP_MD structure allows for the key that is passed
     * in the signature finalization step to determine the signature algorithm
     * applied to the digest, so we would be able to use a single SHA256 context
     * independent of the key type (assuming size compatability).
     * At the point that 1.0.0 or later is the default provider we could simplify
     * this code.   The Java code (OIDLookup.java) uses a much more elaborate
     * set of hash maps to perform a similar function.
     */
    switch (md_nid) {
#ifndef OPENSSL_NO_SHA
        case NID_sha1:      // supported for RSA/DSA/EC key types
            switch (pkey_type) {
                case EVP_PKEY_RSA:
		    sigc->md = EVP_sha1();
                    return(1);
                case EVP_PKEY_DSA:
		    sigc->md = EVP_dss1();
                    return(1);
#if !defined(OPENSSL_NO_EC) && !defined(OPENSSL_NO_ECDSA)
                case EVP_PKEY_EC:
		    sigc->md = EVP_ecdsa();
		    return (1);
#endif
            }
            break;
#endif
        case NID_sha256:    // supported for RSA/EC key types
            if (pkey_type == EVP_PKEY_RSA) {
		sigc->md = EVP_sha256();
                return(1);
	    }
#if !defined(OPENSSL_NO_EC) && !defined(OPENSSL_NO_ECDSA) && defined(NID_ecdsa_with_SHA256)
            else if (pkey_type == EVP_PKEY_EC) {
		sigc->md = &sha256ec_md;
                return(1);
            } /* our own md */
#endif
            break;
        case NID_sha512:     // supported for RSA
            if (pkey_type == EVP_PKEY_RSA)
		sigc->md = EVP_sha512();
                return(1);
        default:
            break;
    }
    fprintf(stderr, "not a Digest+Signature algorithm I understand right now: %s with NID %d\n",
            digest, pkey_type);
    return (0);
}

struct ccn_sigc *
ccn_sigc_create(void)
{
    return (calloc(1, sizeof(struct ccn_sigc)));
}

void
ccn_sigc_destroy(struct ccn_sigc **ctx)
{
    if (*ctx) {
        // XXX - is it OK to call cleanup unconditionally?
        (*(*ctx)->cleanup_func)((*ctx));
        free(*ctx);
        *ctx = NULL;
    }
}

int
ccn_sigc_init(struct ccn_sigc *ctx, const char *digest, const struct ccn_pkey *key)
{
    if (!sigc_from_digest_and_pkey(ctx, digest, key))
	return (-1);
    if (0 == (*ctx->init_func)(ctx->ctx_to_use, EVP_PKEY_get0((EVP_PKEY *)key), ctx->key_len, 
			ctx->md))
        return (-1);
    return (0);
}

int
ccn_sigc_update(struct ccn_sigc *ctx, const void *data, size_t size)
{
    if (0 == (*ctx->update_func)(ctx->ctx_to_use, (unsigned char *)data, size))
        return (-1);
    return (0);
}

int
ccn_sigc_final(struct ccn_sigc *ctx, struct ccn_signature *signature, size_t *size, const struct ccn_pkey *priv_key)
{
    unsigned int sig_size;

    if (0 == (*ctx->final_func)(ctx->ctx_to_use, (unsigned char *)signature, &sig_size, (EVP_PKEY *)priv_key))
        return (-1);
    *size = sig_size;
    return (0);
}

size_t
ccn_sigc_signature_max_size(struct ccn_sigc *ctx, const struct ccn_pkey *key)
{
#if defined(NEED_OPENSSL_1_0_COMPAT)
    if (((EVP_PKEY *)key)->type == EVP_PKEY_HMAC)
	return EVP_MAX_MD_SIZE;
#endif
    return (EVP_PKEY_size((EVP_PKEY *)key));
}

#define is_left(x) (0 == (x & 1))
#define node_lr(x) (x & 1)
#define sibling_of(x) (x ^ 1)
#define parent_of(x) (x >> 1)

int ccn_merkle_root_hash(const unsigned char *msg, size_t size,
                         const struct ccn_parsed_ContentObject *co,
                         const EVP_MD *digest_type,
                         MP_info *merkle_path_info,
                         unsigned char *result, int result_size)
{
    int node = ASN1_INTEGER_get(merkle_path_info->node);
    EVP_MD_CTX digest_context;
    EVP_MD_CTX *digest_contextp = &digest_context;
    size_t data_size;
    unsigned char *input_hash[2] = {NULL, NULL};
    //int hash_count = sk_ASN1_OCTET_STRING_num(merkle_path_info->hashes);
    int hash_index = sk_ASN1_OCTET_STRING_num(merkle_path_info->hashes) - 1;
    //ASN1_OCTET_STRING *sibling_hash;
    int res;
    
    if (result_size != EVP_MD_size(digest_type))
        return -1;

    /*
     * This is the calculation for the node we're starting from
     *
     * The digest type for the leaf node we'll take from the MHT OID
     * We can assume that, since we're using the same digest function, the
     * result size will always be the same.
     */

    EVP_MD_CTX_init(digest_contextp);
    EVP_DigestInit_ex(digest_contextp, digest_type, NULL);
    data_size = co->offset[CCN_PCO_E_Content] - co->offset[CCN_PCO_B_Name];
    res = EVP_DigestUpdate(digest_contextp, msg + co->offset[CCN_PCO_B_Name], data_size);
    res &= EVP_DigestFinal_ex(digest_contextp, result, NULL);
    EVP_MD_CTX_cleanup(digest_contextp);
    if (res != 1)
        return(-1);
    /* input_hash[0, 1] = address of hash for (left,right) node of parent
     */
    while (node != 1) {
        input_hash[node & 1] = result;
        input_hash[(node & 1) ^ 1] = sk_ASN1_OCTET_STRING_value(merkle_path_info->hashes, hash_index)->data;
        if (sk_ASN1_OCTET_STRING_value(merkle_path_info->hashes, hash_index)->length != result_size)
            return (-1);
        hash_index -= 1;
#ifdef DEBUG
        fprintf(stderr, "node[%d].lefthash = ", parent_of(node));
        for (int x = 0; x < result_size; x++) {
            fprintf(stderr, "%02x", input_hash[0][x]);
        }
        fprintf(stderr, "\n");
   
        fprintf(stderr, "node[%d].righthash = ", parent_of(node));
        for (int x = 0; x < result_size; x++) {
            fprintf(stderr, "%02x", input_hash[1][x]);
        }
        fprintf(stderr, "\n");
#endif
        EVP_MD_CTX_init(digest_contextp);
        res = EVP_DigestInit_ex(digest_contextp, digest_type, NULL);
        res &= EVP_DigestUpdate(digest_contextp, input_hash[0], result_size);
        res &= EVP_DigestUpdate(digest_contextp, input_hash[1], result_size);
        res &= EVP_DigestFinal_ex(digest_contextp, result, NULL);
        EVP_MD_CTX_cleanup(digest_contextp);
        if (res != 1)
            return(-1);
        node = parent_of(node);
   
#ifdef DEBUG
        fprintf(stderr, "yielding node[%d] hash = ", node);
        for (int x = 0; x < result_size; x++) {
            fprintf(stderr, "%02x", result[x]);
        }
        fprintf(stderr, "\n");
#endif
    }
    return (0);
}

int ccn_verify_signature(const unsigned char *msg,
                     size_t size,
                     const struct ccn_parsed_ContentObject *co,
                     const struct ccn_pkey *verification_key)
{
    X509_SIG *digest_info = NULL;
    const unsigned char *dd = NULL;
    MP_info *merkle_path_info = NULL;
    unsigned char *root_hash = NULL;
    size_t root_hash_size;

    int res;

    const EVP_MD *merkle_path_digest = NULL;
    
    const unsigned char *signature_bits = NULL;
    size_t signature_bits_size = 0;
    const unsigned char *witness = NULL;
    size_t witness_size = 0;
    const unsigned char *digest_algorithm = NULL;
    size_t digest_algorithm_size;
    struct ccn_sigc *sigc;
    
    EVP_PKEY *pkey = (EVP_PKEY *)verification_key;

    res = ccn_ref_tagged_BLOB(CCN_DTAG_SignatureBits, msg,
                              co->offset[CCN_PCO_B_SignatureBits],
                              co->offset[CCN_PCO_E_SignatureBits],
                              &signature_bits,
                              &signature_bits_size);
    if (res < 0)
        return (-1);

    if (co->offset[CCN_PCO_B_DigestAlgorithm] == co->offset[CCN_PCO_E_DigestAlgorithm]) {
        digest_algorithm = (const unsigned char *)CCN_SIGNING_DEFAULT_DIGEST_ALGORITHM;
    }
    else {
        /* figure out what algorithm the OID represents */
        res = ccn_ref_tagged_string(CCN_DTAG_DigestAlgorithm, msg,
                                  co->offset[CCN_PCO_B_DigestAlgorithm],
                                  co->offset[CCN_PCO_E_DigestAlgorithm],
                                  &digest_algorithm,
                                  &digest_algorithm_size);
        if (res < 0)
            return (-1);
        /* NOTE: since the element closer is a 0, and the element is well formed,
         * the string will be null terminated 
         */
    }
    sigc = ccn_sigc_create();
    if (!sigc_from_digest_and_pkey(sigc, (const char *)digest_algorithm, verification_key)) {
	res = -1;
	goto Bail;
    }
    res = (*sigc->init_func)(sigc->ctx_to_use, EVP_PKEY_get0((EVP_PKEY *)verification_key), 
			sigc->key_len, sigc->md);
    if (!res) {
        res = -1;
	goto Bail;
    }
    if (co->offset[CCN_PCO_B_Witness] != co->offset[CCN_PCO_E_Witness]) {
        /* The witness is a DigestInfo, where the octet-string therein encapsulates
         * a sequence of [integer (origin 1 node#), sequence of [octet-string]]
         * where the inner octet-string is the concatenated hashes on the merkle-path
         */
        res = ccn_ref_tagged_BLOB(CCN_DTAG_Witness, msg,
                                  co->offset[CCN_PCO_B_Witness],
                                  co->offset[CCN_PCO_E_Witness],
                                  &witness,
                                  &witness_size);
        if (res < 0) {
            res = -1;
	    goto Bail;
        }

        digest_info = d2i_X509_SIG(NULL, &witness, witness_size);
        /* digest_info->algor->algorithm->{length, data}
         * digest_info->digest->{length, type, data}
         */
        /* ...2.2 is an MHT w/ SHA256 */
        ASN1_OBJECT *merkle_hash_tree_oid = OBJ_txt2obj("1.2.840.113550.11.1.2.2", 1);
        if (0 != OBJ_cmp(digest_info->algor->algorithm, merkle_hash_tree_oid)) {
            fprintf(stderr, "A witness is present without an MHT OID!\n");
            ASN1_OBJECT_free(merkle_hash_tree_oid);
            res = -1;
	    goto Bail;
        }
        /* we're doing an MHT */
        ASN1_OBJECT_free(merkle_hash_tree_oid);
        merkle_path_digest = EVP_sha256();
        /* DER-encoded in the digest_info's digest ASN.1 octet string is the Merkle path info */
        dd = digest_info->digest->data;
        merkle_path_info = d2i_MP_info(NULL, &dd, digest_info->digest->length);
        X509_SIG_free(digest_info);
#ifdef DEBUG
        int x,h;
        int node = ASN1_INTEGER_get(merkle_path_info->node);
        int hash_count = sk_ASN1_OCTET_STRING_num(merkle_path_info->hashes);
        ASN1_OCTET_STRING *hash;
        fprintf(stderr, "A witness is present with an MHT OID\n");
        fprintf(stderr, "This is node %d, with %d hashes\n", node, hash_count);
        for (h = 0; h < hash_count; h++) {
            hash = sk_ASN1_OCTET_STRING_value(merkle_path_info->hashes, h);
            fprintf(stderr, "     hashes[%d] len = %d data = ", h, hash->length);
            for (x = 0; x < hash->length; x++) {
                fprintf(stderr, "%02x", hash->data[x]);
            }
            fprintf(stderr, "\n");
        }
#endif
        /* In the MHT signature case, we signed/verify the root hash */
        root_hash_size = EVP_MD_size(merkle_path_digest);
        root_hash = calloc(1, root_hash_size);
        res = ccn_merkle_root_hash(msg, size, co, merkle_path_digest, merkle_path_info, root_hash, root_hash_size);
        MP_info_free(merkle_path_info);
        if (res < 0) {
            free(root_hash);
	    res = -1;
            goto Bail;
        }
        res = (*sigc->update_func)(sigc->ctx_to_use, root_hash, root_hash_size);
        free(root_hash);
        if (res == 0) {
	    res = -1;
            goto Bail;
        }
        res = (*sigc->verify_final_func)(sigc->ctx_to_use, signature_bits, signature_bits_size, pkey);
    } else {
        /*
         * In the simple signature case, we signed/verify from the name through
         * the end of the content.
         */
        size_t signed_size = co->offset[CCN_PCO_E_Content] - co->offset[CCN_PCO_B_Name];
        res = (*sigc->update_func)(sigc->ctx_to_use, msg + co->offset[CCN_PCO_B_Name], signed_size);
        if (res == 0) {
	    res = -1;
	    goto Bail;
        }
        res = (*sigc->verify_final_func)(sigc->ctx_to_use, signature_bits, signature_bits_size, pkey);
    }
Bail:
    ccn_sigc_destroy(&sigc);
    return (res);
}

struct ccn_pkey *
ccn_d2i_pubkey(const unsigned char *p, size_t size)
{
    const unsigned char *q = p;
    EVP_PKEY *ans;
    ans = d2i_PUBKEY(NULL, &q, size);
    return ((struct ccn_pkey *)ans);
}

void
ccn_pubkey_free(struct ccn_pkey *i_pubkey)
{
    EVP_PKEY *pkey = (EVP_PKEY *)i_pubkey;
    EVP_PKEY_free(pkey);
}

size_t
ccn_pubkey_size(const struct ccn_pkey *i_pubkey)
{
    size_t ans;
    EVP_PKEY *pkey = (EVP_PKEY *)i_pubkey;
    ans = EVP_PKEY_size(pkey);
    return (ans);
}

int
ccn_append_pubkey_blob(struct ccn_charbuf *c, const struct ccn_pkey *i_pubkey)
{
    int res;
    size_t bytes;
    unsigned char *p = NULL;
    res = i2d_PUBKEY((EVP_PKEY *)i_pubkey, NULL);
    if (res < 0)
        return(-1);
    bytes = res;
    res = ccn_charbuf_append_tt(c, bytes, CCN_BLOB);
    if (res < 0)
        return(-1);
    p = ccn_charbuf_reserve(c, bytes);
    if (p == NULL)
        return(-1);
    res = i2d_PUBKEY((EVP_PKEY *)i_pubkey, &p);
    if (res != (int)bytes)
        return(-1);
    c->length += bytes;
    return(bytes);
}

/* PRNG */

/**
 * Generate pseudo-random bytes.
 *
 * @param buf is the destination buffer
 * @param size is in bytes
 */
void
ccn_random_bytes(unsigned char *buf, size_t size)
{
    int num = size;
    
    if (num < 0 || num != size)
        abort();
    RAND_bytes(buf, num);
}

/**
 * Feed some entropy to the random number generator.
 * 
 * @param buf is the source buffer
 * @param size is in bytes
 * @param bits_of_entropy is an estimate; use 0 to make me guess
 */
void
ccn_add_entropy(const void *buf, size_t size, int bits_of_entropy)
{
    int num = size;
    
    if (num < 0 || num != size)
        abort();
    /* Supply a hopefully conservative estimate of entropy. */
    if (bits_of_entropy <= 0)
        bits_of_entropy = (num < 32) ? 1 : num / 32;
    RAND_add((unsigned char *)buf, num, bits_of_entropy * 0.125);
}
