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
    struct ccn_charbuf *pub_key_id = ccn_charbuf_create();
    struct ccn_charbuf *timestamp = ccn_charbuf_create();
    unsigned char fakesig[32] = "nooooogoooodsiiiiig";
    unsigned char fakepub[32] = {0};
    int res = 0;

    ccn_charbuf_append(pub_key_id, fakepub, sizeof(fakepub));
    ccn_charbuf_putf(timestamp, "2008-08-17T20:35:22Z");
    res += ccn_auth_create(c, pub_key_id, timestamp, Type, NULL);

    res += ccn_charbuf_append_tt(signature, CCN_DTAG_Signature, CCN_DTAG);
    res += ccn_charbuf_append_tt(signature, CCN_DTAG_SignatureBits, CCN_DTAG);
    res += ccn_charbuf_append_tt(signature, sizeof(fakesig), CCN_BLOB);
    res += ccn_charbuf_append(signature, fakesig, sizeof(fakesig));
    res += ccn_charbuf_append_closer(signature);
    res += ccn_charbuf_append_closer(signature);

    ccn_charbuf_destroy(&pub_key_id);
    ccn_charbuf_destroy(&timestamp);
    return (res == 0 ? res : -1);
}
		
int
ccn_auth_create(struct ccn_charbuf *c,
	      struct ccn_charbuf *PublisherKeyID,
	      struct ccn_charbuf *Timestamp,
	      enum ccn_content_type Type,
	      struct ccn_charbuf *KeyLocator)
{
    int res = 0;
    const char *typename = ccn_content_name(Type);
    if (typename == NULL) {
	return (-1);
    }
    
    res += ccn_charbuf_append_tt(c, CCN_DTAG_ContentAuthenticator, CCN_DTAG);

    res += ccn_charbuf_append_tt(c, CCN_DTAG_PublisherKeyID, CCN_DTAG);
    res += ccn_charbuf_append_tt(c, PublisherKeyID->length, CCN_BLOB);
    res += ccn_charbuf_append_charbuf(c, PublisherKeyID);
    res += ccn_charbuf_append_closer(c);

    res += ccn_charbuf_append_tt(c, CCN_DTAG_Timestamp, CCN_DTAG);
    res += ccn_charbuf_append_tt(c, Timestamp->length, CCN_UDATA);
    res += ccn_charbuf_append_charbuf(c, Timestamp);
    res += ccn_charbuf_append_closer(c);

    res += ccn_charbuf_append_tt(c, CCN_DTAG_Type, CCN_DTAG);
    res += ccn_charbuf_append_tt(c, strlen(typename), CCN_UDATA);
    res += ccn_charbuf_append(c, typename, strlen(typename));
    res += ccn_charbuf_append_closer(c);

    if (KeyLocator != NULL) {
	/* KeyLocator is a sub-type that should already be encoded */
	res += ccn_charbuf_append_charbuf(c, KeyLocator);
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
