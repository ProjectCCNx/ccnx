/*
 * ccn/keystore.h
 *
 * KEYSTORE interface
 *
 * This is a veneer so that the ccn code can avoid exposure to the
 * underlying keystore implementation types
 */
#ifndef CCN_KEYSTORE_DEFINED
#define CCN_KEYSTORE_DEFINED

#include <stddef.h>

struct ccn_keystore;

struct ccn_keystore *ccn_keystore_create(void);
void ccn_keystore_destroy(struct ccn_keystore **p);
int ccn_keystore_init(struct ccn_keystore *p, char *name, char *password);
const void *ccn_keystore_private_key(struct ccn_keystore *p);
const void *ccn_keystore_public_key(struct ccn_keystore *p);
const void *ccn_keystore_certificate(struct ccn_keystore *p);

#endif
