/*
 * ccn/indexbuf.h
 * 
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc. All rights reserved.
 * 
 * Expandable buffer of non-negative values
 *
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
int ccn_indexbuf_member(struct ccn_indexbuf *x, size_t val);
void ccn_indexbuf_remove_element(struct ccn_indexbuf *x, size_t val);
int ccn_indexbuf_set_insert(struct ccn_indexbuf *x, size_t val);
int ccn_indexbuf_remove_first_match(struct ccn_indexbuf *x, size_t val);
void ccn_indexbuf_move_to_end(struct ccn_indexbuf *x, size_t val);

#endif
