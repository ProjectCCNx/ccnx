/*
 * ccn/signing.h
 * 
 * Message signing interface
 *
 * This is a veneer so that the ccn code can use various underlying
 * implementations of the signature functions without muss and fuss
 */
#ifndef CCN_SIGNING_DEFINED
#define CCN_SIGNING_DEFINED

#include <stddef.h>

struct ccn_sigc;

struct ccn_sigc *ccn_sigc_create(void);
int ccn_sigc_init(struct ccn_sigc *ctx, const char *digest);
void ccn_sigc_destroy(struct ccn_sigc **);
int ccn_sigc_update(struct ccn_sigc *ctx, const void *data, size_t size);
int ccn_sigc_final(struct ccn_sigc *ctx, const void *signature, size_t *size, const void *priv_key);
size_t ccn_sigc_signature_max_size(struct ccn_sigc *ctx, const void *priv_key);
int ccn_verify_signature(const unsigned char *msg, size_t size, struct ccn_parsed_ContentObject *co,
                         const void *verification_pubkey);
void *ccn_d2i_pubkey(const unsigned char *p, size_t size);
size_t ccn_pubkey_size(void *pubkey);

#endif
