/*
 * ccn_buf_encoder.c
 *  
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * $Id$
 */

#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/indexbuf.h>
#include <ccn/signing.h>

int
ccn_auth_create_default(struct ccn_charbuf *c,
                        enum ccn_content_type Type)
{
    return (ccn_auth_create(c, NULL, 0, NULL, Type, NULL));
}
		
int
ccn_auth_create(struct ccn_charbuf *c,
                const void *publisher_key_id,	/* input, sha256 hash */
                size_t publisher_key_id_size, 	/* input, 32 for sha256 hashes */
                const char *datetime,
                enum ccn_content_type type,	/* input */
                const struct ccn_charbuf *key_locator)	/* input, optional, ccnb encoded */
{
    int res = 0;
    const char *typename = ccn_content_name(type);
    struct ccn_charbuf *dt;
    const char fakepubkeyid[32] = {0};
 
    if (typename == NULL)
	return (-1);
    if (publisher_key_id != NULL && publisher_key_id_size != 32)
        return (-1);

    res |= ccn_charbuf_append_tt(c, CCN_DTAG_ContentAuthenticator, CCN_DTAG);

    res |= ccn_charbuf_append_tt(c, CCN_DTAG_PublisherKeyID, CCN_DTAG);
    if (publisher_key_id != NULL) {
        res |= ccn_charbuf_append_tt(c, publisher_key_id_size, CCN_BLOB);
        res |= ccn_charbuf_append(c, publisher_key_id, publisher_key_id_size);
    } else {
        /* obtain the default publisher key id and append it */
        res |= ccn_charbuf_append_tt(c, sizeof(fakepubkeyid), CCN_BLOB);
        res |= ccn_charbuf_append(c, fakepubkeyid, sizeof(fakepubkeyid));
    }
    res |= ccn_charbuf_append_closer(c);

    res |= ccn_charbuf_append_tt(c, CCN_DTAG_Timestamp, CCN_DTAG);
    if (datetime != NULL) {
        res |= ccn_charbuf_append_tt(c, strlen(datetime), CCN_UDATA);
        res |= ccn_charbuf_append_string(c, datetime);
    }
    else {
        dt = ccn_charbuf_create();
        res |= ccn_charbuf_append_datetime_now(dt, CCN_DATETIME_PRECISION_USEC);
        res |= ccn_charbuf_append_tt(c, dt->length, CCN_UDATA);
        res |= ccn_charbuf_append_charbuf(c, dt);
        ccn_charbuf_destroy(&dt);
    }
    res |= ccn_charbuf_append_closer(c);

    res |= ccn_charbuf_append_tt(c, CCN_DTAG_Type, CCN_DTAG);
    res |= ccn_charbuf_append_tt(c, strlen(typename), CCN_UDATA);
    res |= ccn_charbuf_append_string(c, typename);
    res |= ccn_charbuf_append_closer(c);

    if (key_locator != NULL) {
	/* key_locator is a sub-type that should already be encoded */
	res |= ccn_charbuf_append_charbuf(c, key_locator);
    }
    
    res |= ccn_charbuf_append_closer(c);

    return (res == 0 ? 0 : -1);
}

const char *
ccn_content_name(enum ccn_content_type type)
{
    switch (type) {
    case CCN_CONTENT_FRAGMENT:
	return "FRAGMENT";
    case CCN_CONTENT_LINK:
	return "LINK";
    case CCN_CONTENT_COLLECTION:
	return "COLLECTION";
    case CCN_CONTENT_LEAF:
	return "LEAF";
    case CCN_CONTENT_SESSION:
	return "SESSION";
    case CCN_CONTENT_HEADER:
	return "HEADER";
    case CCN_CONTENT_KEY:
	return "KEY";
    default:
	return NULL;
    }
}

int
ccn_encode_Signature(struct ccn_charbuf *buf,
                     const char *digest_algorithm,
                     const void *witness,
                     size_t witness_size,
                     const void *signature,
                     size_t signature_size)
{
    int res = 0;

    if (signature == NULL)
        return (-1);

    res |= ccn_charbuf_append_tt(buf, CCN_DTAG_Signature, CCN_DTAG);

    if (digest_algorithm != NULL) {
        res |= ccn_charbuf_append_tt(buf, CCN_DTAG_DigestAlgorithm, CCN_DTAG);
        res |= ccn_charbuf_append_tt(buf, strlen(digest_algorithm), CCN_UDATA);
        res |= ccn_charbuf_append_string(buf, digest_algorithm);
        res |= ccn_charbuf_append_closer(buf);
    }

    if (witness != NULL) {
        res |= ccn_charbuf_append_tt(buf, CCN_DTAG_Witness, CCN_DTAG);
        res |= ccn_charbuf_append_tt(buf, witness_size, CCN_BLOB);
        res |= ccn_charbuf_append(buf, witness, witness_size);
        res |= ccn_charbuf_append_closer(buf);
    }

    res |= ccn_charbuf_append_tt(buf, CCN_DTAG_SignatureBits, CCN_DTAG);
    res |= ccn_charbuf_append_tt(buf, signature_size, CCN_BLOB);
    res |= ccn_charbuf_append(buf, signature, signature_size);
    res |= ccn_charbuf_append_closer(buf);
    
    res |= ccn_charbuf_append_closer(buf);

    return (res == 0 ? 0 : -1);
}

int
ccn_encode_ContentObject(struct ccn_charbuf *buf,
                         const struct ccn_charbuf *Name,
                         const struct ccn_charbuf *ContentAuthenticator,
                         const void *data,
                         size_t size,
                         const char *digest_algorithm,
                         const void *private_key
                         )
{
    int res = 0;
    struct ccn_sigc *sig_ctx;
    unsigned char *signature;
    size_t signature_size;
    struct ccn_charbuf *content_header;

    content_header = ccn_charbuf_create();
    res |= ccn_charbuf_append_tt(content_header, CCN_DTAG_Content, CCN_DTAG);
    res |= ccn_charbuf_append_tt(content_header, size, CCN_BLOB);
    res |= ccn_charbuf_append_closer(content_header);

    sig_ctx = ccn_sigc_create();
    if (sig_ctx == NULL) return (-1);

    if (0 != ccn_sigc_init(sig_ctx, digest_algorithm)) return (-1);
    if (0 != ccn_sigc_update(sig_ctx, Name->buf, Name->length)) return (-1);
    if (0 != ccn_sigc_update(sig_ctx, ContentAuthenticator->buf, ContentAuthenticator->length)) return (-1);
    if (0 != ccn_sigc_update(sig_ctx, content_header->buf, content_header->length - 1)) return (-1);
    if (0 != ccn_sigc_update(sig_ctx, data, size)) return (-1);
    if (0 != ccn_sigc_update(sig_ctx, content_header->buf + content_header->length - 1, 1)) return (-1);

    signature = calloc(1, ccn_sigc_signature_max_size(sig_ctx, private_key));
    if (signature == NULL) return (-1);
    if (0 != ccn_sigc_final(sig_ctx, signature, &signature_size, private_key)) {
        free(signature);
        return (-1);
    }
    ccn_sigc_destroy(&sig_ctx);

    res |= ccn_charbuf_append_tt(buf, CCN_DTAG_ContentObject, CCN_DTAG);
    res |= ccn_encode_Signature(buf, digest_algorithm, NULL, 0, signature, signature_size);
    res |= ccn_charbuf_append_charbuf(buf, Name);
    res |= ccn_charbuf_append_charbuf(buf, ContentAuthenticator);
    res |= ccn_encode_Content(buf, data, size);
    res |= ccn_charbuf_append_closer(buf);
    
    free(signature);
    ccn_charbuf_destroy(&content_header);
    return (res == 0 ? 0 : -1);
}

int
ccn_encode_Content(struct ccn_charbuf *buf,
			     const void *data,
			     size_t size)
{
    int res = 0;

    res |= ccn_charbuf_append_tt(buf, CCN_DTAG_Content, CCN_DTAG);
    res |= ccn_charbuf_append_tt(buf, size, CCN_BLOB);
    res |= ccn_charbuf_append(buf, data, size);
    res |= ccn_charbuf_append_closer(buf);
    
    return (res == 0 ? 0 : -1);
}


int
ccn_charbuf_append_tt(struct ccn_charbuf *c, size_t val, enum ccn_tt tt)
{
    unsigned char buf[1+8*((sizeof(val)+6)/7)];
    unsigned char *p = &(buf[sizeof(buf)-1]);
    int n = 1;
    p[0] = (CCN_TT_HBIT & ~CCN_CLOSE) |
           ((val & CCN_MAX_TINY) << CCN_TT_BITS) |
           (CCN_TT_MASK & tt);
    val >>= (7-CCN_TT_BITS);
    while (val != 0) {
        (--p)[0] = (((unsigned char)val) & ~CCN_TT_HBIT) | CCN_CLOSE;
        n++;
        val >>= 7;
    }
    return (ccn_charbuf_append(c, p, n));
}

int
ccn_charbuf_append_closer(struct ccn_charbuf *c)
{
    int res;
    const unsigned char closer = CCN_CLOSE;
    res = ccn_charbuf_append(c, &closer, 1);
    return(res);
}
