#include <stddef.h>
#include <openssl/evp.h>

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
    if (0 == EVP_SignFinal(&ctx->context, (unsigned char *)signature, size, priv_key))
        return (-1);
    return (0);
}

size_t
ccn_sigc_signature_max_size(struct ccn_sigc *ctx, void *priv_key)
{
    return (EVP_PKEY_size(priv_key));
}
