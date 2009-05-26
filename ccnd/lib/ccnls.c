/*
 * Attempts to list name components available at the next level of the hierarchy
 */
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

struct upcalldata {
    int magic; /* 856372 */
    long *counter;
    int n_excl;
    unsigned warn;
    unsigned option;
    struct ccn_charbuf **excl; /* Array of n_excl items */
};

#define MUST_VERIFY 0x01

static int /* for qsort */
namecompare(const void *a, const void *b)
{
    const struct ccn_charbuf *aa = *(const struct ccn_charbuf **)a;
    const struct ccn_charbuf *bb = *(const struct ccn_charbuf **)b;
    int ans = ccn_compare_names(aa->buf, aa->length, bb->buf, bb->length);
    if (ans == 0)
        fprintf(stderr, "wassat? %d\n", __LINE__);
    return (ans);
}

enum ccn_upcall_res
incoming_content(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    struct ccn_charbuf *c = NULL;
    struct ccn_charbuf *comp = NULL;
    struct ccn_charbuf *uri = NULL;
    struct ccn_charbuf *templ = NULL;
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    struct ccn_indexbuf *comps = NULL;
    int matched_comps = 0;
    int res;
    int i;
    struct upcalldata *data = selfp->data;
    
    if (data->magic != 856372) abort();
    if (kind == CCN_UPCALL_FINAL)
        return(CCN_UPCALL_RESULT_OK);
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
        return(CCN_UPCALL_RESULT_REEXPRESS);
    if (kind == CCN_UPCALL_CONTENT_UNVERIFIED) {
        if ((data->option & MUST_VERIFY) != 0)
        return(CCN_UPCALL_RESULT_VERIFY);
        }
    else if (kind != CCN_UPCALL_CONTENT) abort();
    
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    comps = info->content_comps;
    matched_comps = info->pi->prefix_comps;
    c = ccn_charbuf_create();
    uri = ccn_charbuf_create();
    templ = ccn_charbuf_create();
        
    if (matched_comps + 1 > comps->n) {
        ccn_uri_append(c, ccnb, ccnb_size, 1);
        fprintf(stderr, "How did this happen?  %s\n", ccn_charbuf_as_string(uri));
        exit(1);
    }
    data->counter[0]++;
    /* Recover the same prefix as before */
    ccn_name_init(c);
    ccn_name_append_components(c, ccnb, comps->buf[0], comps->buf[matched_comps]);
    
    comp = ccn_charbuf_create();
    ccn_name_init(comp);
    if (matched_comps + 1 == comps->n) {
        /* Reconstruct the implicit content digest component */
        ccn_digest_ContentObject(ccnb, info->pco);
        ccn_name_append(comp, info->pco->digest, info->pco->digest_bytes);
    }
    else {
        ccn_name_append_components(comp, ccnb,
                                   comps->buf[matched_comps],
                                   comps->buf[matched_comps + 1]);
    }
    res = ccn_uri_append(uri, comp->buf, comp->length, 0);
    if (res < 0 || uri->length < 1)
        fprintf(stderr, "*** Error: ccnls line %d res=%d\n", __LINE__, res);
    else
        printf("%s%s\n", ccn_charbuf_as_string(uri) + 1,
               kind == CCN_UPCALL_CONTENT ? " [verified]" : " [unverified]");
    data->excl = realloc(data->excl, (data->n_excl + 1) * sizeof(data->excl[0]));
    data->excl[data->n_excl++] = comp;
    comp = NULL;
    qsort(data->excl, data->n_excl, sizeof(data->excl[0]), &namecompare);
            
    ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
    ccn_charbuf_append(templ, c->buf, c->length); /* Name */
    ccn_charbuf_append_tt(templ, CCN_DTAG_Exclude, CCN_DTAG);
    for (i = 0; i < data->n_excl; i++) {
        comp = data->excl[i];
        if (comp->length < 4) abort();
        ccn_charbuf_append(templ, comp->buf + 1, comp->length - 2);
    }
    comp = NULL;
    ccn_charbuf_append_closer(templ); /* </Exclude> */
    ccn_charbuf_append_tt(templ, CCN_DTAG_AnswerOriginKind, CCN_DTAG);
    ccn_charbuf_append_tt(templ, 1, CCN_UDATA);
    ccn_charbuf_append(templ, "1", 1);
    ccn_charbuf_append_closer(templ); /* </AnswerOriginKind> */
    ccn_charbuf_append_closer(templ); /* </Interest> */
    if (templ->length > data->warn) {
        fprintf(stderr, "*** Interest packet is %d bytes\n", (int)templ->length);
        data->warn = data->warn * 8 / 5;
    }
    ccn_express_interest(info->h, c, -1, selfp, templ);
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&c);
    ccn_charbuf_destroy(&uri);
    return(CCN_UPCALL_RESULT_OK);
}

void
usage(const char *prog)
{
    errno = EINVAL;
    perror(prog);
    exit(1);
}

int
main(int argc, char **argv)
{
    struct ccn *ccn = NULL;
    struct ccn_charbuf *c = NULL;
    struct upcalldata *data = NULL;
    int i;
    int n;
    int res;
    long counter = 0;
    struct ccn_closure *cl = NULL;
    int timeout_ms = 500;
    const char *env_timeout = getenv("CCN_LINGER");
    const char *env_verify = getenv("CCN_VERIFY");

    if (argv[1] == NULL || argv[2] != NULL)
        usage(argv[0]);

    if (env_timeout != NULL && (i = atoi(env_timeout)) > 0)
        timeout_ms = i * 1000;

    c = ccn_charbuf_create();
    res = ccn_name_from_uri(c, argv[1]);
    if (res < 0)
        usage(argv[0]);
        
    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }
    
    data = calloc(1, sizeof(*data));
    data->magic = 856372;
    data->warn = 1492;
    data->counter = &counter;
    data->option = 0;
    if (env_verify && *env_verify)
        data->option |= MUST_VERIFY;
    cl = calloc(1, sizeof(*cl));
    cl->p = &incoming_content;
    cl->data = data;
    ccn_express_interest(ccn, c, -1, cl, NULL);
    cl = NULL;
    data = NULL;
    for (i = 0;; i++) {
        n = counter;
        ccn_run(ccn, timeout_ms); /* stop if we run dry for 1/2 sec */
        fflush(stdout);
        if (counter == n)
            break;
    }
    ccn_destroy(&ccn);
    ccn_charbuf_destroy(&c);
    exit(0);
}
