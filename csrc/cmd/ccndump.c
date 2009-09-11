/**
 * @file ccndump.c
 * Dumps everything quickly retrievable to stdout
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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
#include <ccn/uri.h>

struct ccn_charbuf *
local_scope_template(int allow_stale)
{
    struct ccn_charbuf *templ = ccn_charbuf_create();
    int res = 0;
    res |= ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
    /* <Name/> */
    res |= ccn_charbuf_append_tt(templ, CCN_DTAG_Name, CCN_DTAG);
    res |= ccn_charbuf_append_closer(templ); /* </Name> */
    /* <OrderPreference>4</OrderPreference> */
    res |= ccn_charbuf_append_tt(templ, CCN_DTAG_OrderPreference, CCN_DTAG);
    res |= ccnb_append_number(templ, 4);
    res |= ccn_charbuf_append_closer(templ); /* </OrderPreference> */
    if (allow_stale) {
        /* <AnswerOriginKind>5</AnswerOriginKind> */
        res |= ccn_charbuf_append_tt(templ, CCN_DTAG_AnswerOriginKind, CCN_DTAG);
        res |= ccnb_append_number(templ, (CCN_AOK_CS + CCN_AOK_STALE));
        res |= ccn_charbuf_append_closer(templ); /* </AnswerOriginKind> */
    }
    /* <Scope>0</Scope> */
    res |= ccn_charbuf_append_tt(templ, CCN_DTAG_Scope, CCN_DTAG);
    res |= ccnb_append_number(templ, 0);
    res |= ccn_charbuf_append_closer(templ); /* </Scope> */
    res |= ccn_charbuf_append_closer(templ); /* </Interest> */
    if (res < 0) abort();
    return(templ);
}

static
struct mydata {
    int nseen;
} mydata = {0};

enum ccn_upcall_res
incoming_content(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    struct ccn_charbuf *c = NULL;
    struct ccn_charbuf *templ = NULL;
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    struct ccn_indexbuf *comps = NULL;
    struct mydata *md = selfp->data;
    int res;

    if (kind == CCN_UPCALL_FINAL)
        return(CCN_UPCALL_RESULT_OK);
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
        return(CCN_UPCALL_RESULT_REEXPRESS);
    if (md == NULL)
        return(CCN_UPCALL_RESULT_ERR);
    if (kind != CCN_UPCALL_CONTENT &&
        kind != CCN_UPCALL_CONTENT_UNVERIFIED &&
        kind != CCN_UPCALL_CONTENT_BAD)
        return(CCN_UPCALL_RESULT_ERR);
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    md->nseen++;
    (void)fwrite(ccnb, ccnb_size, 1, stdout);
    /* Use the name of the content just received as the resumption point */
    c = ccn_charbuf_create();
    ccn_name_init(c);
    comps = info->content_comps;
    ccn_name_append_components(c, ccnb, comps->buf[0], comps->buf[comps->n-1]);
    /* Use the full name, including digest, to ensure we move along */
    ccn_digest_ContentObject(ccnb, info->pco);
    ccn_name_append(c, info->pco->digest, info->pco->digest_bytes);
    templ = local_scope_template(selfp->intdata);
    // XXX - This program cannot work anymore as written.
    res = ccn_express_interest(info->h, c, /* info->pi->prefix_comps,*/ selfp, templ);
    if (res < 0) abort();
    ccn_charbuf_destroy(&c);
    ccn_charbuf_destroy(&templ);
    return(CCN_UPCALL_RESULT_OK);
}

/* Use some static data for this simple program */
static struct ccn_closure incoming_content_action = {
    .p = &incoming_content,
    .data = &mydata
};

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-a] [uri]\n"
            "   Dumps everything quickly retrievable\n"
            "   -a - allow stale data\n",
            progname);
    exit(1);
}

int
main(int argc, char **argv)
{
    struct ccn *ccn = NULL;
    struct ccn_charbuf *c = NULL;
    struct ccn_charbuf *templ = NULL;
    int allow_stale = 0;
    int i;
    int ch;
    int res;
    extern int optind;
    int oldseen = -1;

    while ((ch = getopt(argc, argv, "ha")) != -1) {
        switch (ch) {
            case 'a':
                allow_stale = 1;
                break;
            case 'h':
            default:
                usage(argv[0]);
        }
    }
    
    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }
    c = ccn_charbuf_create();
    /* set scope to only address ccnd */
    templ = local_scope_template(allow_stale);
    if (argv[optind] == NULL)
        ccn_name_init(c);
    else {
        res = ccn_name_from_uri(c, argv[optind]);
        if (res < 0) {
            fprintf(stderr, "%s: bad ccn URI: %s\n", argv[0], argv[optind]);
            exit(1);
        }
        if (argv[optind+1] != NULL)
            fprintf(stderr, "%s warning: extra arguments ignored\n", argv[0]);
    }
    res = ccn_express_interest(ccn, c, -1, &incoming_content_action, templ);
    if (res < 0)
        abort();
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&c);
    for (i = 0;; i++) {
        ccn_run(ccn, 100); /* stop if we run dry for 1/10 sec */
        fflush(stdout);
        if (mydata.nseen == oldseen)
            break;
        oldseen = mydata.nseen;
    }
    ccn_destroy(&ccn);
    if (ferror(stdout)) {
        fprintf(stderr, "\nWarning: output from %s may be incomplete.\n", argv[0]);
        exit(1);
    }
    exit(0);
}
