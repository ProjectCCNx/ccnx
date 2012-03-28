/**
 * @file ccn_match.c
 * Support for the match predicate between interest and content.
 * 
 * Part of the CCNx C Library.
 */
/*
 * Copyright (C) 2008, 2009, 2012 Palo Alto Research Center, Inc.
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
#include <stdlib.h>
#include <string.h>
#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/digest.h>

/**
 * Compute the digest of the entire ContentObject if necessary,
 * caching the result in pc->digest, pc->digest_bytes.
 */
void
ccn_digest_ContentObject(const unsigned char *content_object,
                         struct ccn_parsed_ContentObject *pc)
{
    int res;
    struct ccn_digest *d = NULL;

    if (pc->magic < 20080000) abort();
    if (pc->digest_bytes == sizeof(pc->digest))
        return;
    if (pc->digest_bytes != 0) abort();
    d = ccn_digest_create(CCN_DIGEST_SHA256);
    ccn_digest_init(d);
    res = ccn_digest_update(d, content_object, pc->offset[CCN_PCO_E]);
    if (res < 0) abort();
    res = ccn_digest_final(d, pc->digest, sizeof(pc->digest));
    if (res < 0) abort();
    if (pc->digest_bytes != 0) abort();
    pc->digest_bytes = sizeof(pc->digest);
    ccn_digest_destroy(&d);
}

static int
ccn_pubid_matches(const unsigned char *content_object,
                  struct ccn_parsed_ContentObject *pc,
                  const unsigned char *interest_msg,
                  const struct ccn_parsed_interest *pi)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    int pubidstart;
    int pubidbytes;
    int contentpubidstart = 0;
    int contentpubidbytes = 0;
    pubidstart = pi->offset[CCN_PI_B_PublisherIDKeyDigest];
    pubidbytes = pi->offset[CCN_PI_E_PublisherIDKeyDigest] - pubidstart;
    if (pubidbytes > 0) {
        d = ccn_buf_decoder_start(&decoder,
                                  content_object +
                                   pc->offset[CCN_PCO_B_PublisherPublicKeyDigest],
                                  (pc->offset[CCN_PCO_E_PublisherPublicKeyDigest] -
                                   pc->offset[CCN_PCO_B_PublisherPublicKeyDigest]));
        ccn_buf_advance(d);
        if (ccn_buf_match_some_blob(d)) {
            contentpubidstart = d->decoder.token_index;
            ccn_buf_advance(d);
            contentpubidbytes = d->decoder.token_index - contentpubidstart;
        }
        if (pubidbytes != contentpubidbytes)
            return(0); // This is fishy
        if (0 != memcmp(interest_msg + pubidstart,
                        d->buf + contentpubidstart,
                        pubidbytes))
            return(0);
    }
    return(1);
}

/**
 * Test for a match between a next component and an exclusion clause
 *
 * @param excl                  address of exclusion encoding
 * @param excl_size             bytes in exclusion encoding
 * @param nextcomp              addr of nextcomp bytes
 * @param nextcomp_size         number of nextcomp bytes
 * @result 1 if the ccnb-encoded nextcomp matches the 
 *           ccnb-encoded exclusion clause, otherwise 0.
 */
int
ccn_excluded(const unsigned char *excl,
             size_t excl_size,
             const unsigned char *nextcomp,
             size_t nextcomp_size)
{
    unsigned char match_any[2] = "-";
    const unsigned char *bloom = NULL;
    size_t bloom_size = 0;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder, excl, excl_size);
    const unsigned char *comp = NULL;
    size_t comp_size = 0;
    const int excluded = 1;
    
    if (!ccn_buf_match_dtag(d, CCN_DTAG_Exclude))
        abort();
    ccn_buf_advance(d);
    if (ccn_buf_match_dtag(d, CCN_DTAG_Any)) {
        ccn_buf_advance(d);
        bloom = match_any;
        ccn_buf_check_close(d);
    }
    else if (ccn_buf_match_dtag(d, CCN_DTAG_Bloom)) {
        ccn_buf_advance(d);
        if (ccn_buf_match_blob(d, &bloom, &bloom_size))
            ccn_buf_advance(d);
        ccn_buf_check_close(d);
    }
    while (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
        ccn_buf_advance(d);
        comp_size = 0;
        if (ccn_buf_match_blob(d, &comp, &comp_size))
            ccn_buf_advance(d);
        ccn_buf_check_close(d);
        if (comp_size > nextcomp_size)
            break;
        if (comp_size == nextcomp_size) {
            int res = memcmp(comp, nextcomp, comp_size);
            if (res == 0)
                return(excluded); /* One of the explicit excludes */
            if (res > 0)
                break;
        }
        bloom = NULL;
        bloom_size = 0;
        if (ccn_buf_match_dtag(d, CCN_DTAG_Any)) {
            ccn_buf_advance(d);
            bloom = match_any;
            ccn_buf_check_close(d);
        }
        else if (ccn_buf_match_dtag(d, CCN_DTAG_Bloom)) {
            ccn_buf_advance(d);
            if (ccn_buf_match_blob(d, &bloom, &bloom_size))
                ccn_buf_advance(d);
            ccn_buf_check_close(d);
        }
    }
    /*
     * Now we have isolated the applicable filter (Any or Bloom or none).
     */
    if (bloom == match_any)
        return(excluded);
    else if (bloom_size != 0) {
        const struct ccn_bloom_wire *f = ccn_bloom_validate_wire(bloom, bloom_size);
        /* If not a valid filter, treat like a false positive */
        if (f == NULL)
            return(excluded);
        if (ccn_bloom_match_wire(f, nextcomp, nextcomp_size))
            return(excluded);
    }
    return(!excluded);
}

/**
 * Test for a match between a ContentObject and an Interest
 *
 * @param content_object        ccnb-encoded ContentObject
 * @param content_object_size   its size in bytes
 * @param implicit_content_digest boolean indicating whether the
 *                              final name component is implicit (as in
 *                              the on-wire format) or explicit (as within
 *                              ccnd's content store).
 * @param pc                    Valid parse information may be provided to
 *                              speed things up. If NULL it will be
 *                              reconstructed internally.
 * @param interest_msg          ccnb-encoded Interest
 * @param interest_msg_size     its size in bytes
 * @param pi                    see _pc_
 *
 * @result 1 if the ccnb-encoded content_object matches the 
 *           ccnb-encoded interest_msg, otherwise 0.
 */
int
ccn_content_matches_interest(const unsigned char *content_object,
                             size_t content_object_size,
                             int implicit_content_digest,
                             struct ccn_parsed_ContentObject *pc,
                             const unsigned char *interest_msg,
                             size_t interest_msg_size,
                             const struct ccn_parsed_interest *pi)
{
    struct ccn_parsed_ContentObject pc_store;
    struct ccn_parsed_interest pi_store;
    int res;
    int ncomps;
    int prefixstart;
    int prefixbytes;
    int namecompstart;
    int namecompbytes;
    int checkdigest = 0;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    const unsigned char *nextcomp;
    size_t nextcomp_size = 0;
    const unsigned char *comp = NULL;
    size_t comp_size = 0;
    if (pc == NULL) {
        res = ccn_parse_ContentObject(content_object, content_object_size,
                                      &pc_store, NULL);
        if (res < 0) return(0);
        pc = &pc_store;
    }
    if (pi == NULL) {
        res = ccn_parse_interest(interest_msg, interest_msg_size,
                                 &pi_store, NULL);
        if (res < 0) return(0);
        pi = &pi_store;
    }
    if (!ccn_pubid_matches(content_object, pc, interest_msg, pi))
        return(0);
    ncomps = pc->name_ncomps + (implicit_content_digest ? 1 : 0);
    if (ncomps < pi->prefix_comps + pi->min_suffix_comps)
        return(0);
    if (ncomps > pi->prefix_comps + pi->max_suffix_comps)
        return(0);
    prefixstart = pi->offset[CCN_PI_B_Component0];
    prefixbytes = pi->offset[CCN_PI_E_LastPrefixComponent] - prefixstart;
    namecompstart = pc->offset[CCN_PCO_B_Component0];
    namecompbytes = pc->offset[CCN_PCO_E_ComponentLast] - namecompstart;
    if (prefixbytes > namecompbytes) {
        /*
         * The only way for this to be a match is if the implicit
         * content digest name component comes into play.
         */
        if (implicit_content_digest &&
            pi->offset[CCN_PI_B_LastPrefixComponent] - prefixstart == namecompbytes &&
            (pi->offset[CCN_PI_E_LastPrefixComponent] -
             pi->offset[CCN_PI_B_LastPrefixComponent]) == 1 + 2 + 32 + 1) {
            prefixbytes = namecompbytes;
            checkdigest = 1;
        }
        else
            return(0);
    }
    if (0 != memcmp(interest_msg + prefixstart,
                    content_object + namecompstart,
                    prefixbytes))
        return(0);
    if (checkdigest) {
        /*
         * The Exclude by next component is not relevant in this case,
         * since there is no next component present.
         */
        ccn_digest_ContentObject(content_object, pc);
        d = ccn_buf_decoder_start(&decoder,
                        interest_msg + pi->offset[CCN_PI_B_LastPrefixComponent],
                        (pi->offset[CCN_PI_E_LastPrefixComponent] -
                         pi->offset[CCN_PI_B_LastPrefixComponent]));
        comp_size = 0;
        if (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
                ccn_buf_advance(d);
                ccn_buf_match_blob(d, &comp, &comp_size);
            }
        if (comp_size != pc->digest_bytes) abort();
        if (0 != memcmp(comp, pc->digest, comp_size))
            return(0);
    }
    else if (pi->offset[CCN_PI_E_Exclude] > pi->offset[CCN_PI_B_Exclude]) {
        if (prefixbytes < namecompbytes) {
            /* pick out the next component in the content object name */
            d = ccn_buf_decoder_start(&decoder,
                                      content_object +
                                        (namecompstart + prefixbytes),
                                      pc->offset[CCN_PCO_E_ComponentLast] -
                                        (namecompstart + prefixbytes));
            if (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
                ccn_buf_advance(d);
                ccn_buf_match_blob(d, &nextcomp, &nextcomp_size);
            }
            else
                return(0);
        }
        else if (!implicit_content_digest)
            goto exclude_checked;
        else if (prefixbytes == namecompbytes) {
            /* use the digest name as the next component */
            ccn_digest_ContentObject(content_object, pc);
            nextcomp_size = pc->digest_bytes;
            nextcomp = pc->digest;
        }
        else abort(); /* bug - should have returned already */
        if (ccn_excluded(interest_msg + pi->offset[CCN_PI_B_Exclude],
                         (pi->offset[CCN_PI_E_Exclude] -
                          pi->offset[CCN_PI_B_Exclude]),
                         nextcomp,
                         nextcomp_size))
            return(0);
    exclude_checked: {}
    }
    /*
     * At this point the prefix matches and exclude-by-next-component is done.
     */
    // test any other qualifiers here
    return(1);
}
