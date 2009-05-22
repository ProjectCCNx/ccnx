/*
 * ccn_name_util.c
 *  
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * $Id$
 */

#include <string.h>
#include <stdlib.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/indexbuf.h>

int
ccn_name_init(struct ccn_charbuf *c)
{
    int res;
    c->length = 0;
    res = ccn_charbuf_append_tt(c, CCN_DTAG_Name, CCN_DTAG);
    if (res == -1) return(res);
    res = ccn_charbuf_append_closer(c);
    return(res);
}

int
ccn_name_append(struct ccn_charbuf *c, const void *component, size_t n)
{
    int res;
    const unsigned char closer[2] = {CCN_CLOSE, CCN_CLOSE};
    if (c->length < 2 || c->buf[c->length-1] != closer[1])
        return(-1);
    c->length -= 1;
    ccn_charbuf_reserve(c, n + 8);
    res = ccn_charbuf_append_tt(c, CCN_DTAG_Component, CCN_DTAG);
    if (res == -1) return(res);
    res = ccn_charbuf_append_tt(c, n, CCN_BLOB);
    if (res == -1) return(res);
    res = ccn_charbuf_append(c, component, n);
    if (res == -1) return(res);
    res = ccn_charbuf_append(c, closer, sizeof(closer));
    return(res);
}

int 
ccn_name_append_str(struct ccn_charbuf *c, const char *s)
{
    return (ccn_name_append(c, s, strlen(s)));
}

int
ccn_name_append_numeric(struct ccn_charbuf *c,
                        enum ccn_marker marker, uintmax_t value)
{
    uintmax_t v;
    int i;
    char b[32];
    
    for (v = value, i = sizeof(b); v != 0 && i > 0; i--, v >>= 8)
        b[i-1] = v & 0xff;
    if (i < 1)
        return(-1);
    if (marker >= 0)
        b[--i] = marker;
    return (ccn_name_append(c, b + i, sizeof(b) - i));
}

int
ccn_name_append_components(struct ccn_charbuf *c,
                           const unsigned char *ccnb,
                           size_t start, size_t stop)
{
    int res;
    if (c->length < 2 || start > stop)
        return(-1);
    c->length -= 1;
    ccn_charbuf_reserve(c, stop - start + 1);
    res = ccn_charbuf_append(c, ccnb + start, stop - start);
    if (res == -1) return(res);
    res = ccn_charbuf_append_closer(c);
    return(res);
}

int
ccn_name_comp_get(const unsigned char *data,
                  const struct ccn_indexbuf *indexbuf,
                  unsigned int i,
                  const unsigned char **comp, size_t *size)
{
    int len;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    /* indexbuf should have an extra value marking end of last component,
       so we need to use last 2 values */
    if (indexbuf->n < 2 || i > indexbuf->n - 2) {
	/* There isn't a component at this index */
	return(-1);
    }
    len = indexbuf->buf[i + 1]-indexbuf->buf[i];
    d = ccn_buf_decoder_start(&decoder, data + indexbuf->buf[i], len);
    if (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
	ccn_buf_advance(d);
	if (ccn_buf_match_blob(d, comp, size))
	    return(0);
	*comp = d->buf + d->decoder.index;
        *size = 0;
        ccn_buf_check_close(d);
        if (d->decoder.state >= 0)
            return(0);
    }
    return(-1);
}

int
ccn_name_comp_strcmp(const unsigned char *data,
                     const struct ccn_indexbuf *indexbuf,
                     unsigned int i, const char *val)
{
    const unsigned char *comp_ptr;
    size_t comp_size;

    // XXX - We probably want somewhat different semantics in the API -
    // comparing a string against a longer string with a 0 byte should
    // not claim equality.
    if (ccn_name_comp_get(data, indexbuf, i, &comp_ptr, &comp_size) == 0)
	return(strncmp(val, (const char *)comp_ptr, comp_size));
    /* Probably no such component, say query is greater-than */
    return(1);
}

int
ccn_name_split(struct ccn_charbuf *c, struct ccn_indexbuf *components)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    d = ccn_buf_decoder_start(&decoder, c->buf, c->length);
    return(ccn_parse_Name(d, components));
}

int
ccn_name_chop(struct ccn_charbuf *c, struct ccn_indexbuf *components, int n)
{
    if (components == NULL) {
        int res;
        components = ccn_indexbuf_create();
        if (components == NULL)
            return(-1);
        res = ccn_name_split(c, components);
        if (res >= 0)
            res = ccn_name_chop(c, components, n);
        ccn_indexbuf_destroy(&components);
        return(res);
    }
    if (components->n == 0 || components->buf[components->n-1] + 1 != c->length)
        return(-1);
    if (n < 0)
        n += (components->n - 1); /* APL-style indexing */
    if (n < 0)
        return(-1);
    if (n < components->n) {
        c->length = components->buf[n];
        ccn_charbuf_append_value(c, CCN_CLOSE, 1);
        components->n = n + 1;
        return(n);
    }
    return(-1);
}

