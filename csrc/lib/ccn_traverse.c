/**
 * @file ccn_traverse.c
 * @brief Support for traversing a branch of the ccn name hierarchy.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009, 2013 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>


/************ Candidate API  ******/

/**
 * Private record of the state of traversal
 */
struct ccn_traversal {
    int magic; /* 68955871 */
    long *counter;
    unsigned warn;
    int flags;
    int n_excl;
    struct ccn_charbuf **excl; /* Array of n_excl items */
    
};

#define EXCLUDE_LOW 1
#define EXCLUDE_HIGH 2
#define MUST_VERIFY 4
#define LOCAL_SCOPE 8
#define ALLOW_STALE 0x10

/* Prototypes */
static int namecompare(const void *a, const void *b);
static struct ccn_traversal *get_my_data(struct ccn_closure *selfp);
static void append_Any_filter(struct ccn_charbuf *c);
static int express_my_interest(struct ccn *h,
                               struct ccn_closure *selfp,
                               struct ccn_charbuf *name);
static struct ccn_closure *split_my_excludes(struct ccn_closure *selfp);
static enum ccn_upcall_res incoming_content(struct ccn_closure *selfp,
                                            enum ccn_upcall_kind kind,
                                            struct ccn_upcall_info *);
static struct ccn_charbuf *ccn_charbuf_duplicate(struct ccn_charbuf *);
static void answer_passive(struct ccn_charbuf *templ, int allow_stale);
static void local_scope(struct ccn_charbuf *templ);

/**
 * Comparison operator for sorting the excl list with qsort.
 * For convenience, the items in the excl array are
 * charbufs containing ccnb-encoded Names of one component each.
 * (This is not the most efficient representation.)
 */
static int /* for qsort */
namecompare(const void *a, const void *b)
{
    const struct ccn_charbuf *aa = *(const struct ccn_charbuf **)a;
    const struct ccn_charbuf *bb = *(const struct ccn_charbuf **)b;
    int ans = ccn_compare_names(aa->buf, aa->length, bb->buf, bb->length);
    if (ans == 0)
        abort();
    return (ans);
}

static struct ccn_traversal *get_my_data(struct ccn_closure *selfp)
{
    struct ccn_traversal *data = selfp->data;
    if (data->magic != 68955871) abort();
    return(data);
}

/*
 * This upcall gets called for each piece of incoming content that
 * matches one of our interests.  We need to issue a new interest that
 * excludes another component at the current level, and perhaps also
 * and interest to start exploring the next level.  Thus if the matched
 * interest is
 *   /a/b/c exclude {d,e,f,i,j,k}
 * and we get
 *   /a/b/c/g/h
 * we would issue a new interest
 *   /a/b/c exclude {d,e,f,g,i,j,k}
 * to continue exploring the current level, plus a simple interest
 *   /a/b/c/g
 * to start exploring the next level as well.
 *
 * This does end up fetching each piece of content multiple times, once for
 * each level in the name. The repeated requests will be answered from the local
 * content store, though, and so should not generate extra network traffic.
 * There is a lot of unanswerable interest generated, though.  
 *
 * To prevent the interests from becoming too huge, we may need to split them.
 * Thus if the first new interest above were deemed too large, we could instead
 * issue the two interests
 *   /a/b/c exclude {d,e,f,g,*}
 *   /a/b/c exclude {*,g,i,j,k}
 * where * stands for a Bloom filter that excludes anything.  Note the
 * repetition of g to ensure that these two interests cover disjoint portions
 * of the hierarchy. We need to keep track of the endpoint conditions
 * as well as the excluded set in our upcall data.
 * When a split happens, we need a new closure to track it, as we do when
 * we start exploring a new level.
 */
static enum ccn_upcall_res
incoming_content(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    struct ccn_charbuf *c = NULL;
    struct ccn_charbuf *comp = NULL;
    struct ccn_charbuf *uri = NULL;
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    struct ccn_indexbuf *comps = NULL;
    int matched_comps = 0;
    int res;
    int i;
    struct ccn_traversal *data = get_my_data(selfp);
    
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
    if (kind == CCN_UPCALL_CONTENT_BAD)
        return(0);
    if (kind == CCN_UPCALL_CONTENT_UNVERIFIED) {
        if ((data->flags & MUST_VERIFY) != 0)
            return(CCN_UPCALL_RESULT_VERIFY);
    }
    if (kind != CCN_UPCALL_CONTENT && kind != CCN_UPCALL_CONTENT_UNVERIFIED) abort();

    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    comps = info->content_comps;
    matched_comps = info->pi->prefix_comps;
    c = ccn_charbuf_create();
    uri = ccn_charbuf_create();
        
    if (matched_comps + 1 > comps->n) {
        ccn_uri_append(c, ccnb, ccnb_size, 1);
        fprintf(stderr, "How did this happen?  %s\n", ccn_charbuf_as_string(uri));
        exit(1);
    }
    
    data->counter[0]++; /* Tell main that something new came in */

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
    comp = NULL;
    qsort(data->excl, data->n_excl, sizeof(data->excl[0]), &namecompare);
    res = express_my_interest(info->h, selfp, c);
    if (res == -1) {
        struct ccn_closure *high = split_my_excludes(selfp);
        if (high == NULL) abort();
        express_my_interest(info->h, selfp, c);
        express_my_interest(info->h, high, c);
    }
    /* Explore the next level, if there is one. */
    if (matched_comps + 2 < comps->n) {
        struct ccn_traversal *newdat = NULL;
        struct ccn_closure *cl;
        newdat = calloc(1, sizeof(*newdat));
        newdat->magic = 68955871;
        newdat->warn = 1492;
        newdat->counter = data->counter;
        newdat->flags = data->flags & ~(EXCLUDE_LOW | EXCLUDE_HIGH);
        newdat->n_excl = 0;
        newdat->excl = NULL;
        cl = calloc(1, sizeof(*cl));
        cl->p = &incoming_content;
        cl->data = newdat;
        ccn_name_init(c);
        ccn_name_append_components(c, ccnb,
                                   comps->buf[0],
                                   comps->buf[matched_comps + 1]);
        express_my_interest(info->h, cl, c);
    }
    else {
        res = ccn_uri_append(uri, info->content_ccnb, info->pco->offset[CCN_PCO_E], 1);
        if (res < 0)
            fprintf(stderr, "*** Error: ccn_traverse line %d res=%d\n", __LINE__, res);
        else
            printf("%s\n", ccn_charbuf_as_string(uri));
    }
    ccn_charbuf_destroy(&c);
    ccn_charbuf_destroy(&uri);
    return(0);
}

/*
 * Construct and send a new interest that uses the exclusion list.
 * Return -1 if not sent because of packet size, 0 for success.
 */
static int
express_my_interest(struct ccn *h,
                    struct ccn_closure *selfp,
                    struct ccn_charbuf *name)
{
    int ans;
    struct ccn_charbuf *templ = NULL;
    int i;
    struct ccn_traversal *data = get_my_data(selfp);

    templ = ccn_charbuf_create();
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccnb_element_begin(templ, CCN_DTAG_Name);
    ccnb_element_end(templ); /* </Name> */
    if (data->n_excl != 0) {
        ccnb_element_begin(templ, CCN_DTAG_Exclude);
        if ((data->flags & EXCLUDE_LOW) != 0)
            append_Any_filter(templ);
        for (i = 0; i < data->n_excl; i++) {
            struct ccn_charbuf *comp = data->excl[i];
            if (comp->length < 4) abort();
            ccn_charbuf_append(templ, comp->buf + 1, comp->length - 2);
        }
        if ((data->flags & EXCLUDE_HIGH) != 0)
            append_Any_filter(templ);
        ccnb_element_end(templ); /* </Exclude> */
    }
    answer_passive(templ, (data->flags & ALLOW_STALE) != 0);
    if ((data->flags & LOCAL_SCOPE) != 0)
        local_scope(templ);
    ccnb_element_end(templ); /* </Interest> */
    if (templ->length + name->length > data->warn + 2) {
        fprintf(stderr, "*** Interest packet is %d bytes\n", (int)templ->length);
        data->warn = data->warn * 8 / 5;
    }
    if (templ->length + name->length > 1450 && data->n_excl > 3)
        ans = -1;
    else {
        ccn_express_interest(h, name, selfp, templ);
        ans = 0;
    }
    ccn_charbuf_destroy(&templ);
    return(ans);
}

/*
 * Build a new closure to handle the high half of the excludes, and modify the
 * old closure to handle the low half.
 */
static struct ccn_closure *
split_my_excludes(struct ccn_closure *selfp)
{
    int i;
    int m;
    struct ccn_traversal *newdat = NULL;
    struct ccn_closure *cl;
    struct ccn_traversal *data = get_my_data(selfp);
    
    if (data->n_excl < 3)
        return NULL;
    m = data->n_excl / 2;
    newdat = calloc(1, sizeof(*newdat));
    newdat->magic = 68955871;
    newdat->warn = 1492;
    newdat->counter = data->counter;
    newdat->n_excl = data->n_excl - m;
    newdat->excl = calloc(newdat->n_excl, sizeof(newdat->excl[0]));
    if (newdat->excl == NULL) {
        free(newdat);
        return(NULL);
    }
    newdat->excl[0] = ccn_charbuf_duplicate(data->excl[m]);
    newdat->flags = data->flags | EXCLUDE_LOW;
    for (i = 1; i < newdat->n_excl; i++) {
        newdat->excl[i] = data->excl[m + i];
        data->excl[m + i] = NULL;
    }
    data->n_excl = m + 1;
    data->flags |= EXCLUDE_HIGH;
    cl = calloc(1, sizeof(*cl));
    cl->p = &incoming_content;
    cl->data = newdat;
    return(cl);
}

/**
 * Append an Any filter, useful for excluding
 * everything between two 'fenceposts' in an Exclude construct.
 */
static void
append_Any_filter(struct ccn_charbuf *c)
{
    ccnb_element_begin(c, CCN_DTAG_Any);
    ccnb_element_end(c);
}

static struct ccn_charbuf *
ccn_charbuf_duplicate(struct ccn_charbuf *c)
{
    struct ccn_charbuf *ans = ccn_charbuf_create();
    ccn_charbuf_append(ans, c->buf, c->length);
    return(ans);
}

/*
 * Append AnswerOriginKind element to partially constructed Interest,
 * requesting to not generate new content.
 */
static void
answer_passive(struct ccn_charbuf *templ, int allow_stale)
{
    int aok = CCN_AOK_CS;
    if (allow_stale)
        aok |= CCN_AOK_STALE;
    ccnb_tagged_putf(templ, CCN_DTAG_AnswerOriginKind, "%d", aok);
}

/*
 * Append Scope=0 to partially constructed Interest, meaning
 * to address only the local ccnd.
 */
static void
local_scope(struct ccn_charbuf *templ)
{
    ccnb_append_tagged_udata(templ, CCN_DTAG_Scope, "0", 1);
}

/**
 * Temporary driver - exits when done!
 */

void
ccn_dump_names(struct ccn *h, struct ccn_charbuf *name_prefix, int local_scope, int allow_stale)
{
    long *counter;
    int i;
    long n;
    int res;
    struct ccn_traversal *data = NULL;
    struct ccn_closure *cl = NULL;
    
    counter = calloc(1, sizeof(*counter));
    data = calloc(1, sizeof(*data));
    data->magic = 68955871;
    data->warn = 1492;
    data->flags = 0;
    data->counter = counter;
    if (local_scope)
        data->flags |= LOCAL_SCOPE;
    if (allow_stale)
        data->flags |= ALLOW_STALE;
    
    cl = calloc(1, sizeof(*cl));
    cl->p = &incoming_content;
    cl->data = data;
    
    express_my_interest(h, cl, name_prefix);
    cl = NULL;
    data = NULL;
    for (i = 0;; i++) {
        n = *counter;
        res = ccn_run(h, 1000); /* stop if we run dry for 1 sec */
        fflush(stdout);
        if (*counter == n || res < 0)
            break;
    }
    exit(0);
}
