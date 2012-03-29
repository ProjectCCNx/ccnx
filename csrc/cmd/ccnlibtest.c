/**
 * @file cmd/ccnlibtest.c
 */
/*
 * A CCNx program.
 *
 * Copyright (C) 2009, 2010, 2012 Palo Alto Research Center, Inc.
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
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

#include <ccn/ccn.h>

void printraw(const void *r, int n)
{
    int i, l;
    const unsigned char *p = r;
    while (n > 0) {
        l = (n > 40 ? 40 : n);
        for (i = 0; i < l; i++)
            printf(" %c", (' ' <= p[i] && p[i] <= '~') ? p[i] : '.');
        printf("\n");
        for (i = 0; i < l; i++)
            printf("%02X", p[i]);
        printf("\n");
        p += l;
        n -= l;
    }
}

enum ccn_upcall_res
incoming_content(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    if (kind == CCN_UPCALL_FINAL)
        return(CCN_UPCALL_RESULT_OK);
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
        return(CCN_UPCALL_RESULT_REEXPRESS);
    if (kind != CCN_UPCALL_CONTENT && kind != CCN_UPCALL_CONTENT_UNVERIFIED)
        return(CCN_UPCALL_RESULT_ERR);
    printf("Got content matching %d components:\n", info->pi->prefix_comps);
    printraw(info->content_ccnb, info->pco->offset[CCN_PCO_E]);
    return(CCN_UPCALL_RESULT_OK);
}

/* Use some static data for this simple program */
static struct ccn_closure incoming_content_action = {
    .p = &incoming_content
};

static unsigned char rawbuf[65536];
static ssize_t rawlen;

#define MINI_STORE_LIMIT 10
struct mini_store {
    struct ccn_closure me;
    struct ccn_charbuf *cob[MINI_STORE_LIMIT];
};

static struct mini_store store[10];

int
cob_matches(struct ccn_upcall_info *info, struct ccn_charbuf *cob)
{
    int ans;
    
    ans = ccn_content_matches_interest(cob->buf, cob->length, 1, NULL,
                                       info->interest_ccnb,
                                       info->pi->offset[CCN_PI_E],
                                       info->pi);
    return(ans);
}

enum ccn_upcall_res
outgoing_content(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    struct mini_store *md;
    struct ccn_charbuf *cob = NULL;
    int i;
    int res = 0;
    int which;
    
    md = selfp->data;
    which = md->me.intdata;
    if (kind == CCN_UPCALL_FINAL) {
        printf("CCN_UPCALL_FINAL for store %d\n", which);
        for (i = 0; i < MINI_STORE_LIMIT; i++)
            ccn_charbuf_destroy(&md->cob[i]);
        return(CCN_UPCALL_RESULT_OK);
    }
    printf("Store %d got interest matching %d components, kind = %d",
           which, info->matched_comps, kind);
    /* Look through our little pile of content and send one that matches */
    if (kind == CCN_UPCALL_INTEREST) {
        for (i = 0; i < MINI_STORE_LIMIT; i++) {
            cob = md->cob[i];
            if (cob_matches(info, cob)) {
                res = ccn_put(info->h, cob->buf, cob->length);
                if (res == -1) {
                    fprintf(stderr, "... error sending data\n");
                    return(CCN_UPCALL_RESULT_ERR);
                }
                else {
                    printf("... sent my content:\n");
                    printraw(cob->buf, cob->length);
                    ccn_charbuf_destroy(&md->cob[i]);
                    return(CCN_UPCALL_RESULT_INTEREST_CONSUMED);
                }
            }
        }
        printf("... no match\n");
    }
    else
        printf("\n");
    return(CCN_UPCALL_RESULT_ERR);
}


void
usage(void)
{
    fprintf(stderr, "provide names of files containing ccnb format interests and content\n");
    exit(1);
}

int
main(int argc, char **argv)
{
    int opt;
    int res;
    char *filename = NULL;
    int rep = 1;
    struct ccn *ccnH = NULL;
    struct ccn_parsed_interest interest = {0};
    int i;
    struct ccn_charbuf *c = ccn_charbuf_create();
    struct ccn_charbuf *templ = ccn_charbuf_create();
    struct ccn_indexbuf *comps = ccn_indexbuf_create();
    while ((opt = getopt(argc, argv, "h")) != -1) {
        switch (opt) {
            default:
            case 'h':
                usage();
                break;
        }
    }
    argc -= optind;
    argv += optind;
    ccnH = ccn_create();
    if (ccn_connect(ccnH, NULL) == -1) {
        perror("ccn_connect");
        exit(1);
    }
    for (i = 0; i < 10; i++) {
        store[i].me.p = &outgoing_content;
        store[i].me.data = &store[i];
        store[i].me.intdata = i;
    }
    for (i = 0; i < argc; i++) {
        filename = argv[i];
        close(0);
        res = open(filename, O_RDONLY);
        if (res != 0) {
            perror(filename);
            exit(1);
        }
        fprintf(stderr, "Reading %s ... ", filename);
        rawlen = read(0, rawbuf, sizeof(rawbuf));
        if (rawlen < 0) {
            perror("skipping");
            continue;
        }
        // XXX - Should do a skeleton check before parse
        res = ccn_parse_interest(rawbuf, rawlen, &interest, NULL);
        if (res >= 0) {
            size_t name_start = interest.offset[CCN_PI_B_Name];
            size_t name_size = interest.offset[CCN_PI_E_Name] - name_start;
            templ->length = 0;
            ccn_charbuf_append(templ, rawbuf, rawlen);
            fprintf(stderr, "Registering interest with %d name components\n", res);
            c->length = 0;
            ccn_charbuf_append(c, rawbuf + name_start, name_size);
            // XXX - res is currently ignored
            ccn_express_interest(ccnH, c, &incoming_content_action, templ);
        }
        else {
            struct ccn_parsed_ContentObject obj = {0};
            int k;
            res = ccn_parse_ContentObject(rawbuf, rawlen, &obj, comps);
            if (res >= 0) {
                fprintf(stderr, "Offering content\n");
                /* We won't listen for interests with fewer than 2 name component */
                for (k = comps->n - 1; k >= 2; k--) {
                    // XXX - there is a nicer way to do this...
                    c->length = 0;
                    ccn_charbuf_append_tt(c, CCN_DTAG_Name, CCN_DTAG);
                    ccn_charbuf_append(c, rawbuf+comps->buf[0], comps->buf[k] - comps->buf[0]);
                    ccn_charbuf_append_closer(c);
                    res = ccn_set_interest_filter(ccnH, c, &store[0].me);
                    if (res < 0) abort();
                }
                res = ccn_run(ccnH, 1000);
                /* Stop listening for these interests now */
                for (k = comps->n - 1; k >= 2; k--) {
                    c->length = 0;
                    ccn_charbuf_append_tt(c, CCN_DTAG_Name, CCN_DTAG);
                    ccn_charbuf_append(c, rawbuf+comps->buf[0], comps->buf[k] - comps->buf[0]);
                    ccn_charbuf_append_closer(c);
                    res = ccn_set_interest_filter(ccnH, c, NULL);
                    if (res < 0) abort();
                }
            }
            else {
                fprintf(stderr, "what's that?\n");
            }
        }
    }
    fprintf(stderr, "Running for 8 more seconds\n");
    res = ccn_run(ccnH, 8000);
    ccn_destroy(&ccnH);
    exit(0);
}
