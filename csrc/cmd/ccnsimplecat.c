/**
 * @file ccnsimplecat.c
 * Reads streams at the given CCNx URIs and writes to stdout
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2009-2013 Palo Alto Research Center, Inc.
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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

/**
 * Provide usage hints for the program and then exit with a non-zero status.
 */
static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-a] ccnx:/a/b ...\n"
            "   Reads streams at"
            " the given ccn URIs and writes to stdout\n"
            "   -a - allow stale data\n",
            progname);
    exit(1);
}

struct mydata {
    int *done;
    int allow_stale;
};

/**
 * Construct a template suitable for use with ccn_express_interest
 * indicating at least one suffix component, and stale data if so
 * requested.
 */
struct ccn_charbuf *
make_template(struct mydata *md, struct ccn_upcall_info *info)
{
    struct ccn_charbuf *templ = ccn_charbuf_create();
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccnb_element_begin(templ, CCN_DTAG_Name);
    ccnb_element_end(templ); /* </Name> */
    // XXX - use pubid if possible
    ccnb_element_begin(templ, CCN_DTAG_MinSuffixComponents);
    ccnb_append_number(templ, 1);
    ccnb_element_end(templ); /* </MinSuffixComponents> */
    if (md->allow_stale) {
        ccnb_element_begin(templ, CCN_DTAG_AnswerOriginKind);
        ccnb_append_number(templ, CCN_AOK_DEFAULT | CCN_AOK_STALE);
        ccnb_element_end(templ); /* </AnswerOriginKind> */
    }
    ccnb_element_end(templ); /* </Interest> */
    return(templ);
}

/**
 * Handle the incoming content messages. Extracts the data, and
 * requests the next block in sequence if the received block was
 * not the final one.
 */
enum ccn_upcall_res
incoming_content(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    const unsigned char *data = NULL;
    size_t data_size = 0;
    size_t written;
    const unsigned char *ib = NULL; /* info->interest_ccnb */
    struct ccn_indexbuf *ic = NULL;
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
    if (kind == CCN_UPCALL_CONTENT_UNVERIFIED)
        return(CCN_UPCALL_RESULT_VERIFY);
    if (kind != CCN_UPCALL_CONTENT)
        return(CCN_UPCALL_RESULT_ERR);
    if (md == NULL)
        selfp->data = md = calloc(1, sizeof(*md));
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    ib = info->interest_ccnb;
    ic = info->interest_comps;
    res = ccn_content_get_value(ccnb, ccnb_size, info->pco, &data, &data_size);
    if (res < 0) abort();
    if (info->pco->type != CCN_CONTENT_DATA) {
        /* For us this is spam. For now, give up. */
        fprintf(stderr, "*** spammed at block %d\n", (int)selfp->intdata);
        exit(1);
    }
    
    /* OK, we will accept this block. */
    if (data_size == 0)
        *(md->done) = 1;
    else {
        written = fwrite(data, data_size, 1, stdout);
        if (written != 1)
            exit(1);
    }
    // XXX The test below should get refactored into the library
    if (info->pco->offset[CCN_PCO_B_FinalBlockID] !=
        info->pco->offset[CCN_PCO_E_FinalBlockID]) {
        const unsigned char *finalid = NULL;
        size_t finalid_size = 0;
        const unsigned char *nameid = NULL;
        size_t nameid_size = 0;
        struct ccn_indexbuf *cc = info->content_comps;
        ccn_ref_tagged_BLOB(CCN_DTAG_FinalBlockID, ccnb,
                            info->pco->offset[CCN_PCO_B_FinalBlockID],
                            info->pco->offset[CCN_PCO_E_FinalBlockID],
                            &finalid,
                            &finalid_size);
        if (cc->n < 2) abort();
        ccn_ref_tagged_BLOB(CCN_DTAG_Component, ccnb,
                            cc->buf[cc->n - 2],
                            cc->buf[cc->n - 1],
                            &nameid,
                            &nameid_size);
        if (finalid_size == nameid_size &&
              0 == memcmp(finalid, nameid, nameid_size))
            *(md->done) = 1;
    }
    
    if (*(md->done)) {
        ccn_set_run_timeout(info->h, 0);
        return(CCN_UPCALL_RESULT_OK);
    }
    
    /* Ask for the next fragment */
    name = ccn_charbuf_create();
    ccn_name_init(name);
    if (ic->n < 2) abort();
    res = ccn_name_append_components(name, ib, ic->buf[0], ic->buf[ic->n - 2]);
    if (res < 0) abort();
    ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, ++(selfp->intdata));
    templ = make_template(md, info);
    
    res = ccn_express_interest(info->h, name, selfp, templ);
    if (res < 0) abort();
    
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&name);
    
    return(CCN_UPCALL_RESULT_OK);
}

/**
 * Process options and then loop through command line CCNx URIs retrieving
 * the data and writing it to stdout.
 */
int
main(int argc, char **argv)
{
    struct ccn *ccn = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    struct ccn_closure *incoming = NULL;
    const char *arg = NULL;
    int i;
    int res;
    int opt;
    struct mydata *mydata;
    int allow_stale = 0;
    int *done;
    int exit_status = 0;
    
    done = calloc(1, sizeof(*done));
    
    while ((opt = getopt(argc, argv, "ha")) != -1) {
        switch (opt) {
            case 'a':
                allow_stale = 1;
                break;
            case 'h':
            default:
                usage(argv[0]);
        }
    }
    arg = argv[optind];
    if (arg == NULL)
        usage(argv[0]);
    name = ccn_charbuf_create();
    /* Check the args first */
    for (i = optind; argv[i] != NULL; i++) {
        name->length = 0;
        res = ccn_name_from_uri(name, argv[i]);
        if (res < 0) {
            fprintf(stderr, "%s: bad ccn URI: %s\n", argv[0], argv[i]);
            exit(1);
        }
    }
    for (i = optind; (arg = argv[i]) != NULL; i++) {
        *done = 0;
        name->length = 0;
        res = ccn_name_from_uri(name, arg);
        ccn = ccn_create();
        if (ccn_connect(ccn, NULL) == -1) {
            perror("Could not connect to ccnd");
            exit(1);
        }
        ccn_resolve_version(ccn, name, CCN_V_HIGHEST, 50);
        ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, 0);
        incoming = calloc(1, sizeof(*incoming));
        incoming->p = &incoming_content;
        mydata = calloc(1, sizeof(*mydata));
        mydata->allow_stale = allow_stale;
        mydata->done = done;
        incoming->data = mydata;
        templ = make_template(mydata, NULL);
        ccn_express_interest(ccn, name, incoming, templ);
        ccn_charbuf_destroy(&templ);
        /* Run a little while to see if there is anything there */
        res = ccn_run(ccn, 200);
        if ((!*done) && incoming->intdata == 0) {
            fprintf(stderr, "%s: not found: %s\n", argv[0], arg);
            res = -1;
        }
        /* We got something; run until end of data or somebody kills us */
        while (res >= 0 && !*done) {
            fflush(stdout);
            res = ccn_run(ccn, 333);
        }
        if (res < 0)
            exit_status = 1;
        ccn_destroy(&ccn);
        fflush(stdout);
        free(incoming);
        incoming = NULL;
    }
    ccn_charbuf_destroy(&name);
    free(done);
    exit(exit_status);
}
