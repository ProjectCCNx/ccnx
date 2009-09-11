/**
 * @file ccndumptimestamps.c
 * Dumps timestamps of everything quickly retrievable
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

static
struct mydata {
    unsigned char *firstseen;
    size_t firstseensize;
    int nseen;
} mydata = {0};

enum ccn_upcall_res
incoming_content(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = NULL;
    int nest = 0;
    struct mydata *md = selfp->data;
    size_t ccnb_size = 0;
    size_t written;
    if (kind == CCN_UPCALL_FINAL) {
        // XXX - cleanup
        return(0);
    }
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT) {
        return(0); /* don't re-express */
    }
    if ((kind != CCN_UPCALL_CONTENT && kind != CCN_UPCALL_CONTENT_UNVERIFIED) || md == NULL)
        return(-1);
    ccnb_size = info->pco->offset[CCN_PCO_E];
    if (md->firstseen == NULL) {
        md->firstseen = calloc(1, ccnb_size);
        memcpy(md->firstseen, info->content_ccnb, ccnb_size);
        md->firstseensize = ccnb_size;
    }
    else if (md->firstseensize == ccnb_size && 0 == memcmp(md->firstseen, info->content_ccnb, ccnb_size)) {
        selfp->data = NULL;
        return(-1);
    }
    md->nseen++;
    d = ccn_buf_decoder_start(&decoder, info->content_ccnb, ccnb_size);
    if (ccn_buf_match_dtag(d, CCN_DTAG_ContentObject)) {
        nest = d->decoder.nest;
        ccn_buf_advance(d);
        while (d->decoder.state >= 0 && d->decoder.nest >= nest) {
            if (0 && CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_DTAG)
                fprintf(stderr, "%d %d\n", (int)d->decoder.token_index, (int)d->decoder.numval);
            if (ccn_buf_match_dtag(d, CCN_DTAG_Timestamp)) {
                ccn_buf_advance(d);
                if (CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_UDATA) {
                    written = fwrite(info->content_ccnb + d->decoder.index, 1, d->decoder.numval, stdout);
                    if (written != d->decoder.numval) {
                        fprintf(stderr, "*** error writing stdout\n");
                        exit(1);
                    }
                    printf("\n");
                    break;
                }
                if (CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_BLOB) {
                    double dt = 0.0;
                    long jt;
                    const unsigned char *p = info->content_ccnb + d->decoder.index;
                    int n = d->decoder.numval;
                    int i;
                    struct ccn_charbuf *tbuf = ccn_charbuf_create();
                     
                    for (i = 0; i < n; i++)
                        dt = dt * 256.0 + (double)(p[i]);
                    dt /= 4096.0;
                    jt = dt; /* truncates */
                    ccn_charbuf_append_datetime(tbuf, jt, (dt-(double)jt) * 1000000000.0);
                    written = fwrite(tbuf->buf, 1, tbuf->length, stdout);
                    if (written != tbuf->length) {
                        fprintf(stderr, "*** error writing stdout\n");
                        exit(1);
                    }
                    printf("\n");
                    ccn_charbuf_destroy(&tbuf);
                    break;
                }
            }
            ccn_buf_advance(d);
        }
    }
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
    long w = 0;
    int i;
    int ch;
    int seen = 0;
    while ((ch = getopt(argc, argv, "hw:")) != -1) {
        switch (ch) {
            case 'w':
                w = atol(optarg);
                break;
            default:
            case 'h':
                fprintf(stderr, "usage: %s [ -h ] [ -w sec ] \n", argv[0]);
                exit(1);
        }
    }
    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }
    c = ccn_charbuf_create();
    if (w <= 0) {
        templ = ccn_charbuf_create();
        /* set scope to only address ccnd */
        ccn_charbuf_append(templ, "\001\322\362\000\002\322\216\060\000\000", 10);
    }
    ccn_name_init(c);
    ccn_express_interest(ccn, c, &incoming_content_action, templ);
    for (i = 0; i < 100; i++) {
        seen = mydata.nseen;
        ccn_run(ccn, w <= 0 ? 100 : w * 1000);
        if (seen == mydata.nseen)
            break;
    }
    ccn_destroy(&ccn);
    exit(0);
}
