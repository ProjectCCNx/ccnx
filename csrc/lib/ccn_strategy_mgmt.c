/**
 * @file ccn_strategy_mgmt.c
 * @brief Support for parsing and creating StrategySelection elements.
 * 
 * Part of the CCNx C Library.
 */
/* Copyright (C) 2013 Palo Alto Research Center, Inc.
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
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/strategy_mgmt.h>

#define STRATEGY_ID_MAX_SIZE 16

struct ccn_strategy_selection *
ccn_strategy_selection_parse(const unsigned char *p, size_t size)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder, p, size);
    struct ccn_strategy_selection *result;
    struct ccn_charbuf *store = NULL;
    const unsigned char *val = NULL;
    size_t sz;
    size_t start;
    size_t end;
    int action_off = -1;
    int ccnd_id_off = -1;
    int strategyid_off = -1;
    int parameters_off = -1;
    
    result = calloc(1, sizeof(*result));
    if (result == NULL)
        return(NULL);
    result->name_prefix = ccn_charbuf_create();
    result->store = store = ccn_charbuf_create();
    if (result->name_prefix == NULL || result->store == NULL) {
        ccn_strategy_selection_destroy(&result);
        return(NULL);
    }
    if (ccn_buf_match_dtag(d, CCN_DTAG_StrategySelection)) {
        ccn_buf_advance(d);
        action_off = ccn_parse_tagged_string(d, CCN_DTAG_Action, store);
        if (ccn_buf_match_dtag(d, CCN_DTAG_Name)) {
            start = d->decoder.token_index;
            ccn_parse_Name(d, NULL);
            end = d->decoder.token_index;
            ccn_charbuf_append(result->name_prefix, p + start, end - start);
        }
        else
            ccn_charbuf_destroy(&result->name_prefix);
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
        strategyid_off = ccn_parse_tagged_string(d, CCN_DTAG_StrategyID, store);
        parameters_off = ccn_parse_tagged_string(d, CCN_DTAG_StrategyParameters, store);
        result->lifetime = ccn_parse_optional_tagged_nonNegativeInteger(d, CCN_DTAG_FreshnessSeconds);
        ccn_buf_check_close(d);
    }
    else
        d->decoder.state = -__LINE__;
    
    if (d->decoder.index != size || !CCN_FINAL_DSTATE(d->decoder.state))
        ccn_strategy_selection_destroy(&result);
    else {
        const char *b = (const char *)result->store->buf;
        result->action = (action_off == -1) ? NULL : b + action_off;
        result->ccnd_id = (ccnd_id_off == -1) ? NULL : result->store->buf + ccnd_id_off;
        result->strategyid = (strategyid_off == -1) ? NULL : b + strategyid_off;
        result->parameters = (parameters_off == -1) ? NULL : b + parameters_off;
    }
    return(result);
}

/**
 * Destroy the result of ccn_strategy_selection_parse().
 */
void
ccn_strategy_selection_destroy(struct ccn_strategy_selection **pss)
{
    if (*pss == NULL)
        return;
    ccn_charbuf_destroy(&(*pss)->name_prefix);
    ccn_charbuf_destroy(&(*pss)->store);
    free(*pss);
    *pss = NULL;
}

int
ccnb_append_strategy_selection(struct ccn_charbuf *c,
                               const struct ccn_strategy_selection *ss)
{
    int ch;
    int i;
    int len;
    int res;
    
    res = ccnb_element_begin(c, CCN_DTAG_StrategySelection);
    if (ss->action != NULL)
        res |= ccnb_tagged_putf(c, CCN_DTAG_Action, "%s",
                                   ss->action);
    if (ss->name_prefix != NULL && ss->name_prefix->length > 0)
        res |= ccn_charbuf_append(c, ss->name_prefix->buf,
                                     ss->name_prefix->length);
    if (ss->ccnd_id_size != 0)
        res |= ccnb_append_tagged_blob(c, CCN_DTAG_PublisherPublicKeyDigest,
                                          ss->ccnd_id, ss->ccnd_id_size);
    if (ss->strategyid != NULL) {
        len = strlen(ss->strategyid);
        for (i = 0; i < len; i++) {
            ch = ss->strategyid[i];
            if (!(('A' <= ch && ch <= 'Z') || ('a' <= ch && ch <= 'z') ||
                  ('0' <= ch && ch <= '9') || (ch == '_')))
                res |= -1;
        }
        if (len > 0) {
            res |= ccnb_tagged_putf(c, CCN_DTAG_StrategyID, "%.15s",
                                       ss->strategyid);
            if (len >= STRATEGY_ID_MAX_SIZE)
                res |= -1;
        }
    }
    if (ss->parameters != NULL)
        res |= ccnb_tagged_putf(c, CCN_DTAG_StrategyParameters, "%s",
                                   ss->parameters);
    if (ss->lifetime >= 0)
        res |= ccnb_tagged_putf(c, CCN_DTAG_FreshnessSeconds, "%d",
                                   ss->lifetime);
    res |= ccnb_element_end(c);
    return(res);
}
