/*
 * ccn_buf_encoder.c
 *  
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * $Id$
 */

#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/time.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/indexbuf.h>
#include <ccn/signing.h>

int
ccn_signed_info_create_default(struct ccn_charbuf *c,
                        enum ccn_content_type Type)
{
    return (ccn_signed_info_create(c, NULL, 0, NULL, Type, -1, NULL, NULL));
}
		
int
ccn_signed_info_create(struct ccn_charbuf *c,
                       const void *publisher_key_id,	/* input, sha256 hash */
                       size_t publisher_key_id_size, 	/* input, 32 for sha256 hashes */
                       const struct ccn_charbuf *timestamp,/* input ccnb blob, NULL for "now" */
                       enum ccn_content_type type,	/* input */
                       int freshness,			/* input, -1 means omit */
                       const struct ccn_charbuf *finalblockid,  /* input, NULL means omit */
                       const struct ccn_charbuf *key_locator)	/* input, optional, ccnb encoded */
{
    int res = 0;
    const char fakepubkeyid[32] = {0};
 
    if (publisher_key_id != NULL && publisher_key_id_size != 32)
        return (-1);

    res |= ccn_charbuf_append_tt(c, CCN_DTAG_SignedInfo, CCN_DTAG);

    res |= ccn_charbuf_append_tt(c, CCN_DTAG_PublisherPublicKeyDigest, CCN_DTAG);
    if (publisher_key_id != NULL) {
        res |= ccn_charbuf_append_tt(c, publisher_key_id_size, CCN_BLOB);
        res |= ccn_charbuf_append(c, publisher_key_id, publisher_key_id_size);
    } else {
        /* XXX - obtain the default publisher key id and append it */
        res |= ccn_charbuf_append_tt(c, sizeof(fakepubkeyid), CCN_BLOB);
        res |= ccn_charbuf_append(c, fakepubkeyid, sizeof(fakepubkeyid));
    }
    res |= ccn_charbuf_append_closer(c);

    res |= ccn_charbuf_append_tt(c, CCN_DTAG_Timestamp, CCN_DTAG);
    if (timestamp != NULL)
        res |= ccn_charbuf_append_charbuf(c, timestamp);
    else
        res |= ccn_charbuf_append_now_blob(c, CCN_MARKER_NONE);
    res |= ccn_charbuf_append_closer(c);

    if (type != CCN_CONTENT_DATA) {
        res |= ccn_charbuf_append_tt(c, CCN_DTAG_Type, CCN_DTAG);
        res |= ccn_charbuf_append_tt(c, 3, CCN_BLOB);
        res |= ccn_charbuf_append_value(c, type, 3);
        res |= ccn_charbuf_append_closer(c);
    }

    if (freshness >= 0) {
        res |= ccn_charbuf_append_tt(c, CCN_DTAG_FreshnessSeconds, CCN_DTAG);
        res |= ccn_charbuf_append_non_negative_integer(c, freshness);
        res |= ccn_charbuf_append_closer(c);
    }

    if (finalblockid != NULL) {
        res |= ccn_charbuf_append_tt(c, CCN_DTAG_FinalBlockID, CCN_DTAG);
        res |= ccn_charbuf_append_charbuf(c, finalblockid);
        res |= ccn_charbuf_append_closer(c);
    }

    if (key_locator != NULL) {
	/* key_locator is a sub-type that should already be encoded */
	res |= ccn_charbuf_append_charbuf(c, key_locator);
    }
    
    res |= ccn_charbuf_append_closer(c);

    return (res == 0 ? 0 : -1);
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
                         const struct ccn_charbuf *SignedInfo,
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
    if (0 != ccn_sigc_update(sig_ctx, SignedInfo->buf, SignedInfo->length)) return (-1);
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
    res |= ccn_charbuf_append_charbuf(buf, SignedInfo);
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

int
ccn_charbuf_append_non_negative_integer(struct ccn_charbuf *c, int nni)
{
    char nnistring[24];
    int nnistringlen;
    int res;

    if (nni < 0) return (-1);
    nnistringlen = snprintf(nnistring, sizeof(nnistring), "%d", nni);
    res = ccn_charbuf_append_tt(c, nnistringlen, CCN_UDATA);
    res |= ccn_charbuf_append_string(c, nnistring);
    return (res);
}

int
ccn_charbuf_append_timestamp_blob(struct ccn_charbuf *c, int marker, intmax_t secs, int nsecs)
{
    int i;
    int n;
    intmax_t ts;
    unsigned char *p;
    if (secs <= 0 || nsecs < 0 || nsecs > 999999999)
        return(-1);
    n = 2;
    for (ts = secs >> 4; n < 7 && ts != 0; ts >>= 8)
        n++;
    ccn_charbuf_append_tt(c, n + (marker >= 0), CCN_BLOB);
    if (marker >= 0)
        ccn_charbuf_append_value(c, marker, 1);
    p = ccn_charbuf_reserve(c, n);
    if (p == NULL)
        return(-1);
    ts = secs >> 4;
    for (i = 0; i < n - 2; i++)
        p[i] = ts >> (8 * (n - 3 - i));
    /* arithmetic contortions are to avoid overflowing 31 bits */
    ts = ((secs & 15) << 12) + ((nsecs / 5 * 8 + 195312) / 390625);
    for (i = n - 2; i < n; i++)
        p[i] = ts >> (8 * (n - 1 - i));
    c->length += n;
    return(0);
}

int
ccn_charbuf_append_now_blob(struct ccn_charbuf *c, int marker)
{
    struct timeval now;
    int res;

    gettimeofday(&now, NULL);

    res = ccn_charbuf_append_timestamp_blob(c, marker, now.tv_sec, now.tv_usec * 1000);
    return (res);
}

