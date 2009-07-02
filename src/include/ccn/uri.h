/*
 * ccn/uri.h
 * 
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 *
 * ccn-scheme uri conversions
 *
 * $Id$
 */

#ifndef CCN_URI_DEFINED
#define CCN_URI_DEFINED
#include <ccn/charbuf.h>

/*
 * XXX - these names and signatures are a little confusing, and will
 * get reworked.  Please look at ccn_uri.c for details.
 */

/* Conversion from ccnb to uri */
int
ccn_uri_append(struct ccn_charbuf *c,
               const unsigned char *ccnb,
               size_t size,
               int includescheme);


/* Conversion from uri to ccnb form */
int ccn_name_from_uri(struct ccn_charbuf *c, const char *uri);

#endif
