/**
 * @file cmd/ccnsnew.c
 * 
 * A CCNx program to collect content objects as they arrive.
 */
/* Copyright (C) 2012 Palo Alto Research Center, Inc.
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

#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/reg_mgmt.h>
#include <ccn/uri.h>

#define USAGE "[-p port] [ -0123s ] ccnx:/uri ...\n collect arriving content"

static enum ccn_upcall_res incoming_interest(struct ccn_closure *,
                                             enum ccn_upcall_kind,
                                             struct ccn_upcall_info *);
static enum ccn_upcall_res  incoming_content(struct ccn_closure *,
                                             enum ccn_upcall_kind,
                                             struct ccn_upcall_info *);
static void usage(void);
static const char *progname;
static int setscope = 0;

int
main(int argc, char **argv)
{
    struct ccn *h;
    int fflags;
    int i;
    int opt;
    int res;
    const char *arg = NULL;
    struct ccn_charbuf *regprefix = NULL;
    struct ccn_closure in_interest = {0};
    
    setscope = 0;
    progname = argv[0];
    while ((opt = getopt(argc, argv, "0123sh")) != -1) {
        switch (opt) {
            case '0':
            case '1':
            case '2':
            case '3':
                setscope = opt - '0';
                break;
            case 's':
                setscope = -1;
                break;
            case 'h':
            default:
                usage();
        }
    }
    if (argv[optind] == NULL)
        usage();
    
    h = ccn_create();
    res = ccn_connect(h, NULL);
    if (res < 0) {
        ccn_perror(h, "ccn_connect");
        return(1);
    }
    regprefix = ccn_charbuf_create();
    in_interest.p = &incoming_interest;
    for (i = optind; (arg = argv[i]) != NULL; i++) {
        ccn_charbuf_reset(regprefix);
        res = ccn_name_from_uri(regprefix, arg);
        if (res < 0) {
            fprintf(stderr, "%s: not a valid ccnx URI\n", arg);
            usage();
        }
        fflags = CCN_FORW_TAP | CCN_FORW_CHILD_INHERIT | CCN_FORW_ACTIVE;
        res = ccn_set_interest_filter_with_flags(h, regprefix, &in_interest, fflags);
        if (res < 0) {
            ccn_perror(h, "ccn_set_interest_filter_with_flags");
            exit(1);
        }
    }
    ccn_run(h, -1);
    ccn_charbuf_destroy(&regprefix);
    ccn_destroy(&h);
    return(0);
}

struct mydata {
    struct ccn_closure self;
    int timeouts;
    const char *debug;
    char debugspace[1];
};

/** Content handler */
static enum ccn_upcall_res
incoming_content(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    struct mydata *md;
    size_t size;
    ssize_t resl;
    
    md = selfp->data;
    if (selfp != &md->self)
        return(CCN_UPCALL_RESULT_ERR);
    switch (kind) {
        case CCN_UPCALL_FINAL:
            selfp->data = NULL;
            free(md);
            break;
        case CCN_UPCALL_INTEREST_TIMED_OUT:
            md->timeouts++;
            break;
        case CCN_UPCALL_CONTENT_UNVERIFIED:
        case CCN_UPCALL_CONTENT:
            size = info->pco->offset[CCN_PCO_E];
            resl = write(1, info->content_ccnb, size);
            if (resl != size)
                exit(1);
            break;
        default:
            return(CCN_UPCALL_RESULT_ERR);
    }
    return(CCN_UPCALL_RESULT_OK);
}

/**
 * Me too - express the interest that we just saw, with small modifications
 *
 * The idea is to be able to get a copy of whatever content comes along to
 * satisfy the interest.
 *
 * Before sending the interest back out, we need to strip the Nonce, because
 * otherwise it will just be discarded as a duplicate.
 *
 * The scope may also be modified; normally it is set to 0 to minimize the
 * impact on traffic.
 */
static int
me_too(struct ccn *h,
       struct ccn_parsed_interest *pi,
       const unsigned char *imsg,
       int scope)
{
    struct ccn_charbuf *templ;
    struct ccn_charbuf *name;
    struct mydata *md;
    const unsigned char *p;
    size_t s;
    size_t t;
    int res;
    
    templ = ccn_charbuf_create();
    name = ccn_charbuf_create();
    p = &(imsg[pi->offset[CCN_PI_B_Name]]);
    s = pi->offset[CCN_PI_E_Name] - pi->offset[CCN_PI_B_Name];
    ccn_charbuf_append(name, p, s);
    p = imsg;
    s = pi->offset[CCN_PI_B_Scope];
    ccn_charbuf_append(templ, p, s);
    if (scope >= 0) {
        if (scope < 3)
            ccnb_tagged_putf(templ, CCN_DTAG_Scope, "%d", scope);
        s = pi->offset[CCN_PI_E_Scope];
    }
    t = pi->offset[CCN_PI_B_Nonce];
    ccn_charbuf_append(templ, p + s, t - s);
    ccn_charbuf_append_closer(templ);
    md = calloc(1, sizeof(*md));
    if (md == NULL)
        res = -1;
    else {
        md->self.p = &incoming_content;
        md->self.data = md;
        res = ccn_express_interest(h, name, &md->self, templ);
    }
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&templ);
    return(res);
}

/** Interest handler */
static enum ccn_upcall_res
incoming_interest(struct ccn_closure *selfp,
                  enum ccn_upcall_kind kind,
                  struct ccn_upcall_info *info)
{
    switch (kind) {
        case CCN_UPCALL_FINAL:
            /* no cleanup needed */
            break;
        case CCN_UPCALL_INTEREST:
        case CCN_UPCALL_CONSUMED_INTEREST:
            me_too(info->h, info->pi, info->interest_ccnb, setscope);
            break;
        default:
            return(CCN_UPCALL_RESULT_ERR);
    }
    return(CCN_UPCALL_RESULT_OK);
}

/** Usage */
static void
usage(void)
{
    fprintf(stderr, "%s: " USAGE "\n", progname);
    exit(1);
}
