/*
 * Dumps timestamps of everything quickly retrievable
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

int
incoming_content(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn *h,
    const unsigned char *ccnb,    /* binary-format Interest or ContentObject */
    size_t ccnb_size,             /* size in bytes */
    struct ccn_indexbuf *comps,   /* component boundaries within ccnb */
    int matched_comps             /* number of components in registration */
)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = NULL;
    int nest = 0;
    struct mydata *md = selfp->data;
    if (kind == CCN_UPCALL_FINAL) {
        // XXX - cleanup
        return(0);
    }
    if (kind != CCN_UPCALL_CONTENT || md == NULL)
        return(-1);
    if (md->firstseen == NULL) {
        md->firstseen = calloc(1, ccnb_size);
        memcpy(md->firstseen, ccnb, ccnb_size);
        md->firstseensize = ccnb_size;
    }
    else if (md->firstseensize == ccnb_size && 0 == memcmp(md->firstseen, ccnb, ccnb_size)) {
        selfp->data == NULL;
        return(-1);
    }
    md->nseen++;
    d = ccn_buf_decoder_start(&decoder, ccnb, ccnb_size);
    if (ccn_buf_match_dtag(d, CCN_DTAG_ContentObject)) {
        nest = d->decoder.nest;
        ccn_buf_advance(d);
        while (d->decoder.state >= 0 && d->decoder.nest >= nest) {
            if (0 && CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_DTAG)
                fprintf(stderr, "%d %d\n", (int)d->decoder.token_index, (int)d->decoder.numval);
            if (ccn_buf_match_dtag(d, CCN_DTAG_Timestamp)) {
                ccn_buf_advance(d);
                if (CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_UDATA) {
                    fwrite(ccnb + d->decoder.index, 1, d->decoder.numval, stdout);
                    printf("\n");
                    return(0);
                }
            }
            ccn_buf_advance(d);
        }
    }
    return(0);
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
    ccn_express_interest(ccn, c, -1, &incoming_content_action, templ);
    for (i = 0; i < 100; i++) {
        seen = mydata.nseen;
        ccn_run(ccn, w <= 0 ? 100 : w * 1000);
        if (seen == mydata.nseen)
            break;
    }
    ccn_destroy(&ccn);
    exit(0);
}
