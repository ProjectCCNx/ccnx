/**
 * @file ccn_buf_encoder.c
 * @brief Support for constructing various ccnb-encoded objects.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */
#include <string.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/time.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/indexbuf.h>
#include <ccn/signing.h>
#include <ccn/ccn_private.h>

/**
 * Create SignedInfo.
 *
 *
 * @param c is used to hold the result.
 * @param publisher_key_id points to the digest of the publisher key id.
 * @param publisher_key_id_size is the size in bytes(32) of the pub key digest
 * @param timestamp holds the timestamp, as a ccnb-encoded blob, or is NULL
          to use the current time.
 * @param type indicates the Type of the ContentObject.
 * @param freshness is the FreshnessSeconds value, or -1 to omit.
 * @param finalblockid holds the FinalBlockID, as a ccnb-encoded blob, or is
          NULL to omit.
 * @param key_locator is the ccnb-encoded KeyLocator element, or NULL to omit.
 * @returns 0 for success or -1 for error.
 */
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
        return(-1);

    res |= ccnb_element_begin(c, CCN_DTAG_SignedInfo);

    if (publisher_key_id != NULL) {
        res |= ccnb_append_tagged_blob(c, CCN_DTAG_PublisherPublicKeyDigest,
                                       publisher_key_id, publisher_key_id_size);
    } else {
        /* XXX - obtain the default publisher key id and append it */
        res |= ccnb_append_tagged_blob(c, CCN_DTAG_PublisherPublicKeyDigest,
                                       fakepubkeyid, sizeof(fakepubkeyid));
    }
    res |= ccnb_element_begin(c, CCN_DTAG_Timestamp);
    if (timestamp != NULL)
        res |= ccn_charbuf_append_charbuf(c, timestamp);
    else
        res |= ccnb_append_now_blob(c, CCN_MARKER_NONE);
    res |= ccnb_element_end(c);

    if (type != CCN_CONTENT_DATA) {
        res |= ccnb_element_begin(c, CCN_DTAG_Type);
        res |= ccn_charbuf_append_tt(c, 3, CCN_BLOB);
        res |= ccn_charbuf_append_value(c, type, 3);
        res |= ccnb_element_end(c);
    }

    if (freshness >= 0)
        res |= ccnb_tagged_putf(c, CCN_DTAG_FreshnessSeconds, "%d", freshness);

    if (finalblockid != NULL) {
        res |= ccnb_element_begin(c, CCN_DTAG_FinalBlockID);
        res |= ccn_charbuf_append_charbuf(c, finalblockid);
        res |= ccnb_element_end(c);
    }

    if (key_locator != NULL) {
	/* key_locator is a sub-type that should already be encoded */
	res |= ccn_charbuf_append_charbuf(c, key_locator);
    }
    
    res |= ccnb_element_end(c);

    return(res == 0 ? 0 : -1);
}

static int
ccn_encode_Signature(struct ccn_charbuf *buf,
                     const char *digest_algorithm,
                     const void *witness,
                     size_t witness_size,
                     const struct ccn_signature *signature,
                     size_t signature_size)
{
    int res = 0;

    if (signature == NULL)
        return(-1);

    res |= ccnb_element_begin(buf, CCN_DTAG_Signature);

    if (digest_algorithm != NULL) {
        res |= ccnb_append_tagged_udata(buf, CCN_DTAG_DigestAlgorithm,
                                        digest_algorithm, strlen(digest_algorithm));
    }

    if (witness != NULL) {
        res |= ccnb_append_tagged_blob(buf, CCN_DTAG_Witness, witness, witness_size);
    }

    res |= ccnb_append_tagged_blob(buf, CCN_DTAG_SignatureBits, signature, signature_size);

    res |= ccnb_element_end(buf); // CCN_DTAG_Signature

    return(res == 0 ? 0 : -1);
}

/**
 * Encode and sign a ContentObject.
 * @param buf is the output buffer where encoded object is written.
 * @param Name is the ccnb-encoded name from ccn_name_init and friends.
 * @param SignedInfo is the ccnb-encoded info from ccn_signed_info_create.
 * @param data pintes to the raw data to be encoded.
 * @param size is the size, in bytes, of the raw data to be encoded.
 * @param digest_algorithm may be NULL for default.
 * @param key is the private or symmetric key to use for signing.
 * @returns 0 for success or -1 for error.
 */
int
ccn_encode_ContentObject(struct ccn_charbuf *buf,
                         const struct ccn_charbuf *Name,
                         const struct ccn_charbuf *SignedInfo,
                         const void *data,
                         size_t size,
                         const char *digest_algorithm,
                         const struct ccn_pkey *key
                         )
{
    int res = 0;
    struct ccn_sigc *sig_ctx = NULL;
    struct ccn_signature *signature = NULL;
    size_t signature_size;
    struct ccn_charbuf *content_header;
    size_t closer_start;

    content_header = ccn_charbuf_create();
    res |= ccnb_element_begin(content_header, CCN_DTAG_Content);
    if (size != 0)
        res |= ccn_charbuf_append_tt(content_header, size, CCN_BLOB);
    closer_start = content_header->length;
    res |= ccnb_element_end(content_header);
    if (res < 0) {
	res = -1;
	goto Bail;
    }
    sig_ctx = ccn_sigc_create();
    if (sig_ctx == NULL) {
	res = -1;
	goto Bail;
    }
    if (0 != ccn_sigc_init(sig_ctx, digest_algorithm, key)) {
	res = -1;
	goto Bail;
    }
    if (0 != ccn_sigc_update(sig_ctx, Name->buf, Name->length)) {
	res = -1;
	goto Bail;
    }
    if (0 != ccn_sigc_update(sig_ctx, SignedInfo->buf, SignedInfo->length)) {
	res = -1;
	goto Bail;
    }
    if (0 != ccn_sigc_update(sig_ctx, content_header->buf, closer_start)) {
	res = -1;
	goto Bail;
    }
    if (0 != ccn_sigc_update(sig_ctx, data, size)) {
	res = -1;
	goto Bail;
    }
    if (0 != ccn_sigc_update(sig_ctx, content_header->buf + closer_start,
                             content_header->length - closer_start)) {
	res = -1;
	goto Bail;
    }
    signature = calloc(1, ccn_sigc_signature_max_size(sig_ctx, key));
    if (signature == NULL) {
	res = -1;
	goto Bail;
    }
    res = ccn_sigc_final(sig_ctx, signature, &signature_size, key);
    if (0 != res) {
	res = -1;
	goto Bail;
    }
    res |= ccnb_element_begin(buf, CCN_DTAG_ContentObject);
    res |= ccn_encode_Signature(buf, digest_algorithm,
                                NULL, 0, signature, signature_size);
    res |= ccn_charbuf_append_charbuf(buf, Name);
    res |= ccn_charbuf_append_charbuf(buf, SignedInfo);
    res |= ccnb_append_tagged_blob(buf, CCN_DTAG_Content, data, size);
    res |= ccnb_element_end(buf);
Bail:
    if (sig_ctx != NULL)
        ccn_sigc_destroy(&sig_ctx);
    if (signature != NULL)
	free(signature);
    ccn_charbuf_destroy(&content_header);
    return(res == 0 ? 0 : -1);
}

/***********************************
 * Append a StatusResponse
 * 
 *  @param buf is the buffer to append to.
 *  @param errcode is a 3-digit error code.
 *            It should be documented in StatusResponse.txt.
 *  @param errtext is human-readable text (may be NULL).
 *  @returns 0 for success or -1 for error.
 */
int
ccn_encode_StatusResponse(struct ccn_charbuf *buf,
                          int errcode, const char *errtext)
{
    int res = 0;
    if (errcode < 100 || errcode > 999)
        return(-1);
    res |= ccnb_element_begin(buf, CCN_DTAG_StatusResponse);
    res |= ccnb_tagged_putf(buf, CCN_DTAG_StatusCode, "%d", errcode);
    if (errtext != NULL && errtext[0] != 0)
        res |= ccnb_tagged_putf(buf, CCN_DTAG_StatusText, "%s", errtext);
    res |= ccnb_element_end(buf);
    return(res);
}

/**
 * Append a ccnb start marker
 *
 * This forms the basic building block of ccnb-encoded data.
 * @param c is the buffer to append to.
 * @param val is the numval, intepreted according to tt (see enum ccn_tt).
 * @param tt is the type field.
 * @returns 0 for success or -1 for error.
 */
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
    return(ccn_charbuf_append(c, p, n));
}

int
ccn_charbuf_append_closer(struct ccn_charbuf *c)
{
    int res;
    const unsigned char closer = CCN_CLOSE;
    res = ccn_charbuf_append(c, &closer, 1);
    return(res);
}

/**
 * Append a non-negative integer as a UDATA.
 * @param c is the buffer to append to.
 * @param nni is a non-negative value.
 * @returns 0 for success or -1 for error.
 */
int
ccnb_append_number(struct ccn_charbuf *c, int nni)
{
    char nnistring[40];
    int nnistringlen;
    int res;

    if (nni < 0)
        return(-1);
    nnistringlen = snprintf(nnistring, sizeof(nnistring), "%d", nni);
    if (nnistringlen >= sizeof(nnistring))
        return(-1);
    res = ccn_charbuf_append_tt(c, nnistringlen, CCN_UDATA);
    res |= ccn_charbuf_append_string(c, nnistring);
    return(res);
}

/**
 * Append a binary timestamp
 * as a BLOB using the ccn binary Timestamp representation (12-bit fraction).
 * @param c is the buffer to append to.
 * @param marker
 *   If marker >= 0, the low-order byte is used as a marker byte, useful for
 *   some content naming conventions (versioning, in particular).
 * @param secs - seconds since epoch
 * @param nsecs - nanoseconds
 * @returns 0 for success or -1 for error.
 */
int
ccnb_append_timestamp_blob(struct ccn_charbuf *c,
                           enum ccn_marker marker,
                           intmax_t secs, int nsecs)
{
    int i;
    int n;
    uintmax_t ts, tsh;
    int tsl;
    unsigned char *p;
    if (secs <= 0 || nsecs < 0 || nsecs > 999999999)
        return(-1);
    /* arithmetic contortions are to avoid overflowing 31 bits */
    tsl = ((int)(secs & 0xf) << 12) + ((nsecs / 5 * 8 + 195312) / 390625);
    tsh = (secs >> 4) + (tsl >> 16);
    tsl &= 0xffff;
    n = 2;
    for (ts = tsh; n < 7 && ts != 0; ts >>= 8)
        n++;
    ccn_charbuf_append_tt(c, n + (marker >= 0), CCN_BLOB);
    if (marker >= 0)
        ccn_charbuf_append_value(c, marker, 1);
    p = ccn_charbuf_reserve(c, n);
    if (p == NULL)
        return(-1);
    for (i = 0; i < n - 2; i++)
        p[i] = tsh >> (8 * (n - 3 - i));
    for (i = n - 2; i < n; i++)
        p[i] = tsl >> (8 * (n - 1 - i));
    c->length += n;
    return(0);
}

/**
 * Append a binary timestamp, using the current time.
 * 
 * Like ccnb_append_timestamp_blob() but uses current time
 * @param c is the buffer to append to.
 * @param marker - see ccnb_append_timestamp_blob()
 * @returns 0 for success or -1 for error.
 */
int
ccnb_append_now_blob(struct ccn_charbuf *c, enum ccn_marker marker)
{
    struct timeval now;
    int res;

    gettimeofday(&now, NULL);
    res = ccnb_append_timestamp_blob(c, marker, now.tv_sec, now.tv_usec * 1000);
    return(res);
}

/**
 * Append a start-of-element marker.
 */
int
ccnb_element_begin(struct ccn_charbuf *c, enum ccn_dtag dtag)
{
    return(ccn_charbuf_append_tt(c, dtag, CCN_DTAG));
}

/**
 * Append an end-of-element marker.
 *
 * This is the same as ccn_charbuf_append_closer()
 */
int ccnb_element_end(struct ccn_charbuf *c)
{
    return(ccn_charbuf_append_closer(c));
}

/**
 * Append a tagged BLOB
 *
 * This is a ccnb-encoded element with containing the BLOB as content
 * @param c is the buffer to append to.
 * @param dtag is the element's dtab
 * @param data points to the binary data
 * @param size is the size of the data, in bytes
 * @returns 0 for success or -1 for error.
 */
int
ccnb_append_tagged_blob(struct ccn_charbuf *c,
                        enum ccn_dtag dtag,
                        const void *data,
                        size_t size)
{
    int res;
    
    res = ccnb_element_begin(c, dtag);
    if (size != 0) {
        res |= ccn_charbuf_append_tt(c, size, CCN_BLOB);
        res |= ccn_charbuf_append(c, data, size);
    }
    res |= ccnb_element_end(c);
    return(res == 0 ? 0 : -1);
}
/**
 * Append a tagged binary number as a blob containing the integer value
 *
 * This is a ccnb-encoded element holding a 
 * @param cb is the buffer to append to.
 * @param dtag is the element's dtab
 * @param val is the unsigned integer to be appended
 * @returns 0 for success or -1 for error.
 */
int
ccnb_append_tagged_binary_number(struct ccn_charbuf *cb,
                                 enum ccn_dtag dtag,
                                 uintmax_t val) {
    unsigned char buf[sizeof(val)];
    int pos;
    int res = 0;
    for (pos = sizeof(buf); val != 0 && pos > 0; val >>= 8)
        buf[--pos] = val & 0xff;
    res |= ccnb_append_tagged_blob(cb, dtag, buf+pos, sizeof(buf)-pos);
    return(res);
}

/**
 * Append a tagged UDATA string
 *
 * This is a ccnb-encoded element containing the UDATA as content
 * @param c is the buffer to append to.
 * @param dtag is the element's dtab
 * @param data points to the data
 * @param size is the size of the data, in bytes
 * @returns 0 for success or -1 for error.
 */
int
ccnb_append_tagged_udata(struct ccn_charbuf *c,
                         enum ccn_dtag dtag,
                         const void *data,
                         size_t size)
{
    int res;
    
    res = ccnb_element_begin(c, dtag);
    if (size != 0) {
        res |= ccn_charbuf_append_tt(c, size, CCN_UDATA);
        res |= ccn_charbuf_append(c, data, size);
    }
    res |= ccnb_element_end(c);
    return(res == 0 ? 0 : -1);
}

/**
 * Append a tagged UDATA string, with printf-style formatting
 *
 * This is a ccnb-encoded element containing UDATA as content.
 * @param c is the buffer to append to.
 * @param dtag is the element's dtab.
 * @param fmt is a printf-style format string, followed by its values
 * @returns 0 for success or -1 for error.
 */
int
ccnb_tagged_putf(struct ccn_charbuf *c,
                 enum ccn_dtag dtag, const char *fmt, ...)
{
    int res;
    int size;
    va_list ap;
    char *ptr;
    
    res = ccnb_element_begin(c, dtag);
    if (res < 0)
        return(-1);
    ptr = (char *)ccn_charbuf_reserve(c, strlen(fmt) + 20);
    if (ptr == NULL)
        return(-1);
    va_start(ap, fmt);
    size = vsnprintf(ptr + 2, (c->limit - c->length - 2), fmt, ap);
    va_end(ap);
    if (size < 0)
        return(-1);
    if (size > 0) {
        if (size >= (c->limit - c->length - 2))
            ptr = NULL;
        res |= ccn_charbuf_append_tt(c, size, CCN_UDATA);
        if (ptr == (char *)c->buf + c->length + 2)
            c->length += size;
        else if (ptr == (char *)c->buf + c->length + 1) {
            memmove(ptr - 1, ptr, size);
            c->length += size;
        }
        else {
            ptr = (char *)ccn_charbuf_reserve(c, size + 1);
            va_start(ap, fmt);
            size = vsnprintf(ptr, size + 1, fmt, ap);
            va_end(ap);
            if (size < 0)
                return(-1);
            c->length += size;
        }
    }
    res |= ccnb_element_end(c);
    return(res == 0 ? 0 : -1);    
}

/**
 * Append a representation of a Link to a charbuf.
 * @param buf is the output buffer where encoded link is written.
 * @param name is the ccnb-encoded name from ccn_name_init and friends.
 * @param label is a UTF-8 string in a ccn_charbuf.
 * @param linkAuthenticator is the ccnb-encoded LinkAuthenticator.
 * @returns 0 for success or -1 for error.
 */
int
ccnb_append_Link(struct ccn_charbuf *buf,
                 const struct ccn_charbuf *name,
                 const char *label,
                 const struct ccn_charbuf *linkAuthenticator
                 )
{
    int res = 0;
    
    res |= ccnb_element_begin(buf, CCN_DTAG_Link);
    res |= ccn_charbuf_append_charbuf(buf, name);
    if (label != NULL) {
        res |= ccnb_append_tagged_udata(buf, CCN_DTAG_Label, label, strlen(label));
    }
    if (linkAuthenticator != NULL) {
        res |= ccn_charbuf_append_charbuf(buf, linkAuthenticator);
    }
    res |= ccnb_element_end(buf);
    return(res == 0 ? 0 : -1);
}

