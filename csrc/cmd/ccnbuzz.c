/**
 * @file ccnbuzz.c
 * Pre-reads stuff written by ccnsendchunks, produces no output.
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2008-2011, 2013 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

/**
 * Pre-reads stuff written by ccnsendchunks, produces no output
 * This is meant to be run in parallel with ccncatchunks to experiment
 * with the benefits of one kind of pipelining.
 *
 * The idea is to use the Exclude Bloom filters to artificially divide the 
 * possible interests into several different classes.  For example, you
 * might use 8 bits per Bloom filter, and just one hash function, so the
 * 8 different filters
 *    B0 = 01111111
 *    B1 = 10111111
 *      ...
 *    B8 = 11111110
 * will serve to partition the interests into 8 different classes and so at any
 * given time and node there can be 8 different pending interests for the prefix.
 * When a piece of content arrives at the endpoint, a new interest is issued
 * that uses the same Bloom filter, but is restricted to content with a larger
 * sequence number than the content that just arrived.
 * The "real" consumer gets its content by explicitly using the sequence
 * numbers in its requests; almost all of these will get fulfilled out of a
 * nearby cache and so few of the actual interests will need to propagate
 * out to the network.
 * Note that this scheme does not need to be aware of the sequence numbering
 * algorithm; it only relies on them to be increasing according to the
 * canonical ordering.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-a] [-n count] ccnx:/a/b\n"
            "   Pre-reads stuff written by ccnsendchunks, produces no output\n"
            "   -a - allow stale data\n"
            "   -n count - specify number of pipeline slots\n",
            progname);
    exit(1);
}

struct mydata {
    int allow_stale;
};

static void
append_bloom_element(struct ccn_charbuf *templ,
                     enum ccn_dtag dtag, struct ccn_bloom *b)
{
        int i;
        ccnb_element_begin(templ, dtag);
        i = ccn_bloom_wiresize(b);
        ccn_charbuf_append_tt(templ, i, CCN_BLOB);
        ccn_bloom_store_wire(b, ccn_charbuf_reserve(templ, i), i);
        templ->length += i;
        ccnb_element_end(templ);
}

/*
 * This appends a tagged, valid, fully-saturated Bloom filter, useful for
 * excluding everything between two 'fenceposts' in an Exclude construct.
 */
static void
append_bf_all(struct ccn_charbuf *c)
{
    unsigned char bf_all[9] = { 3, 1, 'A', 0, 0, 0, 0, 0, 0xFF };
    const struct ccn_bloom_wire *b = ccn_bloom_validate_wire(bf_all, sizeof(bf_all));
    if (b == NULL) abort();
    ccnb_append_tagged_blob(c, CCN_DTAG_Bloom, bf_all, sizeof(bf_all));
}

static struct ccn_bloom *
make_partition(unsigned i, int lg_n)
{
    struct ccn_bloom_wire template = {0};
    struct ccn_bloom *ans = NULL;
    unsigned j;
    
    if (lg_n > 13 || i >= (1U << lg_n)) abort();
    if (lg_n >= 3)
        template.lg_bits = lg_n;
    else
        template.lg_bits = 3;
    template.n_hash = 1;
    template.method = 'A';
    memset(template.bloom, ~0, sizeof(template.bloom));
    /* This loop is here to replicate out to a byte if lg_n < 3 */
    for (j = i; j < (1U << template.lg_bits); j += (1U << lg_n))
        template.bloom[j / 8] -= (1U << (j % 8));
    ans = ccn_bloom_from_wire(&template, 8 + (1 << (template.lg_bits - 3)));
    return(ans);
}

struct ccn_charbuf *
make_template(struct mydata *md, struct ccn_upcall_info *info, struct ccn_bloom *b)
{
    struct ccn_charbuf *templ = ccn_charbuf_create();
    const unsigned char *ib = NULL; /* info->interest_ccnb */
    const unsigned char *cb = NULL; /* info->content_ccnb */
    struct ccn_indexbuf *cc = NULL;
    struct ccn_parsed_interest *pi = NULL;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    size_t start;
    size_t stop;
    
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccnb_element_begin(templ, CCN_DTAG_Name);
    ccnb_element_end(templ); /* </Name> */
    // XXX - use pubid if possible
    ccnb_element_begin(templ, CCN_DTAG_MaxSuffixComponents);
    ccnb_append_number(templ, 2);
    ccnb_element_end(templ); /* </MaxSuffixComponents> */
    if (info != NULL) {
        ccnb_element_begin(templ, CCN_DTAG_Exclude);
        ib = info->interest_ccnb;
        cb = info->content_ccnb;
        cc = info->content_comps;
        append_bf_all(templ);
        /* Insert the last Component in the filter */
        ccn_charbuf_append(templ,
                           cb + cc->buf[cc->n - 2],
                           cc->buf[cc->n - 1] - cc->buf[cc->n - 2]);
        if (b == NULL) {
            /* Look for Bloom in the matched interest */
            pi = info->pi;
            if (pi->offset[CCN_PI_E_Exclude] > pi->offset[CCN_PI_B_Exclude]) {
                start = stop = 0;
                d = ccn_buf_decoder_start(&decoder,
                                          ib + pi->offset[CCN_PI_B_Exclude],
                                          pi->offset[CCN_PI_E_Exclude] -
                                          pi->offset[CCN_PI_B_Exclude]);
                if (!ccn_buf_match_dtag(d, CCN_DTAG_Exclude))
                    d->decoder.state = -1;
                ccn_buf_advance(d);
                if (ccn_buf_match_dtag(d, CCN_DTAG_Bloom)) {
                    start = pi->offset[CCN_PI_B_Exclude] + d->decoder.token_index;
                    ccn_buf_advance(d);
                    if (ccn_buf_match_blob(d, NULL, NULL))
                        ccn_buf_advance(d);
                    ccn_buf_check_close(d);
                    stop = pi->offset[CCN_PI_B_Exclude] + d->decoder.token_index;
                }
                if (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
                    ccn_buf_advance(d);
                    if (ccn_buf_match_blob(d, NULL, NULL))
                        ccn_buf_advance(d);
                    ccn_buf_check_close(d);
                    start = pi->offset[CCN_PI_B_Exclude] + d->decoder.token_index;
                    if (ccn_buf_match_dtag(d, CCN_DTAG_Bloom)) {
                        ccn_buf_advance(d);
                        if (ccn_buf_match_blob(d, NULL, NULL))
                            ccn_buf_advance(d);
                        ccn_buf_check_close(d);
                    }
                    stop = pi->offset[CCN_PI_B_Exclude] + d->decoder.token_index;
                }
                if (d->decoder.state >= 0)
                    ccn_charbuf_append(templ, ib + start, stop - start);                
            }
        }
        else {
            /* Use the supplied Bloom */
            append_bloom_element(templ, CCN_DTAG_Bloom, b);
        }
        ccnb_element_end(templ); /* </Exclude> */
    }
    else if (b != NULL) {
        ccnb_element_begin(templ, CCN_DTAG_Exclude);
        append_bloom_element(templ, CCN_DTAG_Bloom, b);
        ccnb_element_end(templ); /* </Exclude> */
    }
    if (md->allow_stale) {
        ccnb_element_begin(templ, CCN_DTAG_AnswerOriginKind);
        ccnb_append_number(templ,
                                                CCN_AOK_DEFAULT | CCN_AOK_STALE);
        ccnb_element_end(templ); /* </AnswerOriginKind> */
    }
    ccnb_element_end(templ); /* </Interest> */
    return(templ);
}

static enum ccn_upcall_res
incoming_content(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    const unsigned char *data = NULL;
    size_t data_size = 0;
    const unsigned char *cb = NULL; /* info->content_ccnb */
    struct ccn_indexbuf *cc = NULL;
    int res;
    struct mydata *md = selfp->data;
    
    if (kind == CCN_UPCALL_FINAL) {
        if (md != NULL) {
            selfp->data = NULL;
            free(md);
            md = NULL;
        }
        return(CCN_UPCALL_RESULT_OK);
    }
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
        return(CCN_UPCALL_RESULT_REEXPRESS);
    if (kind != CCN_UPCALL_CONTENT && kind != CCN_UPCALL_CONTENT_UNVERIFIED)
        return(CCN_UPCALL_RESULT_ERR);
    if (md == NULL)
        selfp->data = md = calloc(1, sizeof(*md));
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    cb = info->content_ccnb;
    cc = info->content_comps;
    res = ccn_content_get_value(ccnb, ccnb_size, info->pco, &data, &data_size);
    if (res < 0) abort();
    
    /* Ask for the next one */
    name = ccn_charbuf_create();
    ccn_name_init(name);
    if (cc->n < 2) abort();
    res = ccn_name_append_components(name, cb, cc->buf[0], cc->buf[cc->n - 1]);
    if (res < 0) abort();
    templ = make_template(md, info, NULL);
    // XXX - this program might not work correctly anymore
    res = ccn_express_interest(info->h, name, /* info->pi->prefix_comps,*/ selfp, templ);
    if (res < 0) abort();
    
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&name);
    
    return(CCN_UPCALL_RESULT_OK);
}

int
main(int argc, char **argv)
{
    struct ccn *ccn = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    struct ccn_closure *incoming = NULL;
    const char *arg = NULL;
    int res;
    int opt;
    struct mydata *mydata;
    int allow_stale = 0;
    int lg_n = 3;
    unsigned n = 8;
    int i;
    
    while ((opt = getopt(argc, argv, "han:")) != -1) {
        switch (opt) {
            case 'a':
                allow_stale = 1;
                break;
            case 'n':
                n = atoi(optarg);
                if (n < 2 || n > 8*1024) {
                    fprintf(stderr, "invalid -n value\n");
                    usage(argv[0]);
                }
                break;
            case 'h':
            default:
                usage(argv[0]);
        }
    }
    for (lg_n = 0; (1U << lg_n) < n; lg_n++)
        continue;
    n = 1U << lg_n;
    arg = argv[optind];
    if (arg == NULL)
        usage(argv[0]);
    name = ccn_charbuf_create();
    res = ccn_name_from_uri(name, arg);
    if (res < 0) {
        fprintf(stderr, "%s: bad ccn URI: %s\n", argv[0], arg);
        exit(1);
    }
    if (argv[optind + 1] != NULL)
        fprintf(stderr, "%s warning: extra arguments ignored\n", argv[0]);
    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }
    incoming = calloc(1, sizeof(*incoming));
    incoming->p = &incoming_content;
    mydata = calloc(1, sizeof(*mydata));
    mydata->allow_stale = allow_stale;
    incoming->data = mydata;
    
    for (i = 0; i < n; i++) {
        struct ccn_bloom *b = make_partition(i, lg_n);
        templ = make_template(mydata, NULL, b);
        ccn_express_interest(ccn, name, incoming, templ);
        ccn_charbuf_destroy(&templ);
        ccn_bloom_destroy(&b);
    }
    
    ccn_charbuf_destroy(&name);
    while (res >= 0) {
        res = ccn_run(ccn, 1000);
    }
    ccn_destroy(&ccn);
    exit(res < 0);
}
