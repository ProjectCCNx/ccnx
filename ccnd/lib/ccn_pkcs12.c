#include <stdio.h>
#include <stdlib.h>
#include <openssl/pkcs12.h>


struct ccn_pkcs12 {
    EVP_PKEY *private_key;
    X509 *certificate;
    STACK_OF(X509) *ca;
};

struct ccn_pkcs12 *
ccn_pkcs12_create()
{
    struct ccn_pkcs12 *res_pkcs12 = calloc(1, sizeof(*res_pkcs12));
    return (res_pkcs12);
}

void
ccn_pkcs12_destroy(struct ccn_pkcs12 **p)
{
    if (*p != NULL) {
        if ((*p)->private_key != NULL)
            EVP_PKEY_free((*p)->private_key);
        if ((*p)->certificate != NULL)
            X509_free((*p)->certificate);
        if ((*p)->ca != NULL) {
            /* XXX - NHB - how do we free a STACK_OF(X509) */
        }
        free(*p);
        *p = NULL;
    }
}

int
ccn_pkcs12_init(struct ccn_pkcs12 *p, char *name, char *password)
{
    FILE *fp;
    PKCS12 *pkcs12;
    int res;

    OpenSSL_add_all_algorithms();
    fp = fopen(name, "rb");
    if (fp == NULL)
        return (-1);

    pkcs12 = d2i_PKCS12_fp(fp, NULL);
    fclose(fp);
    if (pkcs12 == NULL)
        return (-1);

    res = PKCS12_parse(pkcs12, password, &p->private_key, &p->certificate, &p->ca);
    PKCS12_free(pkcs12);

    if (res != 0)
        return (-1);

    return (0);
}
