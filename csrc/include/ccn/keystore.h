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

/*
 * opaque type for key storage
 */
struct ccn_keystore;

/*
 * opaque type for public and private keys
 */
struct ccn_pkey;

/*
 * opaque type for (X509) certificates
 */
struct ccn_certificate;

struct ccn_keystore *ccn_keystore_create(void);
void ccn_keystore_destroy(struct ccn_keystore **p);
int ccn_keystore_init(struct ccn_keystore *p, char *name, char *password);
const struct ccn_pkey *ccn_keystore_private_key(struct ccn_keystore *p);
const struct ccn_pkey *ccn_keystore_public_key(struct ccn_keystore *p);
ssize_t ccn_keystore_public_key_digest_length(struct ccn_keystore *p);
const unsigned char *ccn_keystore_public_key_digest(struct ccn_keystore *p);
const struct ccn_certificate *ccn_keystore_certificate(struct ccn_keystore *p);

#endif
