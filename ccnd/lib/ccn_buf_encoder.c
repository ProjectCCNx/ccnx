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

const char * ccn_content_name(enum ccn_content_type type) {
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

int ccn_encode_ContentObject(struct ccn_charbuf *c,
			     struct ccn_charbuf *Name,
			     struct ccn_charbuf *ContentAuthenticator,
			     struct ccn_charbuf *Signature,
			     const char *Content, int len) {
    int res = 0;
    res = ccn_charbuf_append_tt(c, CCN_DTAG_ContentObject, CCN_DTAG);
    if (res == -1) return(res);
    res = ccn_charbuf_append_charbuf(c, Name);
    if (res == -1) return(res);
    res = ccn_charbuf_append_charbuf(c, ContentAuthenticator);
    if (res == -1) return(res);
    res = ccn_charbuf_append_charbuf(c, Signature);
    if (res == -1) return(res);
    res = ccn_charbuf_append_tt(c, len, CCN_BLOB);
    if (res == -1) return(res);
    res = ccn_charbuf_append(c, Content, len);
    if (res == -1) return(res);
    res = ccn_charbuf_append_closer(c);
    if (res == -1) return(res);
    
    return res;
}
