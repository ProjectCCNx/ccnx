/**
 * @file ccn_name_util.c
 * @brief Support for manipulating ccnb-encoded Names.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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
#include <string.h>
#include <stdlib.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/indexbuf.h>
#include <ccn/random.h>

/**
 * Reset charbuf to represent an empty Name in binary format.
 * @returns 0, or -1 for error.
 */
int
ccn_name_init(struct ccn_charbuf *c)
{
    int res;
    c->length = 0;
    res = ccnb_element_begin(c, CCN_DTAG_Name);
    if (res == -1) return(res);
    res = ccnb_element_end(c);
    return(res);
}

/**
 * Add a Component to a Name.
 *
 * The component is an arbitrary string of n octets, no escaping required.
 * @returns 0, or -1 for error.
 */
int
ccn_name_append(struct ccn_charbuf *c, const void *component, size_t n)
{
    int res;
    const unsigned char closer[2] = {CCN_CLOSE, CCN_CLOSE};
    if (c->length < 2 || c->buf[c->length-1] != closer[1])
        return(-1);
    c->length -= 1;
    ccn_charbuf_reserve(c, n + 8);
    res = ccnb_append_tagged_blob(c, CCN_DTAG_Component, component, n);
    ccnb_element_end(c);
    return(res);
}

/**
 * Add a Component that is a NUL-terminated string.
 *
 * The component added consists of the bytes of the string without the NUL.
 * This function is convenient for those applications that construct 
 * component names from simple strings.
 * @returns 0, or -1 for error.
 */
int 
ccn_name_append_str(struct ccn_charbuf *c, const char *s)
{
    return(ccn_name_append(c, s, strlen(s)));
}

/**
 * Add a binary Component to a ccnb-encoded Name
 *
 * These are special components used for marking versions, fragments, etc.
 * @returns 0, or -1 for error
 * see doc/technical/NameConventions.html
 */
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
    return(ccn_name_append(c, b + i, sizeof(b) - i));
}

/**
 * Add nonce Component to ccnb-encoded Name
 *
 * Uses %C1.N namespace.
 * @returns 0, or -1 for error
 * see doc/technical/NameConventions.html
 */
int
ccn_name_append_nonce(struct ccn_charbuf *c)
{
    const unsigned char pre[4] = { CCN_MARKER_CONTROL, '.', 'N', 0 };
    unsigned char b[15];
    
    memcpy(b, pre, sizeof(pre));
    ccn_random_bytes(b + sizeof(pre), sizeof(b) - sizeof(pre));
    return(ccn_name_append(c, b, sizeof(b)));
}

/**
 * Add sequence of ccnb-encoded Components to a ccnb-encoded Name.
 *
 * start and stop are offsets from ccnb
 * @returns 0, or -1 for obvious error
 */
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
    res = ccnb_element_end(c);
    return(res);
}

/**
 * Extract a pointer to and size of component at
 * given index i.  The first component is index 0.
 * @returns 0, or -1 for error.
 */
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

/**
 * Find Component boundaries in a ccnb-encoded Name.
 *
 * Thin veneer over ccn_parse_Name().
 * components arg may be NULL to just do a validity check
 *
 * @returns -1 for error, otherwise the number of Components.
 */
int
ccn_name_split(const struct ccn_charbuf *c, struct ccn_indexbuf *components)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    d = ccn_buf_decoder_start(&decoder, c->buf, c->length);
    return(ccn_parse_Name(d, components));
}

/**
 * Chop the name down to n components.
 * @param c contains a ccnb-encoded Name
 * @param components may be NULL; if provided it must be consistent with
 *        some prefix of the name, and is updated accordingly.
 * @param n is the number or components to leave, or, if negative, specifies
 *        how many components to remove,
          e.g. -1 will remove just the last component.
 * @returns -1 for error, otherwise the new number of Components
 */
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
    /* Fix up components if needed. We could be a little smarter about this. */
    if (components->n == 0 || components->buf[components->n-1] + 1 != c->length)
        if (ccn_name_split(c, components) < 0)
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

/**
 * Advance the last Component of a Name to the next possible value.
 * @param c contains a ccnb-encoded Name to be updated.
 * @returns -1 for error, otherwise the number of Components
 */
int
ccn_name_next_sibling(struct ccn_charbuf *c)
{
    int res = -1;
    struct ccn_indexbuf *ndx;
    unsigned char *lastcomp = NULL;
    size_t lastcompsize = 0;
    size_t i;
    int carry;
    struct ccn_charbuf *newcomp;

    ndx = ccn_indexbuf_create();
    if (ndx == NULL) goto Finish;
    res = ccn_name_split(c, ndx);
    if (res <= 0) {
        res = -1;
        goto Finish;
    }
    res = ccn_ref_tagged_BLOB(CCN_DTAG_Component, c->buf,
        ndx->buf[res-1], ndx->buf[res],
        (const unsigned char **)&lastcomp,
        &lastcompsize);
    if (res < 0) goto Finish;
    for (carry = 1, i = lastcompsize; carry && i > 0; i--) {
        carry = (((++lastcomp[i-1]) & 0xFF) == 0x00);
    }
    if (carry) {
        newcomp = ccn_charbuf_create();
        res |= ccn_charbuf_append_value(newcomp, 0, 1);
        res |= ccn_charbuf_append(newcomp, lastcomp, lastcompsize);
        res |= ccn_name_chop(c, ndx, ndx->n - 2);
        res |= ccn_name_append(c, newcomp->buf, newcomp->length);
        ccn_charbuf_destroy(&newcomp);
        if (res < 0) goto Finish;
    }
    res = ndx->n - 1;
Finish:
    ccn_indexbuf_destroy(&ndx);
    return(res);
}
