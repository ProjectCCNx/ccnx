/**
 * @file ccn/charbuf.h
 * 
 * Expandable character buffer for counted sequences of arbitrary octets.
 *
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
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
 * ccn_charbuf_create_n: allocate a new charbuf with a preallocated but
 *      uninitialized buffer
 * ccn_charbuf_destroy: destroy a charbuf
 */
struct ccn_charbuf *ccn_charbuf_create(void);
struct ccn_charbuf *ccn_charbuf_create_n(size_t n);
void ccn_charbuf_destroy(struct ccn_charbuf **cbp);

/*
 * ccn_charbuf_reserve: reserve some space in the buffer
 * Grows c->buf if needed and returns a pointer to the new region.
 * Does not modify c->length
 */ 
unsigned char *ccn_charbuf_reserve(struct ccn_charbuf *c, size_t n);

/*
 * ccn_charbuf_reset: reset to empty for reuse
 * Sets c->length to 0
 */
void ccn_charbuf_reset(struct ccn_charbuf *c);

/*
 * ccn_charbuf_append: append character content
 */ 
int ccn_charbuf_append(struct ccn_charbuf *c, const void *p, size_t n);

/*
 * ccn_charbuf_append: append n bytes of val
 * The n low-order bytes are appended in network byte order (big-endian) 
 */ 
int ccn_charbuf_append_value(struct ccn_charbuf *c, unsigned val, unsigned n);


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

#define CCN_DATETIME_PRECISION_USEC 6
#define CCN_DATETIME_PRECISION_MAX 6

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
