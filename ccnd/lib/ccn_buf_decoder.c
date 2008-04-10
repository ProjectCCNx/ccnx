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

int
ccn_parse_required_tagged_BLOB(struct ccn_buf_decoder *d, enum ccn_dtag dtag)
{
    int res = -1;
    if (ccn_buf_match_dtag(d, dtag)) {
        res = d->decoder.element_index;
        ccn_buf_advance(d);
        if (ccn_buf_match_blob(d, NULL, NULL))
            ccn_buf_advance(d);
        ccn_buf_check_close(d);
    }
    else
        d->decoder.state = -__LINE__;
    if (d->decoder.state < 0)
        return (d->decoder.state);
    return(res);
}

int
ccn_parse_optional_tagged_BLOB(struct ccn_buf_decoder *d, enum ccn_dtag dtag)
{
    int res = -1;
    if (ccn_buf_match_dtag(d, dtag)) {
        res = d->decoder.element_index;
        ccn_buf_advance(d);
        if (ccn_buf_match_blob(d, NULL, NULL))
            ccn_buf_advance(d);
        ccn_buf_check_close(d);
    }
    if (d->decoder.state < 0)
        return (d->decoder.state);
    return(res);
}

int
ccn_parse_required_tagged_UDATA(struct ccn_buf_decoder *d, enum ccn_dtag dtag)
{
    int res = -1;
    if (ccn_buf_match_dtag(d, dtag)) {
        res = d->decoder.element_index;
        ccn_buf_advance(d);
        if (d->decoder.state >= 0 &&
            CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_UDATA)
            ccn_buf_advance(d);
        else
            d->decoder.state = -__LINE__;
        ccn_buf_check_close(d);
    }
    else
        d->decoder.state = -__LINE__;
    if (d->decoder.state < 0)
        return (-1);
    return(res);
}

struct parsed_Name {
    int start;
    int size;
    int ncomp;
};

int
ccn_parse_Name(struct ccn_buf_decoder *d, struct parsed_Name *x)
{
    int ncomp = 0;
    int res = -1;
    if (ccn_buf_match_dtag(d, CCN_DTAG_Name)) {
        res = d->decoder.element_index;
        ccn_buf_advance(d);
        while (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
            ncomp += 1;
            ccn_buf_advance(d);
            if (ccn_buf_match_blob(d, NULL, NULL)) {
                ccn_buf_advance(d);
            }
            ccn_buf_check_close(d);
        }
        ccn_buf_check_close(d);
    }
    if (res >= 0 && d->decoder.state >= 0) {
        x->start = res;
        x->size = d->decoder.token_index - res;
        x->ncomp = ncomp;
        return (res);
    }
    return(-1);
}

struct parsed_KeyName {
    int Name;
    int PublisherID;
};

int
ccn_parse_KeyName(struct ccn_buf_decoder *d, struct parsed_KeyName *x)
{
    int res = -1;
    struct parsed_Name name;
    if (ccn_buf_match_dtag(d, CCN_DTAG_KeyName)) {
        res = d->decoder.element_index;
        ccn_buf_advance(d);
        x->Name = ccn_parse_Name(d, &name);
        x->PublisherID = ccn_parse_optional_tagged_BLOB(d, CCN_DTAG_PublisherID);
        ccn_buf_check_close(d);
    }
    else
        d->decoder.state = -__LINE__;
    if (d->decoder.state < 0)
        return (d->decoder.state);
    return(res);
}

struct parsed_ContentAuthenticator {
    int PublisherKeyID;
    int NameComponentCount;
    int Timestamp;
    int Type;
    int KeyLocator;
    int ContentDigest;
};

int
ccn_parse_ContentAuthenticator(struct ccn_buf_decoder *d,
    struct parsed_ContentAuthenticator *x)
{
    int res = -1;
    if (ccn_buf_match_dtag(d, CCN_DTAG_ContentAuthenticator)) {
        res = d->decoder.element_index;
        ccn_buf_advance(d);
        x->PublisherKeyID = ccn_parse_required_tagged_BLOB(d, CCN_DTAG_PublisherKeyID);
        x->NameComponentCount = ccn_parse_required_tagged_UDATA(d, CCN_DTAG_NameComponentCount);
        x->Timestamp = ccn_parse_required_tagged_UDATA(d, CCN_DTAG_Timestamp);
        x->Type = ccn_parse_required_tagged_UDATA(d, CCN_DTAG_Type);
        if (ccn_buf_match_dtag(d, CCN_DTAG_KeyLocator)) {
            struct parsed_KeyName keyname = {-1, -1};
            x->KeyLocator = d->decoder.element_index;
            ccn_buf_advance(d);
            if (ccn_buf_match_dtag(d, CCN_DTAG_Key))
                (void)ccn_parse_required_tagged_BLOB(d, CCN_DTAG_Key);
            else if (ccn_buf_match_dtag(d, CCN_DTAG_Certificate))
                (void)ccn_parse_required_tagged_BLOB(d, CCN_DTAG_Certificate);
            else
                (void)ccn_parse_KeyName(d, &keyname);
            ccn_buf_check_close(d);
        }
        x->ContentDigest = ccn_parse_required_tagged_BLOB(d, CCN_DTAG_ContentDigest);
        ccn_buf_check_close(d);
    }
    else
        d->decoder.state = -__LINE__;
    if (d->decoder.state < 0)
        return (d->decoder.state);
    return(res);

}

int
ccn_parse_ContentObject(unsigned char *msg, size_t size,
                   struct ccn_parsed_ContentObject *x)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder, msg, size);
    int res = -1;
    if (ccn_buf_match_dtag(d, CCN_DTAG_ContentObject)) {
        struct parsed_Name name;
        struct parsed_ContentAuthenticator auth;
        res = d->decoder.element_index;
        ccn_buf_advance(d);
        x->Name = ccn_parse_Name(d, &name);
        x->ContentAuthenticator = ccn_parse_ContentAuthenticator(d, &auth);
        x->Signature = ccn_parse_required_tagged_BLOB(d, CCN_DTAG_Signature);
        x->Content = ccn_parse_required_tagged_BLOB(d, CCN_DTAG_Content);
        ccn_buf_check_close(d);
    }
    else
        d->decoder.state = -__LINE__;
    if (d->decoder.index != size || !CCN_FINAL_DSTATE(d->decoder.state))
        return (CCN_DSTATE_ERR_CODING);
    return(res);
}

