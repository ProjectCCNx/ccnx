#include <stddef.h>
#include <openssl/evp.h>
#include <openssl/x509.h>
#include <ccn/merklepathasn1.h>
#include <ccn/ccn.h>

struct ccn_sigc {
    EVP_MD_CTX context;
    const EVP_MD *digest;
};

struct ccn_sigc *
ccn_sigc_create()
{
    return (calloc(1, sizeof(struct ccn_sigc)));
}

void
ccn_sigc_destroy(struct ccn_sigc **ctx)
{
    if (*ctx) {
        free(*ctx);
        *ctx = NULL;
    }
}

int
ccn_sigc_init(struct ccn_sigc *ctx, const char *digest)
{
    EVP_MD_CTX_init(&ctx->context);
    if (digest == NULL) {
        ctx->digest = EVP_sha256();
    }
    else {
        /* XXX - figure out what algorithm the OID represents */
        fprintf(stderr, "not a DigestAlgorithm I understand right now\n");
        return (-1);
    }

    if (0 == EVP_SignInit_ex(&ctx->context, ctx->digest, NULL))
        return (-1);
    return (0);
}

int
ccn_sigc_update(struct ccn_sigc *ctx, const void *data, size_t size)
{
    if (0 == EVP_SignUpdate(&ctx->context, (unsigned char *)data, size))
        return (-1);
    return (0);
}

int
ccn_sigc_final(struct ccn_sigc *ctx, const void *signature, size_t *size, void *priv_key)
{
    unsigned int sig_size;

    if (0 == EVP_SignFinal(&ctx->context, (unsigned char *)signature, &sig_size, priv_key))
        return (-1);
    *size = sig_size;
    return (0);
}

size_t
ccn_sigc_signature_max_size(struct ccn_sigc *ctx, void *priv_key)
{
    return (EVP_PKEY_size(priv_key));
}

int ccn_merkle_root_hash(const unsigned char *msg, size_t offset, size_t signed_size, EVP_MD_CTX *ver_ctx, const char *mht_info)
{
    return (0);
}
int ccn_verify_signature(const unsigned char *msg,
                     size_t size,
                     struct ccn_parsed_ContentObject *co,
                     struct ccn_indexbuf *comps, const void *verification_pubkey)
{
    EVP_MD_CTX verc;
    EVP_MD_CTX *ver_ctx = &verc;
    X509_SIG *digest_info = NULL;
    MP_info *merkle_path_info = NULL;

    int res;

    const EVP_MD *digest = EVP_md_null();

    const unsigned char *signature_bits = NULL;
    size_t signature_bits_size = 0;
    const unsigned char *witness = NULL;
    size_t witness_size = 0;

    res = ccn_ref_tagged_BLOB(CCN_DTAG_SignatureBits, msg,
                              co->offset[CCN_PCO_B_SignatureBits],
                              co->offset[CCN_PCO_E_SignatureBits],
                              &signature_bits,
                              &signature_bits_size);
    if (res < 0)
        return (-1);

    if (co->offset[CCN_PCO_B_DigestAlgorithm] == co->offset[CCN_PCO_E_DigestAlgorithm]) {
        digest = EVP_sha256();
    }
    else {
        /* XXX - figure out what algorithm the OID represents */
        fprintf(stderr, "not a DigestAlgorithm I understand right now\n");
        return (-1);
    }

    EVP_MD_CTX_init(ver_ctx);
    res = EVP_VerifyInit_ex(ver_ctx, digest, NULL);
    if (!res)
        return (-1);

    /* we sign from the beginning of the name through the end of the content */

    size_t signed_size = co->offset[CCN_PCO_E_Content] - co->offset[CCN_PCO_B_Name];
    res = EVP_VerifyUpdate(ver_ctx, msg + co->offset[CCN_PCO_B_Name], signed_size);

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
        if (res < 0)
            return (-1);


        digest_info = d2i_X509_SIG(NULL, &witness, witness_size);
        /* digest_info->algor->algorithm->{length, data}
         * digest_info->digest->{length, type, data}
         */
        ASN1_OBJECT *merkle_hash_tree_oid = OBJ_txt2obj("1.2.840.113550.11.1.2.2", 1);
        if (0 != OBJ_cmp(digest_info->algor->algorithm, merkle_hash_tree_oid)) {
            fprintf(stderr, "A witness is present without an MHT OID!\n");
            ASN1_OBJECT_free(merkle_hash_tree_oid);
            return (-1);
        }
        /* we're doing an MHT */
        ASN1_OBJECT_free(merkle_hash_tree_oid);
        /* DER-encoded in the digest_info's digest ASN.1 octet string is the Merkle path info */
        merkle_path_info = d2i_MP_info(NULL, &(digest_info->digest->data), digest_info->digest->length);
        /* XXX: debugging */
        int node = ASN1_INTEGER_get(merkle_path_info->node);
        int hash_count = merkle_path_info->hashes->num;
        ASN1_OCTET_STRING *hash;
        hash = (ASN1_OCTET_STRING *)merkle_path_info->hashes->data[0];
        fprintf(stderr, "A witness is present with an MHT OID\n");
        fprintf(stderr, "This is node %d, with %d hashes\n", node, merkle_path_info->hashes->num);
        for (int h = 0; h < merkle_path_info->hashes->num; h++) {
            hash = (ASN1_OCTET_STRING *)merkle_path_info->hashes->data[h];
            fprintf(stderr, "     hashes[%d] len = %d data = ", h, hash->length);
            for (int x = 0; x < hash->length; x++) {
                fprintf(stderr, "%02x", hash->data[x]);
            }
            fprintf(stderr, "\n");
        }
        /* XXX: end debugging */
        return (-1);
    } else {
        res = EVP_VerifyFinal(ver_ctx, signature_bits, signature_bits_size, (EVP_PKEY *)verification_pubkey);
        EVP_MD_CTX_cleanup(ver_ctx);
    }
    if (res == 1)
        return (1);
    else
        return (0);
}
