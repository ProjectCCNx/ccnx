/*
 * Attempts pull everything in a branch of the ccn hierarchy.
 * (actually, will miss some stuff)
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
    unsigned warn;
    int n_excl;
    struct ccn_charbuf **excl; /* Array of n_excl items */
};

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
    if (kind == CCN_UPCALL_FINAL) {
        for (i = 0; i < data->n_excl; i++)
            ccn_charbuf_destroy(&(data->excl[i]));
        if (data->excl != NULL)
            free(data->excl);
        free(data);
        free(selfp);
        return(0);
    }
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
        return(0);
    if (kind != CCN_UPCALL_CONTENT) abort();

    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    comps = info->content_comps;
    matched_comps = info->pi->prefix_comps;
    c = ccn_charbuf_create();
    comp = ccn_charbuf_create();
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
    data->excl = realloc(data->excl, (data->n_excl + 1) * sizeof(data->excl[0]));
    data->excl[data->n_excl++] = comp;
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
    ccn_charbuf_append_closer(templ); /* </Interest> */
    if (templ->length > data->warn) {
        fprintf(stderr, "*** Interest packet is %d bytes\n", (int)templ->length);
        data->warn = data->warn * 8 / 5;
    }
    ccn_express_interest(info->h, c, -1, selfp, templ);
    
    /* Explore the next level, if there is one. */
    if (matched_comps + 2 < comps->n) {
        struct upcalldata *newdat = NULL;
        struct ccn_closure *cl;
        newdat = calloc(1, sizeof(*newdat));
        newdat->magic = 856372;
        newdat->warn = 1492;
        newdat->counter = data->counter;
        cl = calloc(1, sizeof(*cl));
        cl->p = &incoming_content;
        cl->data = newdat;
        ccn_name_init(c);
        c->length--;
        ccn_charbuf_append(c, ccnb + comps->buf[0], comps->buf[matched_comps + 1] - comps->buf[0]);
        ccn_charbuf_append_closer(c);
        ccn_express_interest(info->h, c, -1, cl, NULL);
    }
    else {
        res = ccn_uri_append(uri, info->content_ccnb, info->pco->offset[CCN_PCO_E], 1);
        if (res < 0)
            fprintf(stderr, "*** Error: ccnslurp line %d res=%d\n", __LINE__, res);
        else
            printf("%s\n", ccn_charbuf_as_string(uri));
    }
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&c);
    ccn_charbuf_destroy(&uri);
    return(0);
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
    long n;
    int res;
    long counter = 0;
    struct ccn_closure *cl = NULL;
    
    if (argv[1] == NULL || argv[2] != NULL)
        usage(argv[0]);

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
    cl = calloc(1, sizeof(*cl));
    cl->p = &incoming_content;
    cl->data = data;
    ccn_express_interest(ccn, c, -1, cl, NULL);
    cl = NULL;
    data = NULL;
    ccn_charbuf_destroy(&c);
    for (i = 0; i < 1000; i++) {
        n = counter;
        ccn_run(ccn, 1000); /* stop if we run dry for 1 sec */
        fflush(stdout);
        if (counter == n)
            break;
    }
    ccn_destroy(&ccn);
    exit(0);
}
