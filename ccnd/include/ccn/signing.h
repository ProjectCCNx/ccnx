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
#include <ccn/charbuf.h>

struct ccn_sigc;
struct ccn_parsed_ContentObject;

struct ccn_sigc *ccn_sigc_create(void);
int ccn_sigc_init(struct ccn_sigc *ctx, const char *digest);
void ccn_sigc_destroy(struct ccn_sigc **);
int ccn_sigc_update(struct ccn_sigc *ctx, const void *data, size_t size);
int ccn_sigc_final(struct ccn_sigc *ctx, const void *signature, size_t *size, void *priv_key);
size_t ccn_sigc_signature_max_size(struct ccn_sigc *ctx, void *priv_key);
int ccn_verify_signature(const unsigned char *msg, size_t size, struct ccn_parsed_ContentObject *co,
                         const void *verification_pubkey);
void *ccn_d2i_pubkey(const unsigned char *p, size_t size);
void ccn_i_pubkey_free(void *i_pubkey); /* use for result of ccn_d2i_pubkey */
size_t ccn_pubkey_size(void *i_pubkey);

/*
 * ccn_append_pubkey_blob: append a ccnb-encoded blob of the external
 * public key, given the internal form
 * Returns -1 for error
 */
int ccn_append_pubkey_blob(struct ccn_charbuf *c, void *i_pubkey);

#endif
