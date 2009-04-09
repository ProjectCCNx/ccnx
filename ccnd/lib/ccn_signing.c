#include <stddef.h>
#include <string.h>
#include <openssl/evp.h>
#include <openssl/x509.h>
#include <ccn/merklepathasn1.h>
#include <ccn/ccn.h>

struct ccn_sigc {
    EVP_MD_CTX context;
    const EVP_MD *digest;
};

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
        EVP_MD_CTX_cleanup(&(*ctx)->context);
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

#define is_left(x) (0 == (x & 1))
#define node_lr(x) (x & 1)
#define sibling_of(x) (x ^ 1)
#define parent_of(x) (x >> 1)

int ccn_merkle_root_hash(const unsigned char *msg, size_t size,
                         struct ccn_parsed_ContentObject *co,
                         const EVP_MD *digest_type,
                         MP_info *merkle_path_info,
                         unsigned char *result, int result_size)
{
    int node = ASN1_INTEGER_get(merkle_path_info->node);
    EVP_MD_CTX digest_context;
    EVP_MD_CTX *digest_contextp = &digest_context;
    size_t data_size;
    unsigned char *input_hash[2];
    //int hash_count = merkle_path_info->hashes->num;
    int hash_index = merkle_path_info->hashes->num - 1;
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
    res = EVP_DigestFinal_ex(digest_contextp, result, NULL);

    /* input_hash[0, 1] = address of hash for (left,right) node of parent
     */
    while (node != 1) {
        input_hash[node & 1] = result;
        input_hash[(node & 1) ^ 1] = ((ASN1_OCTET_STRING *)merkle_path_info->hashes->data[hash_index])->data;
        if (((ASN1_OCTET_STRING *)merkle_path_info->hashes->data[hash_index])->length != result_size)
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
        EVP_DigestInit_ex(digest_contextp, digest_type, NULL);
        res = EVP_DigestUpdate(digest_contextp, input_hash[0], result_size);
        res = EVP_DigestUpdate(digest_contextp, input_hash[1], result_size);
        res = EVP_DigestFinal_ex(digest_contextp, result, NULL);
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
                     struct ccn_parsed_ContentObject *co,
                     const void *verification_pubkey)
{
    EVP_MD_CTX verc;
    EVP_MD_CTX *ver_ctx = &verc;
    X509_SIG *digest_info = NULL;
    MP_info *merkle_path_info = NULL;
    unsigned char *root_hash;
    size_t root_hash_size;

    int res;

    const EVP_MD *digest = EVP_md_null();
    const EVP_MD *merkle_path_digest = EVP_md_null();

    const unsigned char *signature_bits = NULL;
    size_t signature_bits_size = 0;
    const unsigned char *witness = NULL;
    size_t witness_size = 0;
#ifdef DEBUG
    int x, h;
#endif

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
        /* ...2.2 is an MHT w/ SHA256 */
        ASN1_OBJECT *merkle_hash_tree_oid = OBJ_txt2obj("1.2.840.113550.11.1.2.2", 1);
        if (0 != OBJ_cmp(digest_info->algor->algorithm, merkle_hash_tree_oid)) {
            fprintf(stderr, "A witness is present without an MHT OID!\n");
            ASN1_OBJECT_free(merkle_hash_tree_oid);
            return (-1);
        }
        /* we're doing an MHT */
        ASN1_OBJECT_free(merkle_hash_tree_oid);
        merkle_path_digest = EVP_sha256();
        /* DER-encoded in the digest_info's digest ASN.1 octet string is the Merkle path info */
        merkle_path_info = d2i_MP_info(NULL, (const unsigned char **)&(digest_info->digest->data), digest_info->digest->length);
#ifdef DEBUG
        int node = ASN1_INTEGER_get(merkle_path_info->node);
        int hash_count = merkle_path_info->hashes->num;
        ASN1_OCTET_STRING *hash;
        fprintf(stderr, "A witness is present with an MHT OID\n");
        fprintf(stderr, "This is node %d, with %d hashes\n", node, hash_count);
        for (h = 0; h < hash_count; h++) {
            hash = (ASN1_OCTET_STRING *)merkle_path_info->hashes->data[h];
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
        res = EVP_VerifyUpdate(ver_ctx, root_hash, root_hash_size);
        res = EVP_VerifyFinal(ver_ctx, signature_bits, signature_bits_size, (EVP_PKEY *)verification_pubkey);
        EVP_MD_CTX_cleanup(ver_ctx);
    } else {
        /*
         * In the simple signature case, we signed/verify from the name through
         * the end of the content.
         */
        size_t signed_size = co->offset[CCN_PCO_E_Content] - co->offset[CCN_PCO_B_Name];
        res = EVP_VerifyUpdate(ver_ctx, msg + co->offset[CCN_PCO_B_Name], signed_size);
        res = EVP_VerifyFinal(ver_ctx, signature_bits, signature_bits_size, (EVP_PKEY *)verification_pubkey);
        EVP_MD_CTX_cleanup(ver_ctx);
    }

    if (res == 1)
        return (1);
    else
        return (0);
}
