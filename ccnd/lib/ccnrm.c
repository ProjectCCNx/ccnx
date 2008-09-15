/*
 * Mark as stale any local items a matching given prefixes
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ccn/ccn.h>
#include <ccn/uri.h>

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
    ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
    ccn_charbuf_append_tt(templ, CCN_DTAG_Name, CCN_DTAG);
    ccn_charbuf_append_closer(templ); /* </Name> */
    ccn_charbuf_append_tt(templ, CCN_DTAG_AnswerOriginKind, CCN_DTAG);
    ccn_charbuf_append_tt(templ, 2, CCN_UDATA);
    ccn_charbuf_putf(templ, "%2d", (CCN_AOK_EXPIRE | CCN_AOK_DEFAULT));
    ccn_charbuf_append_closer(templ); /* </AnswerOriginKind> */
    ccn_charbuf_append_tt(templ, CCN_DTAG_Scope, CCN_DTAG);
    ccn_charbuf_append_tt(templ, 1, CCN_UDATA);
    ccn_charbuf_append(templ, "0", 1);
    ccn_charbuf_append_closer(templ); /* </Scope> */
    ccn_charbuf_append_closer(templ); /* </Interest> */
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
    struct mydata *md = selfp->data;

    if (kind == CCN_UPCALL_FINAL)
        return(CCN_UPCALL_RESULT_OK);
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
        return(CCN_UPCALL_RESULT_REEXPRESS);
    if (kind != CCN_UPCALL_CONTENT || md == NULL)
        return(CCN_UPCALL_RESULT_ERR);
    md->nseen++;
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
    /* set scope to only address ccnd, expire anything we get */
    templ = local_scope_rm_template();
    for (i = 1; argv[i] != NULL; i++) {
        c->length = 0;
        res = ccn_name_from_uri(c, argv[i]);
        if (res < 0) {
            fprintf(stderr, "%s: bad ccn URI: %s\n", argv[0], argv[i]);
            exit(1);
        }
        ccn_express_interest(ccn, c, -1, &incoming_content_action, templ);
    }
    if (i == 1) {
        fprintf(stderr, "%s: expecting ccn URIs to mark stale\n", argv[0]);
        exit(1);
    }
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&c);
    for (i = 0;; i++) {
        res = mydata.nseen;
        ccn_run(ccn, 100); /* stop if we run dry for 1/10 sec */
        if (res == mydata.nseen)
            break;
    }
    ccn_destroy(&ccn);
    fprintf(stderr, "marked stale: %d\n", mydata.nseen);
    exit(0);
}
