/*
 * Dumps everything quickly retrievable to stdout
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ccn/ccn.h>
#include <ccn/uri.h>

/***********
<Interest>
  <Name/>
  <Scope>0</Scope>
</Interest>
**********/
struct ccn_charbuf *
local_scope_template(void)
{
    struct ccn_charbuf *templ = ccn_charbuf_create();
    ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
    ccn_charbuf_append_tt(templ, CCN_DTAG_Name, CCN_DTAG);
    ccn_charbuf_append_closer(templ); /* </Name> */
    ccn_charbuf_append_tt(templ, CCN_DTAG_Scope, CCN_DTAG);
    ccn_charbuf_append_tt(templ, 1, CCN_UDATA);
    ccn_charbuf_append(templ, "0", 1);
    ccn_charbuf_append_closer(templ); /* </Scope> */
    ccn_charbuf_append_closer(templ); /* </Interest> */
    return(templ);
}

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
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    struct mydata *md = selfp->data;

    if (kind == CCN_UPCALL_FINAL)
        return(CCN_UPCALL_RESULT_OK);
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
        return(CCN_UPCALL_RESULT_REEXPRESS);
    if (kind != CCN_UPCALL_CONTENT || md == NULL)
        return(CCN_UPCALL_RESULT_ERR);
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    if (md->firstseen == NULL) {
        md->firstseen = calloc(1, ccnb_size);
        memcpy(md->firstseen, info->content_ccnb, ccnb_size);
        md->firstseensize = ccnb_size;
    }
    else if (md->firstseensize == ccnb_size &&
             0 == memcmp(md->firstseen, ccnb, ccnb_size)) {
        selfp->data = NULL;
        return(CCN_UPCALL_RESULT_ERR);
    }
    md->nseen++;
    fwrite(ccnb, ccnb_size, 1, stdout);
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
    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }
    c = ccn_charbuf_create();
    /* set scope to only address ccnd */
    templ = local_scope_template();
    if (argv[1] == NULL)
        ccn_name_init(c);
    else {
        res = ccn_name_from_uri(c, argv[1]);
        if (res < 0) {
            fprintf(stderr, "%s: bad ccn URI: %s\n", argv[0], argv[1]);
            exit(1);
        }
        if (argv[2] != NULL)
            fprintf(stderr, "%s warning: extra arguments ignored\n", argv[0]);
    }
    ccn_express_interest(ccn, c, -1, &incoming_content_action, templ);
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&c);
    for (i = 0; i < 1000; i++) {
        ccn_run(ccn, 100); /* stop if we run dry for 1/10 sec */
        fflush(stdout);
        if (incoming_content_action.data == NULL)
            break;
    }
    ccn_destroy(&ccn);
    if (incoming_content_action.data != NULL) {
        fprintf(stderr, "\nWarning: output from %s may be incomplete.\n", argv[0]);
        exit(1);
    }
    exit(0);
}
