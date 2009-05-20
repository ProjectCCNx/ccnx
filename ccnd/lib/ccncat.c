/*
 * ccncat.c
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-a] ccn:/a/b ...\n"
            "   Reads streams at"
            " the given ccn URIs and writes to stdout\n"
            "   -a - allow stale data\n",
            progname);
    exit(1);
}

struct excludestuff;

struct mydata {
    int allow_stale;
    struct excludestuff *excl;
};

struct excludestuff {
    struct excludestuff* next;
    unsigned char *data;
    size_t size;
};

int
count_excludestuff(struct excludestuff* p)
{
    int n;
    for (n = 0; p != NULL; p = p->next)
        n++;
    return(n);
}

void
fill_bloom(struct ccn_bloom *b, struct excludestuff* excl)
{
    struct excludestuff* p;
    for (p = excl; p != NULL; p = p->next)
        ccn_bloom_insert(b, p->data, p->size);
}

void
clear_excludes(struct mydata *md)
{
    struct excludestuff* e;
    while (md->excl != NULL) {
        e = md->excl;
        md->excl = e->next;
        free(e->data);
        free(e);
    }
}

void
note_new_exclusion(struct mydata *md, const unsigned char *ccnb,
                   size_t start, size_t stop)
{
    struct excludestuff* e;
    unsigned char *data;
    if (start < stop) {
        e = calloc(1, sizeof(*e));
        data = calloc(1, stop-start);
        memcpy(data, ccnb + start, stop - start);
        e->data = data;
        e->size = stop - start;
        e->next = md->excl;
        md->excl = e;
    }
}

struct ccn_charbuf *
make_template(struct mydata *md, struct ccn_upcall_info *info)
{
    struct ccn_charbuf *templ = ccn_charbuf_create();
    int nexcl;
    struct ccn_bloom *b = NULL;
    int i;
    unsigned char seed[4];
    ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
    ccn_charbuf_append_tt(templ, CCN_DTAG_Name, CCN_DTAG);
    ccn_charbuf_append_closer(templ); /* </Name> */
    // XXX - use pubid if possible
    ccn_charbuf_append_tt(templ, CCN_DTAG_AdditionalNameComponents, CCN_DTAG);
    ccn_charbuf_append_non_negative_integer(templ, 1);
    ccn_charbuf_append_closer(templ); /* </AdditionalNameComponents> */
    if (md->allow_stale) {
        ccn_charbuf_append_tt(templ, CCN_DTAG_AnswerOriginKind, CCN_DTAG);
        ccn_charbuf_append_non_negative_integer(templ,
                                                CCN_AOK_DEFAULT | CCN_AOK_STALE);
        ccn_charbuf_append_closer(templ); /* </AnswerOriginKind> */
    }
    nexcl = count_excludestuff(md->excl);
    if (nexcl != 0) {
        long r = lrand48();
        for (i = 0; i < 4; i++) {
            seed[i] = r;
            r <<= 8;
        }
        if (nexcl < 8) nexcl = 8;
        b = ccn_bloom_create(nexcl, seed);
        fill_bloom(b, md->excl);
        ccn_charbuf_append_tt(templ, CCN_DTAG_ExperimentalResponseFilter, CCN_DTAG);
        i = ccn_bloom_wiresize(b);
        ccn_charbuf_append_tt(templ, i, CCN_BLOB);
        ccn_bloom_store_wire(b, ccn_charbuf_reserve(templ, i), i);
        templ->length += i;
        ccn_charbuf_append_closer(templ);
    }
    ccn_charbuf_append_closer(templ); /* </Interest> */
    return(templ);
}

enum ccn_upcall_res
incoming_content(
                 struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    struct ccn_charbuf *temp = NULL;
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    const unsigned char *data = NULL;
    size_t data_size = 0;
    size_t written;
    const unsigned char *ib = NULL; /* info->interest_ccnb */
    struct ccn_indexbuf *ic = NULL;
    int res;
    struct mydata *md = selfp->data;
    
    if (kind == CCN_UPCALL_FINAL) {
        if (md != NULL) {
            clear_excludes(md);
            selfp->data = NULL;
            free(md);
            md = NULL;
        }
        return(CCN_UPCALL_RESULT_OK);
    }
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
        return(CCN_UPCALL_RESULT_REEXPRESS); // XXX - may need to reseed bloom filter
    if (kind == CCN_UPCALL_CONTENT_UNVERIFIED)
        return(CCN_UPCALL_RESULT_VERIFY);
    if (kind != CCN_UPCALL_CONTENT)
        return(CCN_UPCALL_RESULT_ERR);
    if (md == NULL)
        selfp->data = md = calloc(1, sizeof(*md));
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    ib = info->interest_ccnb;
    ic = info->interest_comps;
    res = ccn_content_get_value(ccnb, ccnb_size, info->pco, &data, &data_size);
    if (res < 0) abort();
    if (data_size > 1024 || info->pco->type != CCN_CONTENT_DATA) {
        /* For us this is spam. Need to try again, excluding this one. */
        fprintf(stderr, "*** skip spam at block %d\n", (int)selfp->intdata);
        name = ccn_charbuf_create();
        ccn_name_append_components(name, ib, ic->buf[0], ic->buf[ic->n - 1]);
        note_new_exclusion(md, ccnb,
                           info->pco->offset[CCN_PCO_B_Signature],
                           info->pco->offset[CCN_PCO_E_Signature]);
        templ = make_template(md, info);
        res = ccn_express_interest(info->h, name, -1, selfp, templ);
        if (res < 0)
            abort();
        ccn_charbuf_destroy(&templ);
        ccn_charbuf_destroy(&name);
        return(CCN_UPCALL_RESULT_OK);
    }
    
    /* OK, we will accept this block. */
    
    written = fwrite(data, data_size, 1, stdout);
    if (written != 1)
        exit(1);
    
    /* Ask for the next fragment */
    name = ccn_charbuf_create();
    ccn_name_init(name);
    if (ic->n < 2) abort();
    res = ccn_name_append_components(name, ib, ic->buf[0], ic->buf[ic->n - 2]);
    if (res < 0) abort();
    temp = ccn_charbuf_create();
    ccn_charbuf_putf(temp, "%d", ++(selfp->intdata));
    ccn_name_append(name, temp->buf, temp->length);
    ccn_charbuf_destroy(&temp);
    clear_excludes(md);
    templ = make_template(md, info);
    
    res = ccn_express_interest(info->h, name, -1, selfp, templ);
    if (res < 0) abort();
    
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&name);
    
    return(CCN_UPCALL_RESULT_OK);
}

int
main(int argc, char **argv)
{
    struct ccn *ccn = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    struct ccn_closure *incoming = NULL;
    const char *arg = NULL;
    int i;
    int res;
    char ch;
    struct mydata *mydata;
    int allow_stale = 0;
    
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
    arg = argv[optind];
    if (arg == NULL)
        usage(argv[0]);
    name = ccn_charbuf_create();
    /* Check the args first */
    for (i = optind; argv[i] != NULL; i++) {
        name->length = 0;
        res = ccn_name_from_uri(name, argv[i]);
        if (res < 0) {
            fprintf(stderr, "%s: bad ccn URI: %s\n", argv[0], argv[i]);
            exit(1);
        }
    }
    for (i = optind; (arg = argv[i]) != NULL; i++) {
        name->length = 0;
        res = ccn_name_from_uri(name, argv[i]);
        ccn = ccn_create();
        if (ccn_connect(ccn, NULL) == -1) {
            perror("Could not connect to ccnd");
            exit(1);
        }
        ccn_name_append(name, "0", 1);
        incoming = calloc(1, sizeof(*incoming));
        incoming->p = &incoming_content;
        mydata = calloc(1, sizeof(*mydata));
        mydata->allow_stale = allow_stale;
        mydata->excl = NULL;
        incoming->data = mydata;
        templ = make_template(mydata, NULL);
        
        ccn_express_interest(ccn, name, -1, incoming, templ);
        ccn_charbuf_destroy(&templ);
        /* Run a little while to see if there is anything there */
        res = ccn_run(ccn, 200);
        if (incoming->intdata == 0)
            fprintf(stderr, "%s: not found: %s\n", argv[0], arg);
        /* We got something, run until end of data or somebody kills us */
        while (res >= 0) {
            fflush(stdout);
            res = ccn_run(ccn, 200);
        }
        ccn_destroy(&ccn);
    }
    ccn_charbuf_destroy(&name);
    exit(res < 0);
}
