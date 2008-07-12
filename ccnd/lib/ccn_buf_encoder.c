/*
 * ccn_buf_encoder.c
 *  
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * $Id$
 */

#include <string.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/indexbuf.h>

const char * ccn_content_name(enum ccn_content_type type)
{
    switch (type) {
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
