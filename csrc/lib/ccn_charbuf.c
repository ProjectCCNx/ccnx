/**
 * @file ccn_charbuf.c
 * @brief Support expandable buffer for counted sequences of arbitrary bytes.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2008, 2009, 2013 Palo Alto Research Center, Inc.
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
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <ccn/charbuf.h>

struct ccn_charbuf *
ccn_charbuf_create(void)
{
    struct ccn_charbuf *c;
    c = calloc(1, sizeof(*c));
    return(c);
}

struct ccn_charbuf *
ccn_charbuf_create_n(size_t n)
{
    struct ccn_charbuf *c;
    c = malloc(sizeof(*c));
    if (c == NULL) return (NULL);
    c->length = 0;
    c->limit = n;
    if (n == 0) {
        c->buf = NULL;
        return(c);
    }
    c->buf = malloc(n);
    if (c->buf == NULL) {
        free(c);
        c = NULL;
    }
    return(c);
}

void
ccn_charbuf_destroy(struct ccn_charbuf **cbp)
{
    struct ccn_charbuf *c = *cbp;
    if (c != NULL) {
        if (c->buf != NULL)
            free(c->buf);
        free(c);
        *cbp = NULL;
    }
}

/*
 * ccn_charbuf_reserve: expand buffer as necessary to hold n more chars
 */
unsigned char *
ccn_charbuf_reserve(struct ccn_charbuf *c, size_t n)
{
    size_t newsz = n + c->length;
    unsigned char *buf = c->buf;
    if (newsz < n)
        return(NULL);
    if (newsz > c->limit) {
        if (2 * c->limit > newsz)
            newsz = 2 * c->limit;
#ifdef CCN_NOREALLOC
        buf = malloc(newsz);
        if (buf == NULL)
            return(NULL);
        memcpy(buf, c->buf, c->limit);
        free(c->buf);
#else
        buf = realloc(c->buf, newsz);
        if (buf == NULL)
            return(NULL);
#endif
        memset(buf + c->limit, 0, newsz - c->limit);
        c->buf = buf;
        c->limit = newsz;
    }
    buf += c->length;
    return(buf);
}

void ccn_charbuf_reset(struct ccn_charbuf *c)
{
    if (c == NULL) {
      return;
    } 
    c->length = 0;
}

int
ccn_charbuf_append(struct ccn_charbuf *c, const void *p, size_t n)
{
    unsigned char *dst = ccn_charbuf_reserve(c, n);
    if (dst == NULL)
        return(-1);
    memcpy(dst, p, n);
    c->length += n;
    return(0);
}

int
ccn_charbuf_append_value(struct ccn_charbuf *c, unsigned val, unsigned n)
{
    unsigned char *dst;
    unsigned i;
    if (n > sizeof(val))
        return(-1);
    dst = ccn_charbuf_reserve(c, n);
    if (dst == NULL)
        return(-1);
    for (i = 0; i < n; i++)
        dst[i] = (unsigned char)(val >> (8 * (n-1-i)));
    c->length += n;
    return(0);
}

int
ccn_charbuf_append_charbuf(struct ccn_charbuf *c, const struct ccn_charbuf *in)
{
  return(ccn_charbuf_append(c, in->buf, in->length));
}

int
ccn_charbuf_append_string(struct ccn_charbuf *c, const char *s)
{
  return(ccn_charbuf_append(c, s, strlen(s)));
}

int
ccn_charbuf_putf(struct ccn_charbuf *c, const char *fmt, ...)
{
    int sz;
    va_list ap;
    char *buf;
    buf = (char *)ccn_charbuf_reserve(c, strlen(fmt) + 10); /* estimate */
    if (buf == NULL) return(-1);
    va_start(ap, fmt);
    sz = vsnprintf(buf, c->limit - c->length, fmt, ap);
    va_end(ap);
    if (sz < 0)
        return(sz);
    if (c->length + sz < c->limit) {
        c->length += sz;
        return(sz);
    }
    buf = (char *)ccn_charbuf_reserve(c, sz + 1); /* accurate */
    if (buf == NULL) return(-1);
    va_start(ap, fmt);
    sz = vsnprintf(buf, c->limit - c->length, fmt, ap);
    va_end(ap);
    if (c->length + sz < c->limit) {
        c->length += sz;
        return(sz);
    }
    return(-1);
}

/* This formats time into xs:dateTime format */
int
ccn_charbuf_append_datetime(struct ccn_charbuf *c, time_t secs, int nsecs)
{
    char timestring[32];
    int timelen;
    struct tm time_tm;
    int res;

    timelen = strftime(timestring, sizeof(timestring),
                       "%FT%T", gmtime_r(&secs, &time_tm));
    if (timelen >= sizeof(timestring))
        return(-1);
    if (nsecs != 0) {
        if (nsecs < 0 || nsecs >= 1000000000)
            return(-1);
        timelen += snprintf(&timestring[timelen], sizeof(timestring) - timelen,
                            ".%09d", nsecs);
        if (timelen >= sizeof(timestring))
            return(-1);
        while (timestring[timelen - 1] == '0') timelen--;
    }
    timestring[timelen++] = 'Z';
    res = ccn_charbuf_append(c, timestring, timelen);
    return (res);
}

char *
ccn_charbuf_as_string(struct ccn_charbuf *c)
{
    unsigned char *r;
    r = ccn_charbuf_reserve(c, 1);
    if (r == NULL)
        return(NULL);
    r[0] = 0;
    return((char *)c->buf);
}
