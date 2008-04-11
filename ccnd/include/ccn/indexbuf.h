/*
 * ccn/indexbuf.h
 * 
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * 
 * Expandable buffer of non-negative values
 *
 * $Id$
 */

#ifndef CCN_INDEXBUF_DEFINED
#define CCN_INDEXBUF_DEFINED

#include <stddef.h>

struct ccn_indexbuf {
    size_t n;
    size_t limit;
    size_t *buf;
};

struct ccn_indexbuf *ccn_indexbuf_create(void);
void ccn_indexbuf_destroy(struct ccn_indexbuf **cbp);
size_t *ccn_indexbuf_reserve(struct ccn_indexbuf *c, size_t n);
int ccn_indexbuf_append(struct ccn_indexbuf *c, const size_t *p, size_t n);
int ccn_indexbuf_append_element(struct ccn_indexbuf *c, size_t v);

#endif
