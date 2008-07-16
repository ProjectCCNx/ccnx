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

static int
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

char *
ccn_name_comp_strdup(const unsigned char *data,
                     const struct ccn_indexbuf *indexbuf,
                     unsigned int i)
{
    char * result = NULL;
    const unsigned char * comp_ptr;
    size_t comp_size;

    if (ccn_name_comp_get(data, indexbuf, i, &comp_ptr, &comp_size) == 0) {
	result = calloc(1, comp_size + 1); // XXX - [mfp] - this is the only place (I think) that the client is responsible for directly calling free() on something that we allocated.  This should be fixed!
	if (result != NULL) {
	    memcpy(result, comp_ptr, comp_size);
	    /* Ensure that result is null-terminated */
	    result[comp_size] = '\0';
	}
    }
    return(result);
}

