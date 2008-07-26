#include <stdio.h>
#include <stdlib.h>
#include <openssl/pkcs12.h>


struct ccn_keystore {
    int initialized;
    EVP_PKEY *private_key;
    X509 *certificate;
};

struct ccn_keystore *
ccn_keystore_create()
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
    p->initialized = 1;
    return (0);
}

const void *
ccn_keystore_private_key(struct ccn_keystore *p)
{
    if (0 == p->initialized)
        return (NULL);

    return (const void *)(p->private_key);
}

const void *
ccn_keystore_public_key(struct ccn_keystore *p)
{
    if (0 == p->initialized)
        return (NULL);

    return (const void *)X509_get_pubkey(p->certificate);
}

const void *
ccn_keystore_certificate(struct ccn_keystore *p)
{
    if (0 == p->initialized)
        return (NULL);

    return (const void *)(p->certificate);
}
