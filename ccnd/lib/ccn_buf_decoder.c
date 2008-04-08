/*
 * ccn_buf_decoder.c
 *  
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * $Id$
 */

#include <string.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>

struct ccn_buf_decoder *
ccn_buf_decoder_start(struct ccn_buf_decoder *d, unsigned char *buf, size_t size)
{
    memset(&d->decoder, 0, sizeof(d->decoder));
    d->decoder.state |= CCN_DSTATE_PAUSE;
    d->buf = buf;
    d->size = size;
    ccn_skeleton_decode(&d->decoder, buf, size);
    return(d);
}

void
ccn_buf_advance(struct ccn_buf_decoder *d)
{
    ccn_skeleton_decode(&d->decoder,
                        d->buf + d->decoder.index,
                        d->size - d->decoder.index);
}

int
ccn_buf_match_dtag(struct ccn_buf_decoder *d, enum ccn_dtag dtag)
{
    return (d->decoder.state >= 0 &&
            CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_DTAG &&
            d->decoder.numval == dtag);
}

int
ccn_buf_match_blob(struct ccn_buf_decoder *d, unsigned char **bufp, size_t *sizep)
{
    if (d->decoder.state >= 0 && CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_BLOB) {
        if (bufp != NULL)
            *bufp = d->buf + d->decoder.index;
        if (sizep != NULL)
            *sizep = d->decoder.numval;
        return (1);
    }
    return(0);
}

int
ccn_buf_match_udata(struct ccn_buf_decoder *d, const char *s)
{
    size_t len = strlen(s);
    return (d->decoder.state >= 0 &&
            CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_UDATA &&
            d->decoder.numval == len &&
            0 == memcmp(d->buf + d->decoder.index, s, len));
}

int
ccn_buf_match_attr(struct ccn_buf_decoder *d, const char *s)
{
    size_t len = strlen(s);
    return (d->decoder.state >= 0 &&
            CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_ATTR &&
            d->decoder.numval == len &&
            0 == memcmp(d->buf + d->decoder.index, s, len));
}

void
ccn_buf_check_close(struct ccn_buf_decoder *d)
{
    if (d->decoder.state >= 0) {
        if (CCN_GET_TT_FROM_DSTATE(d->decoder.state) != CCN_NO_TOKEN)
            d->decoder.state = CCN_DSTATE_ERR_NEST;
        else
            ccn_buf_advance(d);
    }
}

int
ccn_parse_interest(unsigned char *msg, size_t size,
                   struct ccn_parsed_interest *interest)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder, msg, size);
    int ncomp = 0;
    if (ccn_buf_match_dtag(d, CCN_DTAG_Interest)) {
        ccn_buf_advance(d);
        if (ccn_buf_match_dtag(d, CCN_DTAG_Name)) {
            interest->name_start = d->decoder.element_index;
            ccn_buf_advance(d);
            while (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
                ncomp += 1;
                ccn_buf_advance(d);
                if (ccn_buf_match_blob(d, NULL, NULL)) {
                    ccn_buf_advance(d);
                }
                ccn_buf_check_close(d);
            }
            interest->name_size = d->decoder.index - interest->name_start;
            ccn_buf_check_close(d);
        }
        else
            return (-__LINE__);
        interest->pubid_start = interest->pubid_size = 0;
        if (ccn_buf_match_dtag(d, CCN_DTAG_PublisherID)) {
            interest->pubid_start = d->decoder.element_index;
            ccn_buf_advance(d);
            if (!ccn_buf_match_attr(d, "type"))
                return (-__LINE__);
            ccn_buf_advance(d);
            if (!(ccn_buf_match_udata(d, "KEY") ||
                  ccn_buf_match_udata(d, "CERTIFICATE") ||
                  ccn_buf_match_udata(d, "ISSUER_KEY") ||
                  ccn_buf_match_udata(d, "ISSUER_CERTIFICATE")))
                return (-__LINE__);
            ccn_buf_advance(d);
            if (!ccn_buf_match_blob(d, NULL, NULL))
                return (-__LINE__);
            ccn_buf_advance(d);
            interest->pubid_size = d->decoder.index - interest->pubid_start;
            ccn_buf_check_close(d);
        }
        interest->nonce_start = interest->nonce_size = 0;
        if (ccn_buf_match_dtag(d, CCN_DTAG_Nonce)) {
            interest->nonce_start = d->decoder.element_index;
            ccn_buf_advance(d);
            if (!ccn_buf_match_blob(d, NULL, NULL))
                return (-__LINE__);
            ccn_buf_advance(d);
            interest->pubid_size = d->decoder.index - interest->pubid_start;
            ccn_buf_check_close(d);
        }
        ccn_buf_check_close(d);
    }
    if (d->decoder.state < 0)
        return(d->decoder.state);
    if (d->decoder.index != size || !CCN_FINAL_DSTATE(d->decoder.state))
        return (CCN_DSTATE_ERR_CODING);
    return (ncomp);
}

