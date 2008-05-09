/*
 * Dumps everything quickly retrievable to stdout
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ccn/ccn.h>

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
    if (kind == CCN_UPCALL_FINAL)
        return(0);
    if (kind != CCN_UPCALL_CONTENT)
        return(-1);
    fwrite(ccnb, ccnb_size, 1, stdout);
    selfp->data = selfp; /* make not NULL to indicate we got something */
    return(0);
}

/* Use some static data for this simple program */
static struct ccn_closure incoming_content_action = {
    .p = &incoming_content
};

int
main(int argc, char **argv)
{
    struct ccn *ccn = NULL;
    struct ccn_charbuf *c = NULL;
    struct ccn_charbuf *templ = NULL;
    int i;
    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }
    c = ccn_charbuf_create();
    templ = ccn_charbuf_create();
    /* set scope to only address ccnd */
    ccn_charbuf_append(templ, "\001\322\362\000\002\322\216\060\000\000", 10);
    ccn_name_init(c);
    ccn_express_interest(ccn, c, -1, &incoming_content_action, templ);
    for (i = 0; i < 100; i++) {
        incoming_content_action.data = NULL;
        ccn_run(ccn, 100); /* stop if we run dry for 1/10 sec */
        fflush(stdout);
        if (incoming_content_action.data == NULL)
            break;
    }
    ccn_destroy(&ccn);
    exit(0);
}
