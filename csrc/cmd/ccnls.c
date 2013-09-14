/**
 * @file ccnls.c
 * Attempts to list name components available at the next level of the hierarchy.
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2008, 2009, 2011, 2013 Palo Alto Research Center, Inc.
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
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

struct upcalldata {
    int magic; /* 856372 */
    long *counter;
    unsigned warn;
    unsigned option;
    int n_excl;
    int scope;
    struct ccn_charbuf **excl; /* Array of n_excl items */
};

#define MUST_VERIFY 0x01

static int /* for qsort */
namecompare(const void *a, const void *b)
{
    const struct ccn_charbuf *aa = *(const struct ccn_charbuf **)a;
    const struct ccn_charbuf *bb = *(const struct ccn_charbuf **)b;
    int ans = ccn_compare_names(aa->buf, aa->length, bb->buf, bb->length);
    if (ans == 0)
        fprintf(stderr, "wassat? %d\n", __LINE__);
    return (ans);
}

enum ccn_upcall_res
incoming_content(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    struct ccn_charbuf *c = NULL;
    struct ccn_charbuf *comp = NULL;
    struct ccn_charbuf *uri = NULL;
    struct ccn_charbuf *templ = NULL;
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    struct ccn_indexbuf *comps = NULL;
    int matched_comps = 0;
    int res;
    int i;
    struct upcalldata *data = selfp->data;
    
    if (data->magic != 856372) abort();
    if (kind == CCN_UPCALL_FINAL)
        return(CCN_UPCALL_RESULT_OK);
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
        return(CCN_UPCALL_RESULT_REEXPRESS);
    if (kind == CCN_UPCALL_CONTENT_UNVERIFIED) {
        if ((data->option & MUST_VERIFY) != 0)
        return(CCN_UPCALL_RESULT_VERIFY);
        }
    else if (kind != CCN_UPCALL_CONTENT) abort();
    
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    comps = info->content_comps;
    matched_comps = info->pi->prefix_comps;
    c = ccn_charbuf_create();
    uri = ccn_charbuf_create();
    templ = ccn_charbuf_create();
    /* note that comps->n is 1 greater than the number of explicit components */
    if (matched_comps > comps->n) {
        ccn_uri_append(c, ccnb, ccnb_size, 1);
        fprintf(stderr, "How did this happen?  %s\n", ccn_charbuf_as_string(uri));
        exit(1);
    }
    data->counter[0]++;
    /* Recover the same prefix as before */
    ccn_name_init(c);
    res = ccn_name_append_components(c, info->interest_ccnb,
                                     info->interest_comps->buf[0],
                                     info->interest_comps->buf[matched_comps]);
    if (res < 0) abort();
    
    comp = ccn_charbuf_create();
    ccn_name_init(comp);
    if (matched_comps + 1 == comps->n) {
        /* Reconstruct the implicit ContentObject digest component */
        ccn_digest_ContentObject(ccnb, info->pco);
        ccn_name_append(comp, info->pco->digest, info->pco->digest_bytes);
    }
    else if (matched_comps < comps->n) {
        ccn_name_append_components(comp, ccnb,
                                   comps->buf[matched_comps],
                                   comps->buf[matched_comps + 1]);
    }
    res = ccn_uri_append(uri, comp->buf, comp->length, 0);
    if (res < 0 || uri->length < 1)
        fprintf(stderr, "*** Error: ccnls line %d res=%d\n", __LINE__, res);
    else {
        if (uri->length == 1)
            ccn_charbuf_append(uri, ".", 1);
        printf("%s%s\n", ccn_charbuf_as_string(uri) + 1,
               kind == CCN_UPCALL_CONTENT ? " [verified]" : " [unverified]");
    }
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccn_charbuf_append(templ, c->buf, c->length); /* Name */
    if (matched_comps == comps->n) {
        /* The interest supplied the digest component */
        ccn_charbuf_destroy(&comp);
        /*
         * We can't rely on the Exclude filter to keep from seeing this, so 
         * say that we need at least one more name component.
         */
        ccnb_append_tagged_udata(templ, CCN_DTAG_MinSuffixComponents, "1", 1);
    }
    else {
        data->excl = realloc(data->excl, (data->n_excl + 1) * sizeof(data->excl[0]));
        data->excl[data->n_excl++] = comp;
        comp = NULL;
    }
    qsort(data->excl, data->n_excl, sizeof(data->excl[0]), &namecompare);
    ccnb_element_begin(templ, CCN_DTAG_Exclude);
    for (i = 0; i < data->n_excl; i++) {
        comp = data->excl[i];
        if (comp->length < 4) abort();
        ccn_charbuf_append(templ, comp->buf + 1, comp->length - 2);
    }
    comp = NULL;
    ccnb_element_end(templ); /* </Exclude> */
    ccnb_tagged_putf(templ, CCN_DTAG_AnswerOriginKind, "%d", CCN_AOK_CS);
    if (data->scope > -1)
       ccnb_tagged_putf(templ, CCN_DTAG_Scope, "%d", data->scope);
    ccnb_element_end(templ); /* </Interest> */
    if (templ->length > data->warn) {
        fprintf(stderr, "*** Interest packet is %d bytes\n", (int)templ->length);
        data->warn = data->warn * 8 / 5;
    }
    ccn_express_interest(info->h, c, selfp, templ);
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&c);
    ccn_charbuf_destroy(&uri);
    return(CCN_UPCALL_RESULT_OK);
}

void
usage(const char *prog)
{
    fprintf(stderr, "Usage: %s uri\n"
            "   Prints names with uri as prefix\n"
            "     environment var CCN_SCOPE is scope for interests (0, 1 or 2, no default)\n"
            "     environment var CCN_LINGER is no-data timeout (seconds) default 0.5s\n"
            "     environment var CCN_VERIFY indicates signature verification is required (non-zero)\n", prog);
    exit(1);
}

int
main(int argc, char **argv)
{
    struct ccn *ccn = NULL;
    struct ccn_charbuf *c = NULL;
    struct ccn_charbuf *templ = NULL;
    struct upcalldata *data = NULL;
    int i;
    int n;
    int res;
    long counter = 0;
    struct ccn_closure *cl = NULL;
    int timeout_ms = 500;
    const char *env_timeout = getenv("CCN_LINGER");
    const char *env_verify = getenv("CCN_VERIFY");
    const char *env_scope = getenv("CCN_SCOPE");

    if (argv[1] == NULL || argv[2] != NULL)
        usage(argv[0]);

    if (env_timeout != NULL && (i = atoi(env_timeout)) > 0)
        timeout_ms = i * 1000;

    c = ccn_charbuf_create();
    res = ccn_name_from_uri(c, argv[1]);
    if (res < 0)
        usage(argv[0]);
        
    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }
    
    data = calloc(1, sizeof(*data));
    data->magic = 856372;
    data->warn = 1492;
    data->counter = &counter;
    data->option = 0;
    if (env_verify && *env_verify)
        data->option |= MUST_VERIFY;
    data->scope = -1;
    if (env_scope != NULL && (i = atoi(env_scope)) >= 0)
      data->scope = i;
    cl = calloc(1, sizeof(*cl));
    cl->p = &incoming_content;
    cl->data = data;
    if (data->scope > -1) {
        templ = ccn_charbuf_create();
        ccnb_element_begin(templ, CCN_DTAG_Interest);
        ccnb_element_begin(templ, CCN_DTAG_Name);
        ccnb_element_end(templ); /* </Name> */
        ccnb_tagged_putf(templ, CCN_DTAG_Scope, "%d", data->scope);
        ccnb_element_end(templ); /* </Interest> */
    }
    ccn_express_interest(ccn, c, cl, templ);
    ccn_charbuf_destroy(&templ);
    cl = NULL;
    data = NULL;
    for (i = 0;; i++) {
        n = counter;
        ccn_run(ccn, timeout_ms); /* stop if we run dry for 1/2 sec */
        fflush(stdout);
        if (counter == n)
            break;
    }
    ccn_destroy(&ccn);
    ccn_charbuf_destroy(&c);
    exit(0);
}
