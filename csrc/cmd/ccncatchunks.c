/**
 * @file ccncatchunks.c
 * Reads stuff written by ccnsendchunks, writes to stdout.
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2008-2010, 2013 Palo Alto Research Center, Inc.
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

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-a] ccnx:/a/b\n"
            "   Reads stuff written by ccnsendchunks under"
            " the given uri and writes to stdout\n"
            "   -a - allow stale data\n",
            progname);
    exit(1);
}

struct mydata {
    int allow_stale;
};

struct ccn_charbuf *
make_template(struct mydata *md, struct ccn_upcall_info *info)
{
    struct ccn_charbuf *templ = ccn_charbuf_create();
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccnb_element_begin(templ, CCN_DTAG_Name);
    ccnb_element_end(templ); /* </Name> */
    // XXX - use pubid if possible
    ccnb_element_begin(templ, CCN_DTAG_MaxSuffixComponents);
    ccnb_append_number(templ, 1);
    ccnb_element_end(templ); /* </MaxSuffixComponents> */
    if (md->allow_stale) {
        ccnb_element_begin(templ, CCN_DTAG_AnswerOriginKind);
        ccnb_append_number(templ, CCN_AOK_DEFAULT | CCN_AOK_STALE);
        ccnb_element_end(templ); /* </AnswerOriginKind> */
    }
    ccnb_element_end(templ); /* </Interest> */
    return(templ);
}

#define CHUNK_SIZE 1024

enum ccn_upcall_res
incoming_content(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    struct ccn_charbuf *temp = NULL;
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
    if (kind != CCN_UPCALL_CONTENT && kind != CCN_UPCALL_CONTENT_UNVERIFIED)
        return(CCN_UPCALL_RESULT_ERR);
    if (md == NULL)
        selfp->data = md = calloc(1, sizeof(*md));
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    ib = info->interest_ccnb;
    ic = info->interest_comps;
    /* XXX - must verify sig, and make sure it is LEAF content */
    res = ccn_content_get_value(ccnb, ccnb_size, info->pco, &data, &data_size);
    if (res < 0) abort();
    if (data_size > CHUNK_SIZE) {
        /* For us this is spam. Give up now. */
        fprintf(stderr, "*** Segment %d found with a data size of %d."
                        " This program only works with segments of 1024 bytes."
                        " Try ccncatchunks2 instead.\n",
                        (int)selfp->intdata, (int)data_size);
        exit(1);
    }
    
    /* OK, we will accept this block. */
    
    written = fwrite(data, data_size, 1, stdout);
    if (written != 1)
        exit(1);
    
    /* A short block signals EOF for us. */
    if (data_size < CHUNK_SIZE)
        exit(0);
    
    /* Ask for the next one */
    name = ccn_charbuf_create();
    ccn_name_init(name);
    if (ic->n < 2) abort();
    res = ccn_name_append_components(name, ib, ic->buf[0], ic->buf[ic->n - 2]);
    if (res < 0) abort();
    temp = ccn_charbuf_create();
    ccn_charbuf_putf(temp, "%d", ++(selfp->intdata));
    ccn_name_append(name, temp->buf, temp->length);
    ccn_charbuf_destroy(&temp);
    templ = make_template(md, info);
    
    res = ccn_express_interest(info->h, name, selfp, templ);
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
    ccn_name_append(name, "0", 1);
    incoming = calloc(1, sizeof(*incoming));
    incoming->p = &incoming_content;
    mydata = calloc(1, sizeof(*mydata));
    mydata->allow_stale = allow_stale;
    incoming->data = mydata;
    templ = make_template(mydata, NULL);
    ccn_express_interest(ccn, name, incoming, templ);
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&name);
    /* Run a little while to see if there is anything there */
    res = ccn_run(ccn, 200);
    if (incoming->intdata == 0) {
        fprintf(stderr, "%s: not found: %s\n", argv[0], arg);
        exit(1);
    }
    /* We got something, run until end of data or somebody kills us */
    while (res >= 0) {
        fflush(stdout);
        res = ccn_run(ccn, 200);
    }
    ccn_destroy(&ccn);
    exit(res < 0);
}
