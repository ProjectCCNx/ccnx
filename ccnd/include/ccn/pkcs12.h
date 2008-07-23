/*
 * ccn/pkcs12.h
 *
 * PKCS12 interface
 *
 * This is a veneer so that the ccn code can avoid exposure to the
 * openssl types
 */
#ifndef CCN_PKCS12_DEFINED
#define CCN_PKCS12_DEFINED

#include <stddef.h>

struct ccn_pkcs12;

struct ccn_pkcs12 *ccn_pkcs12_create();
void ccn_pkcs12_destroy(struct ccn_pkcs12 **p);
int ccn_pkcs12_init(struct ccn_pkcs12 *p, char *name, char *password);

#endif
