/*
 * ccn_reg_mgmt.c
 *  
 * Copyright (C) 2009 Palo Alto Research Center, Inc. All rights reserved.
 */

#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/reg_mgmt.h>
// <!ELEMENT ForwardingEntry  (Action?, Name?, PublisherPublicKeyDigest?, FaceID?, ForwardingFlags?, FreshnessSeconds?)>

struct ccn_forwarding_entry *
ccn_forwarding_entry_parse(const unsigned char *p, size_t size)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder, p, size);
    struct ccn_charbuf *store = ccn_charbuf_create();
    struct ccn_forwarding_entry *result;
    const unsigned char *val;
    size_t sz;
    size_t start;
    size_t end;
    int action_off = -1;
    int ccnd_id_off = -1;
    
    if (store == NULL)
        return(NULL);
    result = calloc(1, sizeof(*result));
    if (result == NULL) {
        ccn_charbuf_destroy(&store);
        return(NULL);
    }
    if (ccn_buf_match_dtag(d, CCN_DTAG_ForwardingEntry)) {
        ccn_buf_advance(d);
        action_off = ccn_parse_tagged_string(d, CCN_DTAG_Action, store);
        if (ccn_buf_match_dtag(d, CCN_DTAG_Name)) {
            result->name_prefix = ccn_charbuf_create();
            start = d->decoder.token_index;
            ccn_parse_Name(d, NULL);
            end = d->decoder.token_index;
            ccn_charbuf_append(result->name_prefix, p + start, end - start);
        }
        else
            result->name_prefix = NULL;
        if (ccn_buf_match_dtag(d, CCN_DTAG_PublisherPublicKeyDigest)) {
            ccn_buf_advance(d);
            if (ccn_buf_match_blob(d, &val, &sz)) {
                ccn_buf_advance(d);
                if (sz != 32)
                    d->decoder.state = -__LINE__;
            }
            ccn_buf_check_close(d);
            if (d->decoder.state >= 0) {
                ccnd_id_off = store->length;
                ccn_charbuf_append(store, val, sz);
                result->ccnd_id_size = sz;
            }
        }
        result->faceid = ccn_parse_optional_tagged_nonNegativeInteger(d, CCN_DTAG_FaceID);
        result->flags = ccn_parse_optional_tagged_nonNegativeInteger(d, CCN_DTAG_ForwardingFlags);
        result->lifetime = ccn_parse_optional_tagged_nonNegativeInteger(d, CCN_DTAG_FreshnessSeconds);
        ccn_buf_check_close(d);
    }
    else
        d->decoder.state = -__LINE__;
    
    if (d->decoder.index != size || !CCN_FINAL_DSTATE(d->decoder.state) ||
        store->length > sizeof(result->store))
        ccn_forwarding_entry_destroy(&result);
    else {
        char *b = (char *)result->store;
        memcpy(b, store->buf, store->length);
        result->action = (action_off == -1) ? NULL : b + action_off;
        result->ccnd_id = (ccnd_id_off == -1) ? NULL : result->store + ccnd_id_off;
    }
    ccn_charbuf_destroy(&store);
    return(result);
}

/**
 * Destroy the result of ccn_forwarding_entry_parse().
 */
void
ccn_forwarding_entry_destroy(struct ccn_forwarding_entry **pfe)
{
    if (*pfe == NULL)
        return;
    ccn_charbuf_destroy(&(*pfe)->name_prefix);
    free(*pfe);
    *pfe = NULL;
}

int
ccnb_append_forwarding_entry(struct ccn_charbuf *c,
                             const struct ccn_forwarding_entry *fe)
{
    return -1;
}
