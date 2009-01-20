/*
 * Dumps names of everything quickly retrievable to stdout
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

/***********
<Interest>
  <Name/>
  <NameComponentCount>0</NameComponentCount>
  <OrderPreference>4</OrderPreference>
  <Scope>0</Scope>
</Interest>
**********/
struct ccn_charbuf *
local_scope_template(int allow_stale)
{
    struct ccn_charbuf *templ = ccn_charbuf_create();
    ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
    /* <Name/> */
    ccn_charbuf_append_tt(templ, CCN_DTAG_Name, CCN_DTAG);
    ccn_charbuf_append_closer(templ); /* </Name> */
    /* <NameComponentCount>0</NameComponentCount> */
    ccn_charbuf_append_tt(templ, CCN_DTAG_NameComponentCount, CCN_DTAG);
    ccn_charbuf_append_tt(templ, 1, CCN_UDATA);
    ccn_charbuf_append(templ, "0", 1);
    ccn_charbuf_append_closer(templ); /* </NameComponentCount> */
    /* <OrderPreference>4</OrderPreference> */
    ccn_charbuf_append_tt(templ, CCN_DTAG_OrderPreference, CCN_DTAG);
    ccn_charbuf_append_tt(templ, 1, CCN_UDATA);
    ccn_charbuf_append(templ, "4", 1);
    ccn_charbuf_append_closer(templ); /* </OrderPreference> */
    if (allow_stale) {
        /* <AnswerOriginKind>5</AnswerOriginKind> */
        ccn_charbuf_append_tt(templ, CCN_DTAG_AnswerOriginKind, CCN_DTAG);
        ccn_charbuf_append_tt(templ, 1, CCN_UDATA);
        ccn_charbuf_putf(templ, "%d", (int)(CCN_AOK_CS + CCN_AOK_STALE));
        ccn_charbuf_append_closer(templ); /* </AnswerOriginKind> */
    }
    /* <Scope>0</Scope> */
    ccn_charbuf_append_tt(templ, CCN_DTAG_Scope, CCN_DTAG);
    ccn_charbuf_append_tt(templ, 1, CCN_UDATA);
    ccn_charbuf_append(templ, "0", 1);
    ccn_charbuf_append_closer(templ); /* </Scope> */
    ccn_charbuf_append_closer(templ); /* </Interest> */
    return(templ);
}

static const unsigned char templ_ccnb[20] =
        "\001\322\362\000\002\212\216\060"
        "\000\002\362\216\064\000\002\322"
        "\216\060\000\000";
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
    int res;
    if (kind == CCN_UPCALL_FINAL)
        return(0);
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
        return(CCN_UPCALL_RESULT_REEXPRESS);
    if (kind != CCN_UPCALL_CONTENT)
        return(-1);
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    comps = info->content_comps;
    c = ccn_charbuf_create();
    res = ccn_uri_append(c, ccnb, ccnb_size, 1);
    if (res >= 0)
        printf("%s\n", ccn_charbuf_as_string(c));
    else
        fprintf(stderr, "*** Error: ccndumpnames line %d kind=%d res=%d\n",
            __LINE__, kind, res);
    /* Use the name of the content just received as the resumption point */
    ccn_name_init(c);
    ccn_name_append_components(c, ccnb, comps->buf[0], comps->buf[comps->n-1]);
    /* Use the full name, including digest, to ensure we move along */
    ccn_digest_ContentObject(ccnb, info->pco);
    ccn_name_append(c, info->pco->digest, info->pco->digest_bytes);
    templ = local_scope_template(selfp->intdata);
    ccn_express_interest(info->h, c, 0, selfp, templ);
    
    ccn_charbuf_destroy(&c);
    selfp->data = selfp; /* make not NULL to indicate we got something */
    return(0);
}

/* Use some static data for this simple program */
static struct ccn_closure incoming_content_action = {
    .p = &incoming_content
};

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-a]\n"
            "   Dumps names of everything quickly retrievable\n"
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
    incoming_content_action.intdata = allow_stale;
    
    ccn_name_init(c);
    ccn_express_interest(ccn, c, 0, &incoming_content_action, templ);
    for (i = 0;; i++) {
        incoming_content_action.data = NULL;
        ccn_run(ccn, 100); /* stop if we run dry for 1/10 sec */
        fflush(stdout);
        if (incoming_content_action.data == NULL)
            break;
    }
    ccn_destroy(&ccn);
    ccn_charbuf_destroy(&c);
    ccn_charbuf_destroy(&templ);
    exit(0);
}
