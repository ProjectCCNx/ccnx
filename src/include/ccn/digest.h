/*
 * ccn/digest.h
 * 
 * Message digest interface
 *
 * This is a veneer so that the ccn code can use various underlying
 * implementations of the message digest functions without muss and fuss
 */
#ifndef CCN_DIGEST_DEFINED
#define CCN_DIGEST_DEFINED

#include <stddef.h>

struct ccn_digest;

/* These ids are not meant to be stable across versions */
enum ccn_digest_id {
    CCN_DIGEST_DEFAULT,
    CCN_DIGEST_SHA1,
    CCN_DIGEST_SHA224,
    CCN_DIGEST_SHA256, /* This is our current favorite */
    CCN_DIGEST_SHA384,
    CCN_DIGEST_SHA512
};

struct ccn_digest *ccn_digest_create(enum ccn_digest_id);
void ccn_digest_destroy(struct ccn_digest **);
enum ccn_digest_id ccn_digest_getid(struct ccn_digest *);
size_t ccn_digest_size(struct ccn_digest *);
void ccn_digest_init(struct ccn_digest *);
/* return codes are negative for errors */
int ccn_digest_update(struct ccn_digest *, const void *, size_t);
int ccn_digest_final(struct ccn_digest *, unsigned char *, size_t);

#endif

