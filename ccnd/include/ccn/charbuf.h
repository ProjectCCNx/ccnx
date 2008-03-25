/*
 * ccn/charbuf.h
 * 
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * 
 * Expandable character buffer
 *
 * $Id$
 */

#ifndef CCN_CHARBUF_DEFINED
#define CCN_CHARBUF_DEFINED

#include <stddef.h>

struct ccn_charbuf {
    size_t length;
    size_t limit;
    unsigned char *buf;
};

struct ccn_charbuf *ccn_charbuf_create(void);
void ccn_charbuf_destroy(struct ccn_charbuf **cbp);
unsigned char *ccn_charbuf_reserve(struct ccn_charbuf *c, size_t n);
int ccn_charbuf_append(struct ccn_charbuf *c, const void *p, size_t n);

#endif
