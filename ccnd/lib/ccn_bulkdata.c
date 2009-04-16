/*
 * ccn_bulkdata.c
 * 
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 *
 * Support for transport of bulk data
 *
 * $Id$
 */

#include <assert.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <ccn/bloom.h>
#include <ccn/ccn.h>

/*
 * Encode the number in decimal ascii
 */
void
ccn_decimal_seqfunc(uintmax_t x, void *param, struct ccn_charbuf *resultbuf)
{
    (void)param; /* unused */
    assert(resultbuf->length == 0);
    ccn_charbuf_putf(resultbuf, "%ju", x);
}

/*
 * Encode the number in big-endian binary, using one more than the
 * minimum number of bytes (that is, the first byte is always zero).
 */
void
ccn_binary_seqfunc(uintmax_t x, void *param, struct ccn_charbuf *resultbuf)
{
    uintmax_t m;
    int n;
    unsigned char *b;
    (void)param; /* unused */
    for (n = 0, m = 0; x < m; n++)
        m = (m << 8) | 0xff;
    b = ccn_charbuf_reserve(resultbuf, n + 1);
    resultbuf->length = n + 1;
    for (; n >= 0; n--, x >>= 8)
        b[n] = x & 0xff;
}

/*
 * Our private record of the state of the bulk data reception
 */
struct bulkdata {
    ccn_seqfunc *seqfunc;           /* the sequence number scheme */
    void *seqfunc_param;            /* parameters thereto, if needed */
    struct pending *first;          /* start of list of pending items */
    struct ccn_closure *client;     /* client-supplied upcall for delivery */
    uintmax_t next_expected;        /* smallest undelivered sequence number */
    struct ccn_charbuf *name_prefix;
    int prefix_comps;
    /* pubid, etc? */
};

struct pending {
    struct pending *prev;           /* links for doubly-linked list */
    struct pending *next;
    struct bulkdata *parent;
    uintmax_t x;                    /* sequence number for this item */
    struct ccn_closure closure;     /* our closure for getting matching data */
    unsigned char *content_ccnb;    /* the content that has arrived */
    size_t content_size;
};

static enum ccn_upcall_res deliver_content(struct ccn *h, struct bulkdata *b);
static void express_bulkdata_interest(struct ccn *h, struct pending *b);
// XXX - missing a way to create a struct bulkdata *
// XXX - missing code to create new pendings


static enum ccn_upcall_res
imcoming_bulkdata(struct ccn_closure *selfp,
                  enum ccn_upcall_kind kind,
                  struct ccn_upcall_info *info)
{
    struct bulkdata *b;
    struct pending *p = selfp->data;
    enum ccn_upcall_res res = CCN_UPCALL_RESULT_ERR;

    assert(selfp == &p->closure);
    b = p->parent;
    
    switch (kind) {
        case CCN_UPCALL_FINAL:
            p->prev->next = p->next->prev;
            p->next->prev = p->prev->next;
            if (b != NULL && p == b->first)
                b->first = (p == p->next) ? NULL : p->next;
            if (p->content_ccnb != NULL)
                free(p->content_ccnb);
            free(p);
            return(CCN_UPCALL_RESULT_OK);
	case CCN_UPCALL_CONTENT:
    	case CCN_UPCALL_CONTENT_UNVERIFIED:
	case CCN_UPCALL_CONTENT_BAD:
            /* XXX - should we be returning bad (signature failed) content */
            break;
        case CCN_UPCALL_INTEREST_TIMED_OUT:
            /* XXX - may want to give client a chance to decide */ 
            return(CCN_UPCALL_RESULT_REEXPRESS);
        default:
            return(CCN_UPCALL_RESULT_ERR);
    }
    /* XXX - check to see if seq comp matches, if not we have a hole to fill */
    
    if (p->content_ccnb == NULL) {
    if (p->x == b->next_expected) {
        /* Good, we have in-order data to deliver to the caller */
        res = (*b->client->p)(b->client, kind, info);
        if (res == CCN_UPCALL_RESULT_OK) {
            b->next_expected += 1;
            b->first = (p == p->next) ? NULL : p->next;
            p->prev->next = p->next->prev;
            p->next->prev = p->prev->next;
            p->next = p->prev = p;
            p->parent = NULL;
        }
        // else ...
    }
    else if (p->content_ccnb == NULL) {
        /* Out-of-order data, save it for later */
        size_t size = info->pco->offset[CCN_PCO_E];
        selfp->refcount++; /* don't call FINAL just yet */
        p->content_ccnb = malloc(size);
        memcpy(p->content_ccnb, info->content_ccnb, size);
        p->content_size = size;
    }
    }
    while (b->first != NULL && b->first->x == b->next_expected &&
           b->first->content_ccnb != NULL) {
        res = deliver_content(info->h, b);
        if (res != CCN_UPCALL_RESULT_OK)
            break;
    }
    if (b->first == NULL) {
        // XXX 
        return(CCN_UPCALL_RESULT_OK);
    }
    for (p = b->first; p->x >= b->next_expected; p = p->next) {
        // XXX - this is not really right ...
        if (p->content_ccnb == NULL)
            express_bulkdata_interest(info->h, p);
    }
    return(CCN_UPCALL_RESULT_OK);
}

static void
express_bulkdata_interest(struct ccn *h, struct pending *p)
{
    int res;
    struct bulkdata *b = NULL;
    int prefix_comps = -1;
    int addl_comps = -1;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    struct ccn_charbuf *seq = NULL;
    
    b = p->parent;
    if (b == NULL)
        return;
    name = ccn_charbuf_create();
    templ = ccn_charbuf_create();
    seq = ccn_charbuf_create();

    ccn_charbuf_append(name, b->name_prefix->buf, b->name_prefix->length);
    
    seq->length = 0;
    (*b->seqfunc)(p->x, b->seqfunc_param, seq);
    ccn_name_append(name, seq->buf, seq->length);
    prefix_comps = -1;
    addl_comps = 1;
    
    ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);

    ccn_charbuf_append_tt(templ, CCN_DTAG_Name, CCN_DTAG);
    ccn_charbuf_append_closer(templ); /* </Name> */

    ccn_charbuf_append_tt(templ, CCN_DTAG_AdditionalNameComponents, CCN_DTAG);
    ccn_charbuf_append_non_negative_integer(templ, addl_comps);
    ccn_charbuf_append_closer(templ); /* </AdditionalNameComponents> */

    ccn_charbuf_append_closer(templ); /* </Interest> */
    res = ccn_express_interest(h, name, prefix_comps, &p->closure, templ);
    assert(res >= 0); // XXX - handle this better
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&seq);
}

/*
 * deliver_content is used to deliver a previously-buffered
 * ContentObject to the client.
 */
static enum ccn_upcall_res
deliver_content(struct ccn *h, struct bulkdata *b)
{
    struct ccn_upcall_info info = {0};
    struct ccn_parsed_ContentObject obj = {0};
    struct pending *p = b->first;
    int res;
    enum ccn_upcall_res ans;
    assert(p != NULL && p->x == b->next_expected && p->content_ccnb != NULL);
    info.pco = &obj;
    info.content_comps = ccn_indexbuf_create();
    res = ccn_parse_ContentObject(p->content_ccnb, p->content_size,
                                  &obj, info.content_comps);
    assert(res >= 0);
    info.content_ccnb = p->content_ccnb;
    info.matched_comps = info.content_comps->n - 2;
    /* XXX - we have no matched interest to present */
    ans = (*b->client->p)(b->client, CCN_UPCALL_CONTENT, &info);
    // XXX - check for refusal
    info.content_ccnb = NULL;
    free(p->content_ccnb);
    p->content_ccnb = NULL;
    p->content_size = 0;
    ccn_indexbuf_destroy(&info.content_comps);
    if (ans == CCN_UPCALL_RESULT_OK) {
        struct ccn_closure *old = &p->closure;
        if ((--(old->refcount)) == 0) {
            info.pco = NULL;
            (old->p)(old, CCN_UPCALL_FINAL, &info);
        }
    }
    return(ans);
}
