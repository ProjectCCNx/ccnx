/**
 * @file ccnrm.c
 * Mark as stale any local items a matching given prefixes.
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-o outfile] ccnx:/a/b ...\n"
            "   Remove (mark stale) content matching the given ccn URIs\n"
            "   -o outfile - write the ccnb-encoded content to the named file\n",
            progname);
    exit(1);
}

/***********
<Interest>
  <Name/>
  <AnswerOriginKind>19</AnswerOriginKind>
  <Scope>0</Scope>
</Interest>
**********/
struct ccn_charbuf *
local_scope_rm_template(void)
{
    struct ccn_charbuf *templ = ccn_charbuf_create();
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccnb_element_begin(templ, CCN_DTAG_Name);
    ccnb_element_end(templ); /* </Name> */
    ccnb_tagged_putf(templ, CCN_DTAG_AnswerOriginKind, "%2d",
                     (CCN_AOK_EXPIRE | CCN_AOK_DEFAULT));
    ccnb_tagged_putf(templ, CCN_DTAG_Scope, "0");
    ccnb_element_end(templ); /* </Interest> */
    return(templ);
}

static
struct mydata {
    int nseen;
    FILE *output;
} mydata = {0, NULL};

enum ccn_upcall_res
incoming_content(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    struct mydata *md = selfp->data;

    if (kind == CCN_UPCALL_FINAL)
        return(CCN_UPCALL_RESULT_OK);
    if (md == NULL)
        return(CCN_UPCALL_RESULT_ERR);
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
        return(CCN_UPCALL_RESULT_REEXPRESS);
    if ((kind != CCN_UPCALL_CONTENT && kind != CCN_UPCALL_CONTENT_UNVERIFIED))
        return(CCN_UPCALL_RESULT_ERR);
    md->nseen++;
    if (md->output != NULL)
        fwrite(info->content_ccnb, info->pco->offset[CCN_PCO_E], 1, md->output);
    return(CCN_UPCALL_RESULT_REEXPRESS);
}

/* Use some static data for this simple program */
static struct ccn_closure incoming_content_action = {
    .p = &incoming_content,
    .data = &mydata
};

int
main(int argc, char **argv)
{
    struct ccn *ccn = NULL;
    struct ccn_charbuf *c = NULL;
    struct ccn_charbuf *templ = NULL;
    int i;
    int res;
    int opt;
    FILE* closethis = NULL;
    
    while ((opt = getopt(argc, argv, "ho:")) != -1) {
        switch (opt) {
            case 'o':
                if (strcmp(optarg, "-") == 0)
                    mydata.output = stdout;
                else
                    mydata.output = closethis = fopen(optarg, "wb");
                if (mydata.output == NULL) {
                    perror(optarg);
                    exit(1);
                }
                break;
            case 'h': /* FALLTHRU */
            default: usage(argv[0]);
        }
    }
    
    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }
    c = ccn_charbuf_create();
    /* set scope to only address ccnd, expire anything we get */
    templ = local_scope_rm_template();
    for (i = optind; argv[i] != NULL; i++) {
        c->length = 0;
        res = ccn_name_from_uri(c, argv[i]);
        if (res < 0) {
            fprintf(stderr, "%s: bad ccn URI: %s\n", argv[0], argv[i]);
            exit(1);
        }
        ccn_express_interest(ccn, c, &incoming_content_action, templ);
    }
    if (i == optind)
        usage(argv[0]);
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&c);
    for (i = 0;; i++) {
        res = mydata.nseen;
        ccn_run(ccn, 100); /* stop if we run dry for 1/10 sec */
        if (res == mydata.nseen)
            break;
    }
    if (closethis != NULL)
        fclose(closethis);
    ccn_destroy(&ccn);
    fprintf(stderr, "marked stale: %d\n", mydata.nseen);
    exit(0);
}
