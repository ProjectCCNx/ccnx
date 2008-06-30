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
#include <ccn/indexbuf.h>

// XXX - stdio is temporary
#include <stdio.h>

struct ccn_buf_decoder *
ccn_buf_decoder_start(struct ccn_buf_decoder *d, const unsigned char *buf, size_t size)
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
ccn_buf_match_some_dtag(struct ccn_buf_decoder *d)
{
    return(d->decoder.state >= 0 &&
           CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_DTAG);
}

int
ccn_buf_match_some_blob(struct ccn_buf_decoder *d)
{
    return(d->decoder.state >= 0 && CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_BLOB);
}

int
ccn_buf_match_blob(struct ccn_buf_decoder *d, const unsigned char **bufp, size_t *sizep)
{
    if (ccn_buf_match_some_blob(d)) {
        if (bufp != NULL)
            *bufp = d->buf + d->decoder.index;
        if (sizep != NULL)
            *sizep = d->decoder.numval;
        return (1);
    }
    if (bufp != NULL)
        *bufp = d->buf + d->decoder.token_index;
    if (sizep != NULL)
        *sizep = 0;
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
ccn_parse_required_tagged_BLOB(struct ccn_buf_decoder *d, enum ccn_dtag dtag)
{
    int res = -1;
    if (ccn_buf_match_dtag(d, dtag)) {
        res = d->decoder.element_index;
        ccn_buf_advance(d);
        if (ccn_buf_match_some_blob(d))
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
ccn_parse_Name(struct ccn_buf_decoder *d, struct parsed_Name *x, struct ccn_indexbuf *components)
{
    int ncomp = 0;
    int res = -1;
    if (ccn_buf_match_dtag(d, CCN_DTAG_Name)) {
        res = d->decoder.element_index;
        if (components) components->n = 0;
        ccn_buf_advance(d);
        while (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
            if (components != NULL)
                ccn_indexbuf_append_element(components, d->decoder.token_index);
            ncomp += 1;
            ccn_buf_advance(d);
            if (ccn_buf_match_blob(d, NULL, NULL)) {
                ccn_buf_advance(d);
            }
            ccn_buf_check_close(d);
        }
        if (components != NULL)
            ccn_indexbuf_append_element(components, d->decoder.token_index);
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

int
ccn_parse_PublisherID(struct ccn_buf_decoder *d)
{
    int res = -1;
    if (ccn_buf_match_dtag(d, CCN_DTAG_PublisherID)) {
        res = d->decoder.element_index;
        ccn_buf_advance(d);
        if (!ccn_buf_match_attr(d, "type"))
            return (d->decoder.state = -__LINE__);
        ccn_buf_advance(d);
        if (!(ccn_buf_match_udata(d, "KEY") ||
              ccn_buf_match_udata(d, "CERTIFICATE") ||
              ccn_buf_match_udata(d, "ISSUER_KEY") ||
              ccn_buf_match_udata(d, "ISSUER_CERTIFICATE")))
            return (d->decoder.state = -__LINE__);
        ccn_buf_advance(d);
        if (!ccn_buf_match_some_blob(d))
            return (d->decoder.state = -__LINE__);
        ccn_buf_advance(d);
        ccn_buf_check_close(d);
    }
    if (d->decoder.state < 0)
        return (d->decoder.state);
    return(res);
}

int
ccn_parse_nonNegativeInteger(struct ccn_buf_decoder *d)
{
    const unsigned char *p;
    int i;
    int n;
    int val;
    int newval;
    unsigned char c;
    if (d->decoder.state >= 0 &&
          CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_UDATA) {
        p = d->buf + d->decoder.index;
        n = d->decoder.numval;
        if (n < 1)
            return(d->decoder.state = -__LINE__);
        val = 0;
        for (i = 0; i < n; i++) {
            c = p[i];
            if ('0' <= c && c <= '9') {
                newval = val * 10 + (c - '0');
                if (newval < val)
                    return(d->decoder.state = -__LINE__);
                val = newval;
            }
            else
                return(d->decoder.state = -__LINE__);
        }
        ccn_buf_advance(d);
        return(val);
    }
    return(d->decoder.state = -__LINE__);
}

int
ccn_fetch_tagged_nonNegativeInteger(enum ccn_dtag tt, const unsigned char *buf, size_t start, size_t stop)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    int result = -1;
    if (stop < start) return(-1);
    d = ccn_buf_decoder_start(&decoder, buf + start, stop - start);
    if (ccn_buf_match_dtag(d, tt)) {
        ccn_buf_advance(d);
        result = ccn_parse_nonNegativeInteger(d);
        ccn_buf_check_close(d);
    }
    if (result < 0)
        return(-1);
    return(result);
}

int
ccn_parse_interest(const unsigned char *msg, size_t size,
                   struct ccn_parsed_interest *interest,
                   struct ccn_indexbuf *components)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder, msg, size);
    int ncomp = 0;
    int res;
    if (ccn_buf_match_dtag(d, CCN_DTAG_Interest)) {
        struct parsed_Name name = {0};
        ccn_buf_advance(d);
        interest->offset[CCN_PI_B_Name] = d->decoder.element_index;
        interest->offset[CCN_PI_B_Component0] = d->decoder.index;
        res = ccn_parse_Name(d, &name, components);
        if (res < 0)
            return(res);
        interest->offset[CCN_PI_E_ComponentN] = d->decoder.token_index - 1;
        interest->offset[CCN_PI_E_Name] = d->decoder.token_index;
        ncomp = name.ncomp;
        /* Required match rule */
        interest->offset[CCN_PI_B_MatchRule] = d->decoder.token_index;
        if (ccn_buf_match_some_dtag(d)) {
            switch (d->decoder.numval) {
                case CCN_DTAG_MatchFirstAvailableDescendant:
                case CCN_DTAG_MatchLastAvailableDescendant:
                case CCN_DTAG_MatchNextAvailableSibling:
                case CCN_DTAG_MatchLastAvailableSibling:
                case CCN_DTAG_MatchEntirePrefix:
                    ccn_buf_advance(d);
                    ccn_buf_check_close(d);
                    break;
                default:
                    fprintf(stderr, "Got a match?\n");
            }
        }
        else fprintf(stderr, "got a match?\n");
        interest->offset[CCN_PI_E_MatchRule] = d->decoder.token_index;
        /* optional PublisherID */
        interest->offset[CCN_PI_B_PublisherID] = d->decoder.token_index;
        res = ccn_parse_PublisherID(d);
        interest->offset[CCN_PI_E_PublisherID] = d->decoder.token_index;
        /* optional Scope */
        interest->scope = -1;
        interest->offset[CCN_PI_B_Scope] = d->decoder.token_index;
        if (ccn_buf_match_dtag(d, CCN_DTAG_Scope)) {
            ccn_buf_advance(d);
            interest->scope = ccn_parse_nonNegativeInteger(d);
            if (interest->scope > 9)
                return (d->decoder.state = -__LINE__);
            ccn_buf_check_close(d);
        }
        interest->offset[CCN_PI_E_Scope] = d->decoder.token_index;
        /* optional Nonce */
        interest->offset[CCN_PI_B_Nonce] = d->decoder.token_index;
        if (ccn_buf_match_dtag(d, CCN_DTAG_Nonce)) {
            ccn_buf_advance(d);
            if (!ccn_buf_match_some_blob(d))
                return (d->decoder.state  = -__LINE__);
            ccn_buf_advance(d);
            ccn_buf_check_close(d);
        }
        interest->offset[CCN_PI_E_Nonce] = d->decoder.token_index;
        /* Allow for some experimental stuff */
        interest->offset[CCN_PI_B_OTHER] = d->decoder.token_index;
        while (ccn_buf_match_some_dtag(d)) {
            ccn_buf_advance(d);
            if (ccn_buf_match_some_blob(d))
                ccn_buf_advance(d);
            ccn_buf_check_close(d);
        }
        interest->offset[CCN_PI_E_OTHER] = d->decoder.token_index;
        ccn_buf_check_close(d);
        interest->offset[CCN_PI_E] = d->decoder.index;
    }
    if (d->decoder.state < 0)
        return(d->decoder.state);
    if (d->decoder.index != size || !CCN_FINAL_DSTATE(d->decoder.state))
        return (CCN_DSTATE_ERR_CODING);
    return (ncomp);
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
        x->Name = ccn_parse_Name(d, &name, NULL);
        x->PublisherID = ccn_parse_PublisherID(d);
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

/* ccn_parse_ContentAuthenticator is currently unused */
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
ccn_parse_ContentObject(const unsigned char *msg, size_t size,
                        struct ccn_parsed_ContentObject *x,
                        struct ccn_indexbuf *components)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder, msg, size);
    if (ccn_buf_match_dtag(d, CCN_DTAG_ContentObject)) {
        struct parsed_Name name;
        ccn_buf_advance(d);        
        x->offset[CCN_PCO_B_Name] = d->decoder.element_index;
        x->offset[CCN_PCO_B_Component0] = d->decoder.index;
        x->Name = ccn_parse_Name(d, &name, components);
        if (x->Name < 0)
            d->decoder.state = -__LINE__;
        x->offset[CCN_PCO_E_ComponentN] = d->decoder.token_index - 1;
        x->offset[CCN_PCO_E_Name] = d->decoder.token_index;
        
        if (ccn_buf_match_dtag(d, CCN_DTAG_ContentAuthenticator)) {
            x->ContentAuthenticator = d->decoder.token_index;
            ccn_buf_advance(d);
            x->offset[CCN_PCO_B_CAUTH_PublisherKeyID] = d->decoder.token_index;
            ccn_parse_required_tagged_BLOB(d, CCN_DTAG_PublisherKeyID);
            x->offset[CCN_PCO_E_CAUTH_PublisherKeyID] = d->decoder.token_index;
            
            x->offset[CCN_PCO_B_CAUTH_NameComponentCount] = d->decoder.token_index;
            ccn_parse_required_tagged_UDATA(d, CCN_DTAG_NameComponentCount);
            x->offset[CCN_PCO_E_CAUTH_NameComponentCount] = d->decoder.token_index;
            
            x->offset[CCN_PCO_B_CAUTH_Timestamp] = d->decoder.token_index;
            ccn_parse_required_tagged_UDATA(d, CCN_DTAG_Timestamp);
            x->offset[CCN_PCO_E_CAUTH_Timestamp] = d->decoder.token_index;
            
            x->offset[CCN_PCO_B_CAUTH_Type] = d->decoder.token_index;
            ccn_parse_required_tagged_UDATA(d, CCN_DTAG_Type);
            x->offset[CCN_PCO_E_CAUTH_Type] = d->decoder.token_index;
            
            x->offset[CCN_PCO_B_CAUTH_KeyLocator] = d->decoder.token_index;
            x->offset[CCN_PCO_B_CAUTH_Key_Certificate_KeyName] = d->decoder.token_index;
            x->offset[CCN_PCO_E_CAUTH_Key_Certificate_KeyName] = d->decoder.token_index;
            if (ccn_buf_match_dtag(d, CCN_DTAG_KeyLocator)) {
                struct parsed_KeyName keyname = {-1, -1};
                ccn_buf_advance(d);
                x->offset[CCN_PCO_B_CAUTH_Key_Certificate_KeyName] = d->decoder.token_index;
                if (ccn_buf_match_dtag(d, CCN_DTAG_Key)) {
                    (void)ccn_parse_required_tagged_BLOB(d, CCN_DTAG_Key);
                }
                else if (ccn_buf_match_dtag(d, CCN_DTAG_Certificate)) {
                    (void)ccn_parse_required_tagged_BLOB(d, CCN_DTAG_Certificate);
                }
                else {
                    (void)ccn_parse_KeyName(d, &keyname);
                }
                x->offset[CCN_PCO_E_CAUTH_Key_Certificate_KeyName] = d->decoder.token_index;
                ccn_buf_check_close(d);
            }
            x->offset[CCN_PCO_E_CAUTH_KeyLocator] = d->decoder.token_index;
            
            x->offset[CCN_PCO_B_CAUTH_ContentDigest] = d->decoder.token_index;
            ccn_parse_required_tagged_BLOB(d, CCN_DTAG_ContentDigest);
            x->offset[CCN_PCO_E_CAUTH_ContentDigest] = d->decoder.token_index;
            
            ccn_buf_check_close(d);
            x->offset[CCN_PCO_E_ContentAuthenticator] = d->decoder.token_index;
        }
        else
            d->decoder.state = -__LINE__;
        
        x->offset[CCN_PCO_B_Signature] = d->decoder.token_index;
        x->Signature = ccn_parse_required_tagged_BLOB(d, CCN_DTAG_Signature);
        x->offset[CCN_PCO_E_Signature] = d->decoder.token_index;
        x->offset[CCN_PCO_B_Content] = d->decoder.token_index;
        x->Content = ccn_parse_required_tagged_BLOB(d, CCN_DTAG_Content);
        x->offset[CCN_PCO_E_Content] = d->decoder.token_index;
        ccn_buf_check_close(d);
        x->offset[CCN_PCO_E] = d->decoder.index;
    }
    else
        d->decoder.state = -__LINE__;
    if (d->decoder.index != size || !CCN_FINAL_DSTATE(d->decoder.state))
        return (CCN_DSTATE_ERR_CODING);
    return(0);
}

int
ccn_ref_tagged_BLOB(enum ccn_dtag tt, const unsigned char *buf, size_t start, size_t stop, const unsigned char **presult, size_t *psize)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    if (stop < start) return(-1);
    d = ccn_buf_decoder_start(&decoder, buf + start, stop - start);
    if (ccn_buf_match_dtag(d, tt)) {
        ccn_buf_advance(d);
        if (ccn_buf_match_blob(d, presult, psize))
            ccn_buf_advance(d);
        ccn_buf_check_close(d);
    }
    else
        return(-1);
    if (d->decoder.index != d->size || !CCN_FINAL_DSTATE(d->decoder.state))
        return (CCN_DSTATE_ERR_CODING);
    return(0);
}

static struct ccn_buf_decoder *
ccn_buf_decoder_start_at_components(struct ccn_buf_decoder *d,
    const unsigned char *buf, size_t buflen)
{
    ccn_buf_decoder_start(d, buf, buflen);
    while (ccn_buf_match_dtag(d, CCN_DTAG_Name) ||
           ccn_buf_match_dtag(d, CCN_DTAG_Interest) ||
           ccn_buf_match_dtag(d, CCN_DTAG_ContentObject))
        ccn_buf_advance(d);
    return(d);
}

int
ccn_compare_names(const unsigned char *a, size_t asize, const unsigned char *b, size_t bsize)
{
    struct ccn_buf_decoder a_decoder;
    struct ccn_buf_decoder b_decoder;
    struct ccn_buf_decoder *aa = ccn_buf_decoder_start_at_components(&a_decoder, a, asize);
    struct ccn_buf_decoder *bb = ccn_buf_decoder_start_at_components(&b_decoder, b, bsize);
    const unsigned char *acp = NULL;
    const unsigned char *bcp = NULL;
    size_t acsize;
    size_t bcsize;
    int cmp = 0;
    int more_a;
    // fprintf(stderr, "############\n");
    for (;;) {
        more_a = ccn_buf_match_dtag(aa, CCN_DTAG_Component);
        cmp = more_a - ccn_buf_match_dtag(bb, CCN_DTAG_Component);
        if (more_a == 0 || cmp != 0)
            break;
        ccn_buf_advance(aa);
        ccn_buf_advance(bb);
        acsize = bcsize = 0;
        if (ccn_buf_match_blob(aa, &acp, &acsize))
            ccn_buf_advance(aa);
        if (ccn_buf_match_blob(bb, &bcp, &bcsize))
            ccn_buf_advance(bb);
        // fprintf(stderr, "%s : %s\n", acp, bcp);
        cmp = acsize - bcsize;
        if (cmp != 0)
            break;
        cmp = memcmp(acp, bcp, acsize);
        if (cmp != 0)
            break;
        ccn_buf_check_close(aa);
        ccn_buf_check_close(bb);
    }
    // fprintf(stderr, "ccn_compare_names returning %d\n", cmp);
    return (cmp);
}

