/*
 * ccn_buf_decoder.c
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

// XXX - stdio is for temporary debug stuff
//#include <stdio.h>

struct ccn_buf_decoder *
ccn_buf_decoder_start(struct ccn_buf_decoder *d,
                      const unsigned char *buf, size_t size)
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
    return(d->decoder.state >= 0 &&
           CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_BLOB);
}

int
ccn_buf_match_blob(struct ccn_buf_decoder *d,
                   const unsigned char **bufp, size_t *sizep)
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
ccn_buf_advance_past_element(struct ccn_buf_decoder *d)
{
    enum ccn_tt tt;
    int nest;
    if (d->decoder.state < 0)
        return(d->decoder.state);
    tt = CCN_GET_TT_FROM_DSTATE(d->decoder.state);
    if (tt == CCN_DTAG || tt == CCN_TAG) {
        nest = d->decoder.nest;
        ccn_buf_advance(d);
        while (d->decoder.state >= 0 && d->decoder.nest >= nest)
            ccn_buf_advance(d);
        /* The nest decrements before the closer is consumed */
        ccn_buf_check_close(d);
    }
    else
        return(-1);
    if (d->decoder.state < 0)
        return(d->decoder.state);
    return (0);
}

int
ccn_parse_required_tagged_BLOB(struct ccn_buf_decoder *d, enum ccn_dtag dtag,
int minlen, int maxlen)
{
    int res = -1;
    size_t len = 0;
    if (ccn_buf_match_dtag(d, dtag)) {
        res = d->decoder.element_index;
        ccn_buf_advance(d);
        if (ccn_buf_match_some_blob(d)) {
            len = d->decoder.numval;
            ccn_buf_advance(d);
        }
        ccn_buf_check_close(d);
        if (len < minlen || (maxlen >= 0 && len > maxlen)) {
            d->decoder.state = -__LINE__;
        }
    }
    else
        d->decoder.state = -__LINE__;
    if (d->decoder.state < 0)
        return (d->decoder.state);
    return(res);
}

int
ccn_parse_optional_tagged_BLOB(struct ccn_buf_decoder *d, enum ccn_dtag dtag,
int minlen, int maxlen)
{
    if (ccn_buf_match_dtag(d, dtag))
        return(ccn_parse_required_tagged_BLOB(d, dtag, minlen, maxlen));
    return(-1);
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

int
ccn_parse_optional_tagged_UDATA(struct ccn_buf_decoder *d, enum ccn_dtag dtag)
{
    if (ccn_buf_match_dtag(d, dtag))
        return(ccn_parse_required_tagged_UDATA(d, dtag));
    return(-1);
}

struct parsed_Name {
    int start;
    int size;
    int lastcomp;
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
        x->lastcomp = d->decoder.token_index;
        while (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
            if (components != NULL)
                ccn_indexbuf_append_element(components, d->decoder.token_index);
            x->lastcomp = d->decoder.token_index;
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
ccn_parse_PublisherID(struct ccn_buf_decoder *d, struct ccn_parsed_interest *pi)
{
    int res = -1;
    int iskey = 0;
    unsigned pubstart = d->decoder.token_index;
    unsigned keystart = pubstart;
    unsigned keyend = pubstart;
    unsigned pubend = pubstart;
    if (ccn_buf_match_dtag(d, CCN_DTAG_PublisherID)) {
        res = d->decoder.element_index;
        ccn_buf_advance(d);
        if (!ccn_buf_match_attr(d, "type"))
            return (d->decoder.state = -__LINE__);
        ccn_buf_advance(d);
        iskey = ccn_buf_match_udata(d, "KEY");
        if (!(iskey                                      ||
              ccn_buf_match_udata(d, "CERTIFICATE")      ||
              ccn_buf_match_udata(d, "ISSUER_KEY")       ||
              ccn_buf_match_udata(d, "ISSUER_CERTIFICATE")))
            return (d->decoder.state = -__LINE__);
        ccn_buf_advance(d);
        keystart = d->decoder.token_index;
        if (!ccn_buf_match_some_blob(d))
            return (d->decoder.state = -__LINE__);
        ccn_buf_advance(d);
        keyend = d->decoder.token_index;
        ccn_buf_check_close(d);
        pubend = d->decoder.token_index;
    }
    if (d->decoder.state < 0)
        return (d->decoder.state);
    if (pi != NULL) {
        pi->offset[CCN_PI_B_PublisherID] = pubstart;
        pi->offset[CCN_PI_B_PublisherIDKeyDigest] = keystart;
        pi->offset[CCN_PI_E_PublisherIDKeyDigest] = iskey ? keyend : keystart;
        pi->offset[CCN_PI_E_PublisherID] = pubend;
    }
    return(res);
}

int
ccn_parse_Exclude(struct ccn_buf_decoder *d)
{
    int res = -1;
    if (ccn_buf_match_dtag(d, CCN_DTAG_Exclude)) {
        res = d->decoder.element_index;
        ccn_buf_advance(d);
        ccn_parse_optional_tagged_BLOB(d, CCN_DTAG_Bloom, 1, 1024+8);
        while (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
            ccn_parse_required_tagged_BLOB(d, CCN_DTAG_Component, 0, -1);
            ccn_parse_optional_tagged_BLOB(d, CCN_DTAG_Bloom, 1, 1024+8);
        }
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
    if (d->decoder.state < 0)
        return(d->decoder.state);
    if (CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_UDATA) {
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
ccn_parse_timestamp(struct ccn_buf_decoder *d)
{
    const unsigned char dlm[] = "--T::.Z";
    const unsigned char *p;
    int i;
    int k;
    int n;
    if (d->decoder.state < 0)
        return(d->decoder.state);
    if (CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_UDATA) {
        p = d->buf + d->decoder.index;
        n = d->decoder.numval;
        if (n < 8 || n > 40)
            return(d->decoder.state = -__LINE__);
        if (p[n - 1] != 'Z')
            return(d->decoder.state = -__LINE__);
        for (i = 0, k = 0; i < n && '0' <= p[i] && p[i] <= '9';) {
            i++;
            if (i < n && p[i] == dlm[k]) {
                if (dlm[k++] == 0)
                    return(d->decoder.state = -__LINE__);
                i++;
            }
        }
        if (k < 5)
            return(d->decoder.state = -__LINE__);
        if (!(i == n || i == n - 1))
            return(d->decoder.state = -__LINE__);
        ccn_buf_advance(d);
        return(0);
    }
    return(d->decoder.state = -__LINE__);
}

int
ccn_parse_required_tagged_timestamp(struct ccn_buf_decoder *d, enum ccn_dtag dtag)
{
    int res = -1;
    if (ccn_buf_match_dtag(d, dtag)) {
        res = d->decoder.element_index;
        ccn_buf_advance(d);
        ccn_parse_timestamp(d);
        ccn_buf_check_close(d);
    }
    else
        d->decoder.state = -__LINE__;
    if (d->decoder.state < 0)
        return (-1);
    return(res);
}

int
ccn_parse_optional_tagged_nonNegativeInteger(struct ccn_buf_decoder *d, enum ccn_dtag dtag)
{
    int res = -1;
    if (ccn_buf_match_dtag(d, dtag)) {
        ccn_buf_advance(d);
        res = ccn_parse_nonNegativeInteger(d);
        ccn_buf_check_close(d);
    }
    if (d->decoder.state < 0)
        return (d->decoder.state);
    return(res);
}

int
ccn_fetch_tagged_nonNegativeInteger(enum ccn_dtag tt,
                                    const unsigned char *buf,
                                    size_t start, size_t stop)
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
        interest->offset[CCN_PI_B_LastPrefixComponent] = name.lastcomp;
        interest->offset[CCN_PI_E_LastPrefixComponent] = d->decoder.token_index - 1;
        //interest->offset[CCN_PI_B_ComponentLast] = name.lastcomp;
        interest->offset[CCN_PI_E_ComponentLast] = d->decoder.token_index - 1;
        interest->offset[CCN_PI_E_Name] = d->decoder.token_index;
        ncomp = name.ncomp;
        /* optional NameComponentCount */
        interest->offset[CCN_PI_B_NameComponentCount] = d->decoder.token_index;
        interest->prefix_comps = ccn_parse_optional_tagged_nonNegativeInteger(d,
                         CCN_DTAG_NameComponentCount);
        interest->offset[CCN_PI_E_NameComponentCount] = d->decoder.token_index;
        if (d->decoder.state < 0 || interest->prefix_comps > ncomp)
            return (d->decoder.state = -__LINE__);
        if (interest->prefix_comps == -1 || interest->prefix_comps == ncomp)
            interest->prefix_comps = ncomp;
        else if (components != NULL) {
            ncomp = interest->prefix_comps;
            if (ncomp >= components->n) abort();
            interest->offset[CCN_PI_B_LastPrefixComponent] = components->buf[(ncomp > 0) ? ncomp-1 : 0];
            interest->offset[CCN_PI_E_LastPrefixComponent] = components->buf[ncomp];
        }
        else {
            /* urp - restart the parse with a components buffer. Ugly. */
            components = ccn_indexbuf_create();
            if (components == NULL) return(-1);
            res = ccn_parse_interest(msg, size, interest, components);
            ccn_indexbuf_destroy(&components);
            return(res);
        }
        /* optional AdditionalNameComponents */
        interest->offset[CCN_PI_B_AdditionalNameComponents] = d->decoder.token_index;
        ccn_parse_optional_tagged_nonNegativeInteger(d,
                         CCN_DTAG_AdditionalNameComponents);
        interest->offset[CCN_PI_E_AdditionalNameComponents] = d->decoder.token_index;
        /* optional PublisherID */
        res = ccn_parse_PublisherID(d, interest);
        /* optional Exclude element */
        interest->offset[CCN_PI_B_Exclude] = d->decoder.token_index;
        res = ccn_parse_Exclude(d);
        interest->offset[CCN_PI_E_Exclude] = d->decoder.token_index;
        /* optional OrderPreference */
        interest->offset[CCN_PI_B_OrderPreference] = d->decoder.token_index;
        interest->orderpref = ccn_parse_optional_tagged_nonNegativeInteger(d,
                         CCN_DTAG_OrderPreference);
        interest->offset[CCN_PI_E_OrderPreference] = d->decoder.token_index;
        if (interest->orderpref > 5)
            return (d->decoder.state = -__LINE__);        
        /* optional AnswerOriginKind */
        interest->offset[CCN_PI_B_AnswerOriginKind] = d->decoder.token_index;
        interest->answerfrom = ccn_parse_optional_tagged_nonNegativeInteger(d,
                         CCN_DTAG_AnswerOriginKind);
        interest->offset[CCN_PI_E_AnswerOriginKind] = d->decoder.token_index;
        if (interest->answerfrom == -1)
            interest->answerfrom = 3;
        else if (interest->answerfrom > 1 && interest->answerfrom != 3)
            return (d->decoder.state = -__LINE__);
        /* optional Scope */
        interest->offset[CCN_PI_B_Scope] = d->decoder.token_index;
        interest->scope = ccn_parse_optional_tagged_nonNegativeInteger(d,
                         CCN_DTAG_Scope);
        interest->offset[CCN_PI_E_Scope] = d->decoder.token_index;
        if (interest->scope > 9)
                return (d->decoder.state = -__LINE__);
        /* optional Count */
        interest->offset[CCN_PI_B_Count] = d->decoder.token_index;
        interest->count = ccn_parse_optional_tagged_nonNegativeInteger(d,
                         CCN_DTAG_Count);
        interest->offset[CCN_PI_E_Count] = d->decoder.token_index;
        if (interest->count == -1)
            interest->count = 1;
        /* optional Nonce */
        interest->offset[CCN_PI_B_Nonce] = d->decoder.token_index;
        res = ccn_parse_optional_tagged_BLOB(d, CCN_DTAG_Nonce, 4, 64);
        interest->offset[CCN_PI_E_Nonce] = d->decoder.token_index;
        /* Allow for some experimental stuff */
        interest->offset[CCN_PI_B_OTHER] = d->decoder.token_index;
        ccn_parse_optional_tagged_BLOB(d, CCN_DTAG_ExperimentalResponseFilter, 9, 1024+8);
        interest->offset[CCN_PI_E_OTHER] = d->decoder.token_index;
        ccn_buf_check_close(d);
        interest->offset[CCN_PI_E] = d->decoder.index;
    }
    else
        return (d->decoder.state = -__LINE__);
    if (d->decoder.state < 0)
        return (d->decoder.state);
    if (d->decoder.index != size || !CCN_FINAL_DSTATE(d->decoder.state))
        return (CCN_DSTATE_ERR_CODING);
    return (ncomp);
}

struct parsed_KeyName {
    int Name;
    int PublisherID;
};

static int
ccn_parse_KeyName(struct ccn_buf_decoder *d, struct parsed_KeyName *x)
{
    int res = -1;
    struct parsed_Name name;
    if (ccn_buf_match_dtag(d, CCN_DTAG_KeyName)) {
        res = d->decoder.element_index;
        ccn_buf_advance(d);
        x->Name = ccn_parse_Name(d, &name, NULL);
        x->PublisherID = ccn_parse_PublisherID(d, NULL);
        ccn_buf_check_close(d);
    }
    else
        d->decoder.state = -__LINE__;
    if (d->decoder.state < 0)
        return (d->decoder.state);
    return(res);
}

static int
ccn_parse_Signature(struct ccn_buf_decoder *d, struct ccn_parsed_ContentObject *x)
{
    int res = -1;
    int i;
    struct ccn_parsed_ContentObject dummy;
    if (x == NULL)
        x = &dummy;
    for (i = CCN_PCO_B_Signature; i <= CCN_PCO_E_Signature; i++) {
        x->offset[i] = d->decoder.token_index;
    }
    if (ccn_buf_match_dtag(d, CCN_DTAG_Signature)) {
        res = d->decoder.element_index;
        ccn_buf_advance(d);
        x->offset[CCN_PCO_B_DigestAlgorithm] = d->decoder.token_index;
        ccn_parse_optional_tagged_UDATA(d, CCN_DTAG_DigestAlgorithm);
        x->offset[CCN_PCO_E_DigestAlgorithm] = d->decoder.token_index;
        x->offset[CCN_PCO_B_Witness] = d->decoder.token_index;
        ccn_parse_optional_tagged_BLOB(d, CCN_DTAG_Witness, 8, -1);
        x->offset[CCN_PCO_E_Witness] = d->decoder.token_index;
        x->offset[CCN_PCO_B_SignatureBits] = d->decoder.token_index;
        ccn_parse_required_tagged_BLOB(d, CCN_DTAG_SignatureBits, 16, -1);
        x->offset[CCN_PCO_E_SignatureBits] = d->decoder.token_index;
        ccn_buf_check_close(d);
        x->offset[CCN_PCO_E_Signature] = d->decoder.token_index;
    }
    if (d->decoder.state < 0)
        return (d->decoder.state);
    return(res);
}

static int
ccn_parse_SignedInfo(struct ccn_buf_decoder *d, struct ccn_parsed_ContentObject *x)
{
    x->offset[CCN_PCO_B_SignedInfo] = d->decoder.token_index;
    if (ccn_buf_match_dtag(d, CCN_DTAG_SignedInfo)) {
        ccn_buf_advance(d);
        x->offset[CCN_PCO_B_CAUTH_PublisherKeyID] = d->decoder.token_index;
        ccn_parse_required_tagged_BLOB(d, CCN_DTAG_PublisherKeyID, 16, 64);
        x->offset[CCN_PCO_E_CAUTH_PublisherKeyID] = d->decoder.token_index;
        
        if (x->magic == -1)                                                     // XXX - compat
            ccn_parse_optional_tagged_UDATA(d, CCN_DTAG_NameComponentCount);    // XXX - compat
        
        x->offset[CCN_PCO_B_CAUTH_Timestamp] = d->decoder.token_index;
        ccn_parse_required_tagged_timestamp(d, CCN_DTAG_Timestamp);
        x->offset[CCN_PCO_E_CAUTH_Timestamp] = d->decoder.token_index;
        
        x->offset[CCN_PCO_B_CAUTH_Type] = d->decoder.token_index;
        ccn_parse_required_tagged_UDATA(d, CCN_DTAG_Type);
        x->offset[CCN_PCO_E_CAUTH_Type] = d->decoder.token_index;
        
        x->offset[CCN_PCO_B_FreshnessSeconds] = d->decoder.token_index;
        ccn_parse_optional_tagged_nonNegativeInteger(d, CCN_DTAG_FreshnessSeconds);
        x->offset[CCN_PCO_E_FreshnessSeconds] = d->decoder.token_index;
        
        x->offset[CCN_PCO_B_CAUTH_KeyLocator] = d->decoder.token_index;
        x->offset[CCN_PCO_B_CAUTH_Key_Certificate_KeyName] = d->decoder.token_index;
        x->offset[CCN_PCO_E_CAUTH_Key_Certificate_KeyName] = d->decoder.token_index;
        if (ccn_buf_match_dtag(d, CCN_DTAG_KeyLocator)) {
            struct parsed_KeyName keyname = {-1, -1};
            ccn_buf_advance(d);
            x->offset[CCN_PCO_B_CAUTH_Key_Certificate_KeyName] = d->decoder.token_index;
            if (ccn_buf_match_dtag(d, CCN_DTAG_Key)) {
                (void)ccn_parse_required_tagged_BLOB(d, CCN_DTAG_Key, 0, -1);
            }
            else if (ccn_buf_match_dtag(d, CCN_DTAG_Certificate)) {
                (void)ccn_parse_required_tagged_BLOB(d, CCN_DTAG_Certificate, 0, -1);
            }
            else {
                (void)ccn_parse_KeyName(d, &keyname);
            }
            x->offset[CCN_PCO_E_CAUTH_Key_Certificate_KeyName] = d->decoder.token_index;
            ccn_buf_check_close(d);
        }
        x->offset[CCN_PCO_E_CAUTH_KeyLocator] = d->decoder.token_index;
        if (x->magic == -1)                                                     // XXX - compat
            ccn_parse_optional_tagged_BLOB(d, CCN_DTAG_ContentDigest, 16, -1);  // XXX - compat
        ccn_buf_check_close(d);
    }
    else
        d->decoder.state = -__LINE__;
    x->offset[CCN_PCO_E_SignedInfo] = d->decoder.token_index;
    if (d->decoder.state < 0)
        return (d->decoder.state);
    return(0);
}

int
ccn_parse_ContentObject(const unsigned char *msg, size_t size,
                        struct ccn_parsed_ContentObject *x,
                        struct ccn_indexbuf *components)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder, msg, size);
    int res;
    x->magic = -1;
    x->digest_bytes = 0;
    if (ccn_buf_match_dtag(d, CCN_DTAG_ContentObject)) {
        struct parsed_Name name;
        ccn_buf_advance(d);
        res = ccn_parse_Signature(d, x);
        if (res >= 0)
            x->magic = 20080711;
        x->offset[CCN_PCO_B_Name] = d->decoder.token_index;
        x->offset[CCN_PCO_B_Component0] = d->decoder.index;
        res = ccn_parse_Name(d, &name, components);
        if (res < 0)
            d->decoder.state = -__LINE__;
        x->name_ncomps = name.ncomp;
        x->offset[CCN_PCO_E_ComponentLast] = d->decoder.token_index - 1;
        x->offset[CCN_PCO_E_Name] = d->decoder.token_index;
        ccn_parse_SignedInfo(d, x);
        if (x->magic == -1 && ccn_buf_match_dtag(d, CCN_DTAG_Signature)) {      // XXX - compat
            x->offset[CCN_PCO_B_Signature] = d->decoder.token_index;            // XXX - compat
            res = ccn_parse_required_tagged_BLOB(d, CCN_DTAG_Signature, 16, -1);// XXX - compat
            x->magic = 20080630;                                                // XXX - compat
            x->offset[CCN_PCO_E_Signature] = d->decoder.token_index;            // XXX - compat
        }                                                                       // XXX - compat
        x->offset[CCN_PCO_B_Content] = d->decoder.token_index;
        ccn_parse_required_tagged_BLOB(d, CCN_DTAG_Content, 0, -1);
        x->offset[CCN_PCO_E_Content] = d->decoder.token_index;
        ccn_buf_check_close(d);
        x->offset[CCN_PCO_E] = d->decoder.index;
    }
    else
        d->decoder.state = -__LINE__;
    if (d->decoder.index != size || !CCN_FINAL_DSTATE(d->decoder.state))
        return (CCN_DSTATE_ERR_CODING);
    if (x->magic < 0)
        return(-1);
    return(0);
}

int
ccn_ref_tagged_BLOB(enum ccn_dtag tt,
                    const unsigned char *buf, size_t start, size_t stop,
                    const unsigned char **presult, size_t *psize)
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
           ccn_buf_match_dtag(d, CCN_DTAG_ContentObject)) {
        ccn_buf_advance(d);
        ccn_parse_Signature(d, NULL);
    }
    return(d);
}

int
ccn_content_get_value(const unsigned char *data, size_t data_size,
                      const struct ccn_parsed_ContentObject *content,
                      const unsigned char **value, size_t *value_size)
{
    int res;
    res = ccn_ref_tagged_BLOB(CCN_DTAG_Content, data,
          content->offset[CCN_PCO_B_Content],
          content->offset[CCN_PCO_E_Content],
          value, value_size);
    return(res);
}

int
ccn_compare_names(const unsigned char *a, size_t asize,
                  const unsigned char *b, size_t bsize)
{
    struct ccn_buf_decoder a_decoder;
    struct ccn_buf_decoder b_decoder;
    struct ccn_buf_decoder *aa =
        ccn_buf_decoder_start_at_components(&a_decoder, a, asize);
    struct ccn_buf_decoder *bb =
        ccn_buf_decoder_start_at_components(&b_decoder, b, bsize);
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

