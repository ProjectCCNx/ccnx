/*
 * ccn_buf_encoder.c
 *  
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * $Id$
 */

#include <string.h>
#include <stdio.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/indexbuf.h>

int
ccn_auth_create_default(struct ccn_charbuf *c,
		struct ccn_charbuf *signature,
		enum ccn_content_type Type,
		const struct ccn_charbuf *path,
		const void *content, size_t length)
{
    unsigned char fakesig[32] = "nooooogoooodsiiiiig";
    unsigned char fakepub[32] = {0};
    int res = 0;

    /* Fake timestamp is "2008-08-17T20:35:22Z" */
    res += ccn_auth_create(c, fakepub, sizeof(fakepub), 1219005322, 0, Type, NULL);

    res += ccn_charbuf_append_tt(signature, CCN_DTAG_Signature, CCN_DTAG);
    res += ccn_charbuf_append_tt(signature, CCN_DTAG_SignatureBits, CCN_DTAG);
    res += ccn_charbuf_append_tt(signature, sizeof(fakesig), CCN_BLOB);
    res += ccn_charbuf_append(signature, fakesig, sizeof(fakesig));
    res += ccn_charbuf_append_closer(signature);
    res += ccn_charbuf_append_closer(signature);

    return (res == 0 ? res : -1);
}
		
int
ccn_auth_create(struct ccn_charbuf *c,
                const void *publisher_key_id,	/* input, sha256 hash */
                size_t publisher_key_id_size, 	/* input, 32 for sha256 hashes */
                time_t sec,			/* input, dateTime seconds since epoch */
                int nanosec,			/* input, dateTime nanoseconds */
                enum ccn_content_type type,	/* input */
                const struct ccn_charbuf *key_locator)	/* input, optional, ccnb encoded */
{
    int res = 0;
    const char *typename = ccn_content_name(type);
    struct ccn_charbuf *datetime = ccn_charbuf_create();

    if (typename == NULL)
	return (-1);
    if (publisher_key_id == NULL || publisher_key_id_size != 32)
        return (-1);
    if (datetime == NULL) 
        return (-1);

    res += ccn_charbuf_append_tt(c, CCN_DTAG_ContentAuthenticator, CCN_DTAG);

    res += ccn_charbuf_append_tt(c, CCN_DTAG_PublisherKeyID, CCN_DTAG);
    res += ccn_charbuf_append_tt(c, publisher_key_id_size, CCN_BLOB);
    res += ccn_charbuf_append(c, publisher_key_id, publisher_key_id_size);
    res += ccn_charbuf_append_closer(c);

    res += ccn_charbuf_append_datetime(datetime, sec, nanosec);

    res += ccn_charbuf_append_tt(c, CCN_DTAG_Timestamp, CCN_DTAG);
    res += ccn_charbuf_append_tt(c, datetime->length, CCN_UDATA);
    res += ccn_charbuf_append_charbuf(c, datetime);
    res += ccn_charbuf_append_closer(c);

    res += ccn_charbuf_append_tt(c, CCN_DTAG_Type, CCN_DTAG);
    res += ccn_charbuf_append_tt(c, strlen(typename), CCN_UDATA);
    res += ccn_charbuf_append(c, typename, strlen(typename));
    res += ccn_charbuf_append_closer(c);

    if (key_locator != NULL) {
	/* key_locator is a sub-type that should already be encoded */
	res += ccn_charbuf_append_charbuf(c, key_locator);
    }
    
    res += ccn_charbuf_append_closer(c);

    return (res == 0 ? res : -1);
}

const char *
ccn_content_name(enum ccn_content_type type)
{
    switch (type) {
    // XXX - these do not match up with schema/ccn.xsd
    case CCN_CONTENT_FRAGMENT:
	return "FRAGMENT";
	break;
    case CCN_CONTENT_LINK:
	return "LINK";
	break;
    case CCN_CONTENT_COLLECTION:
	return "COLLECTION";
	break;
    case CCN_CONTENT_LEAF:
	return "LEAF";
	break;
    case CCN_CONTENT_SESSION:
	return "SESSION";
	break;
    case CCN_CONTENT_HEADER:
	return "HEADER";
	break;
    case CCN_CONTENT_KEY:
	return "KEY";
	break;
    default:
	return NULL;
    }
}

int ccn_encode_ContentObject(struct ccn_charbuf *ccnb,
			     const struct ccn_charbuf *Signature,
                             int foo, // to change procedure type
                             const struct ccn_charbuf *Name,
			     const struct ccn_charbuf *ContentAuthenticator,
			     const void *Content, int len) {
    struct ccn_charbuf *c = ccnb;
    int res = 0;

    /* Each of the input charbufs should be already encoded as a 
       sub-piece so just needs to be dropped in */

    res += ccn_charbuf_append_tt(c, CCN_DTAG_ContentObject, CCN_DTAG);
    res += ccn_charbuf_append_charbuf(c, Signature);
    res += ccn_charbuf_append_charbuf(c, Name);
    res += ccn_charbuf_append_charbuf(c, ContentAuthenticator);

    res += ccn_charbuf_append_tt(c, CCN_DTAG_Content, CCN_DTAG);
    res += ccn_charbuf_append_tt(c, len, CCN_BLOB);
    res += ccn_charbuf_append(c, Content, len);
    res += ccn_charbuf_append_closer(c);

    res += ccn_charbuf_append_closer(c);
    
    return (res == 0 ? res : -1);
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
