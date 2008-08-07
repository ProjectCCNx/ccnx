#include <stdlib.h>
#include <string.h>
#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/digest.h>

void
ccn_digest_ContentObject(const unsigned char *content_object,
                         struct ccn_parsed_ContentObject *pc)
{
    int res;
    struct ccn_digest *d = NULL;
    const unsigned char *content = NULL;
    size_t content_bytes = 0;
    if (pc->magic < 20080000) abort();
    if (pc->digest_bytes == sizeof(pc->digest))
        return;
    if (pc->digest_bytes != 0) abort();
    d = ccn_digest_create(CCN_DIGEST_SHA256);
    ccn_digest_init(d);
    res = ccn_ref_tagged_BLOB(CCN_DTAG_Content, content_object,
          pc->offset[CCN_PCO_B_Content],
          pc->offset[CCN_PCO_E_Content],
          &content, &content_bytes);
    if (res < 0) abort();
    res = ccn_digest_update(d, content, content_bytes);
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
    int contentpubidstart;
    int contentpubidbytes;
    pubidstart = pi->offset[CCN_PI_B_PublisherIDKeyDigest];
    pubidbytes = pi->offset[CCN_PI_E_PublisherIDKeyDigest] - pubidstart;
    if (pubidbytes > 0) {
        d = ccn_buf_decoder_start(&decoder,
                                  content_object + pc->offset[CCN_PCO_B_CAUTH_PublisherKeyID],
                                  (pc->offset[CCN_PCO_E_CAUTH_PublisherKeyID] -
                                   pc->offset[CCN_PCO_B_CAUTH_PublisherKeyID]));
        ccn_buf_advance(d);
        if (ccn_buf_match_some_blob(d)) {
            contentpubidstart = d->decoder.token_index;
            ccn_buf_advance(d);
            contentpubidbytes = d->decoder.token_index - contentpubidstart;
        }
        if (pubidbytes != contentpubidbytes)
            return(0); // This is fishy
        if (0 != memcmp(interest_msg + pubidstart,
                        content_object + contentpubidbytes,
                        pubidbytes))
            return(0);
    }
    return(1);
}

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
    int prefixstart;
    int prefixbytes;
    int namecompstart;
    int namecompbytes;
    int checkdigest = 0;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    const unsigned char *nextcomp;
    size_t nextcomp_size = 0;
    const unsigned char *comp;
    size_t comp_size = 0;
    const unsigned char *bloom;
    size_t bloom_size = 0;
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
    if (pi->offset[CCN_PI_B_AdditionalNameComponents] < pi->offset[CCN_PI_E_AdditionalNameComponents]) {
        res = ccn_fetch_tagged_nonNegativeInteger(CCN_DTAG_AdditionalNameComponents,
            interest_msg,
            pi->offset[CCN_PI_B_AdditionalNameComponents],
            pi->offset[CCN_PI_E_AdditionalNameComponents]);
        if (res + pi->prefix_comps != pc->name_ncomps + (implicit_content_digest ? 1 : 0))
            return(0);
    }
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
        d = ccn_buf_decoder_start(&decoder,
                                  interest_msg + pi->offset[CCN_PI_B_Exclude],
                                  pi->offset[CCN_PI_E_Exclude] -
                                  pi->offset[CCN_PI_B_Exclude]);
        if (!ccn_buf_match_dtag(d, CCN_DTAG_Exclude))
            abort();
        ccn_buf_advance(d);
        bloom_size = 0;
        if (ccn_buf_match_dtag(d, CCN_DTAG_Bloom)) {
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
                res = memcmp(comp, nextcomp, comp_size);
                if (res == 0)
                    return(0); /* One of the explicit excludes */
                if (res > 0)
                    break;
            }
            bloom_size = 0;
            if (ccn_buf_match_dtag(d, CCN_DTAG_Bloom)) {
                ccn_buf_advance(d);
                if (ccn_buf_match_blob(d, &bloom, &bloom_size))
                    ccn_buf_advance(d);
                ccn_buf_check_close(d);
            }
        }
        /*
         * Now we have isolated the applicable Bloom filter.
         */
        if (bloom_size != 0) {
            const struct ccn_bloom_wire *f = ccn_bloom_validate_wire(bloom, bloom_size);
            /* If not a valid filter, treat like a false positive */
            if (f == NULL)
                return(0);
            if (ccn_bloom_match_wire(f, nextcomp, nextcomp_size))
                return(0);
        }
    exclude_checked: {}
    }
    /*
     * At this point the prefix match and exclude-by-next-component is done.
     */
    /*
     *  Test the Bloom filter by Signature
     */
    if (pi->offset[CCN_PI_E_OTHER] > pi->offset[CCN_PI_B_OTHER]) {
        const struct ccn_bloom_wire *f = NULL;
        d = ccn_buf_decoder_start(&decoder,
                interest_msg + pi->offset[CCN_PI_B_OTHER],
                pi->offset[CCN_PI_E_OTHER] - pi->offset[CCN_PI_B_OTHER]);
        if (ccn_buf_match_dtag(d, CCN_DTAG_ExperimentalResponseFilter)) {
            ccn_buf_advance(d);
            bloom_size = 0;
            ccn_buf_match_blob(d, &bloom, &bloom_size);
            f = ccn_bloom_validate_wire(bloom, bloom_size);
        }
        if (f != NULL) {
            /* Use the entire Signature element for this test */
            size_t start = pc->offset[CCN_PCO_B_Signature];
            size_t stop =  pc->offset[CCN_PCO_E_Signature];
            if (ccn_bloom_match_wire(f, content_object + start, stop - start))
                return(0);
        }
    }
    // test any other qualifiers here
    return(1);
}
