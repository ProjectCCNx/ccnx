/**
 * @file ccn_file_header.c
 * @brief Support for parsing and creating file headers
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009 Palo Alto Research Center, Inc.
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
#include <ccn/coding.h>

#include <ccn/header.h>

int
ccn_parse_tagged_required_uintmax(struct ccn_buf_decoder *d, enum ccn_dtag dtag, uintmax_t *result)
{
    int res = -1;
    if (ccn_buf_match_dtag(d, dtag)) {
        ccn_buf_advance(d);
        res = ccn_parse_uintmax(d, result);
        ccn_buf_check_close(d);
    } else {
        return (d->decoder.state = -__LINE__);
    }
    return (res);
}
/**
 * Parse a ccnb-encoded Header 
 */
struct ccn_header *
ccn_header_parse(const unsigned char *p, size_t size)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder, p, size);
    struct ccn_header *result;
    const unsigned char *blob;
    size_t blobsize;
    int res = 0;

    result = calloc(1, sizeof(*result));
    if (result == NULL)
        return (NULL);
    if (ccn_buf_match_dtag(d, CCN_DTAG_Header)) {
        ccn_buf_advance(d);
        res |= ccn_parse_tagged_required_uintmax(d, CCN_DTAG_Start, &result->start);
        res |= ccn_parse_tagged_required_uintmax(d, CCN_DTAG_Count, &result->count);
        res |= ccn_parse_tagged_required_uintmax(d, CCN_DTAG_BlockSize, &result->block_size);
        res |= ccn_parse_tagged_required_uintmax(d, CCN_DTAG_Length, &result->length);
        if (res != 0) {
            free(result);
            return (NULL);
        }
        if (ccn_buf_match_dtag(d, CCN_DTAG_ContentDigest)) {
            ccn_buf_advance(d);
            if (0 == ccn_buf_match_blob(d, &blob, &blobsize)) {
                result->root_digest = ccn_charbuf_create();
                ccn_charbuf_append(result->content_digest, blob, blobsize);
            }
            ccn_buf_check_close(d);
        }
        if (ccn_buf_match_dtag(d, CCN_DTAG_RootDigest)) {
            ccn_buf_advance(d);
            if (0 == ccn_buf_match_blob(d, &blob, &blobsize)) {
                result->root_digest = ccn_charbuf_create();
                ccn_charbuf_append(result->root_digest, blob, blobsize);
            }
            ccn_buf_check_close(d);
        }
        ccn_buf_check_close(d);
    }
    return (result);
}
/*
 * Destroy the result of a ccn_header_parse or ccn_get_header
 */
void
ccn_header_destroy(struct ccn_header **ph)
{
    if (*ph == NULL)
        return;
    ccn_charbuf_destroy(&(*ph)->root_digest);
    ccn_charbuf_destroy(&(*ph)->content_digest);
    free(*ph);
    *ph = NULL;
}

int
ccnb_append_header(struct ccn_charbuf *c,
                   const struct ccn_header *h)
{
    int res;
    res = ccnb_element_begin(c, CCN_DTAG_Header);
    res |= ccnb_tagged_putf(c, CCN_DTAG_Start, "%u", h->start);
    res |= ccnb_tagged_putf(c, CCN_DTAG_Start, "%u", h->count);
    res |= ccnb_tagged_putf(c, CCN_DTAG_Start, "%u", h->block_size);
    res |= ccnb_tagged_putf(c, CCN_DTAG_Start, "%u", h->length);
    if (h->content_digest != NULL)
        res |= ccn_charbuf_append_charbuf(c, h->content_digest);
    if (h->root_digest != NULL)
        res |= ccn_charbuf_append_charbuf(c, h->root_digest);
    res |= ccnb_element_end(c);
    return (res);
}

struct ccn_header *
ccn_get_header(struct ccn *h, struct ccn_charbuf *name, int timeout)
{
    struct ccn_charbuf *hn;
    struct ccn_header *result = NULL;
    int res;

    hn = ccn_charbuf_create();
    ccn_charbuf_append_charbuf(hn, name);
    ccn_name_append_str(hn, "_meta_");
    ccn_name_append_str(hn, ".header");
    res = ccn_resolve_version(h, hn, CCN_V_HIGHEST, timeout);
    if (res == 0) {
        struct ccn_charbuf *ho = ccn_charbuf_create();
        struct ccn_parsed_ContentObject pcobuf = { 0 };
        const unsigned char *hc;
        size_t hcs;

        res = ccn_get(h, hn, NULL, timeout, ho, &pcobuf, NULL, 0);
        if (res == 0) {
            hc = ho->buf;
            hcs = ho->length;
            ccn_content_get_value(hc, hcs, &pcobuf, &hc, &hcs);
            result = ccn_header_parse(hc, hcs);
        }
        ccn_charbuf_destroy(&ho);
    }
    ccn_charbuf_destroy(&hn);
    return (result);
}
