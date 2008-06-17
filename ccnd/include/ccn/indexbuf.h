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

/***********************************
 * Convenience functions for processing individual components based on 
 * indexbuf, especially as strings (though components need not be 
 * strings)
 */

/*
 * ccn_indexbuf_comp_strcmp: perform strcmp of given val against 
 * component.  Returns -1, 0, or 1 if val is less than, equal to,
 * or greater than the component at given index (counting from 0).
 * Safe even on binary components, though the result may not be useful.
 */
int ccn_indexbuf_comp_strcmp(const char *data, const struct ccn_indexbuf* indexbuf, unsigned int index, const char *val);

/*
 * ccn_indexbuf_comp_strdup: return a copy of component at given index
 * as a string, that is, it will be terminated by \0 even if the original
 * component was not.  The first component is index 0.
 * Caller is responsible to free returned buffer containing copy.
 */
char * ccn_indexbuf_comp_strdup(const char *data, const struct ccn_indexbuf *indexbuf, unsigned int index);

#endif
