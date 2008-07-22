/*
 * ccn/charbuf.h
 * 
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * 
 * Expandable character buffer for counted sequences of arbitrary octets
 *
 * $Id$
 */

#ifndef CCN_CHARBUF_DEFINED
#define CCN_CHARBUF_DEFINED

#include <stddef.h>
#include <time.h>

struct ccn_charbuf {
    size_t length;
    size_t limit;
    unsigned char *buf;
};

/*
 * ccn_charbuf_create:  allocate a new charbuf
 * ccn_charbuf_destroy: destroy a charbuf
 */
struct ccn_charbuf *ccn_charbuf_create(void);
void ccn_charbuf_destroy(struct ccn_charbuf **cbp);

/*
 * ccn_charbuf_reserve: reserve some space in the buffer
 * Grows c->buf if needed and returns a pointer to the new region.
 * Does not modify c->length
 */ 
unsigned char *ccn_charbuf_reserve(struct ccn_charbuf *c, size_t n);

/*
 * ccn_charbuf_append: append character content
 */ 
int ccn_charbuf_append(struct ccn_charbuf *c, const void *p, size_t n);

/*
 * ccn_charbuf_append_charbuf: append content from another charbuf
 */ 
int ccn_charbuf_append_charbuf(struct ccn_charbuf *c, const struct ccn_charbuf *i);

/*
 * ccn_charbuf_append: append a string
 * Sometimes you have a null-terminated string in hand...
 */ 
int ccn_charbuf_append_string(struct ccn_charbuf *c, const char *s);

/*
 * ccn_charbuf_putf: formatting output
 * Use this in preference to snprintf to simplify bookkeeping.
 */ 
int ccn_charbuf_putf(struct ccn_charbuf *c, const char *fmt, ...);

/*
 * ccn_charbuf_append_datetime: append a date/time string
 * Appends a dateTime string in canonical form according to
 * http://www.w3.org/TR/xmlschema-2/
 * Return value is 0, or -1 for error.
 * example: 2008-07-22T17:33:14.109Z
 */ 
int ccn_charbuf_append_datetime(struct ccn_charbuf *c, time_t secs, int nsecs);

/*
 * ccn_charbuf_as_string: view charbuf contents as a string
 * This assures that c->buf has a null termination, and simply
 * returns the pointer into the buffer.  If the result needs to
 * persist beyond the next operation on c, the caller is
 * responsible for copying it.
 */ 
char *ccn_charbuf_as_string(struct ccn_charbuf *c);

#endif
