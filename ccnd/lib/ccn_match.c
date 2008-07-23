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

int
ccn_content_matches_interest(const unsigned char *content_object,
                             size_t content_object_size,
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
    prefixstart = pi->offset[CCN_PI_B_Component0];
    prefixbytes = pi->offset[CCN_PI_E_ComponentLast] - prefixstart;
    namecompstart = pc->offset[CCN_PCO_B_Component0];
    namecompbytes = pc->offset[CCN_PCO_E_ComponentLast] - namecompstart;
    if (prefixbytes > namecompbytes) {
        /*
         * The only way for this to be a match is if the implicit
         * content digest name component comes into play.
         */
        if (pi->offset[CCN_PI_B_ComponentLast] - prefixstart == namecompbytes &&
            (pi->offset[CCN_PI_E_ComponentLast] -
             pi->offset[CCN_PI_B_ComponentLast])  == 1 + 2 + 32 + 1) {
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
                        interest_msg + pi->offset[CCN_PI_B_ComponentLast],
                        (pi->offset[CCN_PI_E_ComponentLast] -
                         pi->offset[CCN_PI_B_ComponentLast]));
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
            // Stubbed out. Pretend nextcomp matches, hence excluding this one.
            return(0);
        }
    }
    /*
     * At this point the prefix match and exclude-by-next-component is done.
     */
    // test any other qualifiers here
    return(1);
}
