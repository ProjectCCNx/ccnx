/**
 * @file csrc/ccn_sync.c
 *
 * Sync library interface.
 * Implements a library interface to the Sync protocol facilities implemented
 * by the Repository
 *
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/time.h>
#include <ccn/ccn.h>
#include <ccn/coding.h>
#include <ccn/digest.h>
#include <ccn/sync.h>
#include <ccn/uri.h>

#include <sync/SyncActions.h>
#include <sync/SyncNode.h>
#include <sync/SyncPrivate.h>
#include <sync/SyncTreeWorker.h>

#define CCNL_NONE       0   /**< No logging at all */
#define CCNL_SEVERE     3   /**< Severe errors */
#define CCNL_ERROR      5   /**< Configuration errors */
#define CCNL_WARNING    7   /**< Something might be wrong */
#define CCNL_INFO       9   /**< Low-volume informational */
#define CCNL_FINE      11   /**< Debugging */
#define CCNL_FINER     13   /**< More debugging */
#define CCNL_FINEST    15   /**< MORE DEBUGGING YET */

#define CACHE_PURGE_TRIGGER 60     // cache entry purge, in seconds
#define CACHE_CLEAN_BATCH 16       // seconds between cleaning batches
#define CACHE_CLEAN_DELTA 8        // cache clean batch size
#define ADVISE_NEED_RESET 1        // reset value for adviseNeed
#define UPDATE_STALL_DELTA 15      // seconds used to determine stalled update
#define UPDATE_NEED_DELTA 6        // seconds for adaptive update
#define SHORT_DELAY_MICROS 500    // short delay for quick reschedule
#define COMPARE_ASSUME_BAD 20      // secs since last fetch OK to assume compare failed
#define NODE_SPLIT_TRIGGER 400    // in bytes, triggers node split
#define EXCLUSION_LIMIT 1000       // in bytes, limits exclusion list size
#define EXCLUSION_TRIG 5           // trigger for including root hashes in excl list (secs)
#define STABLE_TIME_TRIG 10        // trigger for storing stable point (secs)
#define HASH_SPLIT_TRIGGER 17      // trigger for splitting based on hash (n/255)
#define NAMES_YIELD_INC 100        // number of names to inc between yield tests
#define NAMES_YIELD_MICROS 20*1000 // number of micros to use as yield trigger

static ccnr_hwm ccns_hwm_update(struct ccnr_handle *ccnr, ccnr_hwm hwm, ccnr_accession a);
static uintmax_t ccns_accession_encode(struct ccnr_handle *ccnr, ccnr_accession a);

struct ccns_slice {
    unsigned version;
    unsigned nclauses;
    struct ccn_charbuf *topo;
    struct ccn_charbuf *prefix;
    struct ccn_charbuf **clauses; // contents defined in documentation, need utils
};

#define CCNS_FLAGS_SC 1      // start at current root hash.

struct ccns_handle {
    struct SyncBaseStruct *base;
    struct SyncRootStruct *root;
    struct ccn_scheduled_event *ev;
    ccns_callback callback;
    unsigned flags;
};

/*
 * Utility routines to allocate/deallocate ccns_slice structures
 */
struct ccns_slice *
ccns_slice_create()
{
    struct ccns_slice *s = calloc(1, sizeof(*s));
    if (s == NULL)
        return(NULL);
    s->version = SLICE_VERSION;
    s->topo = ccn_charbuf_create_n(8); // name encoding requires minimum 2
    s->prefix = ccn_charbuf_create_n(8);
    if (s->topo == NULL || s->prefix == NULL) {
        ccn_charbuf_destroy(&s->topo);
        ccn_charbuf_destroy(&s->prefix);
        free(s);
        s = NULL;
    }
    ccn_name_init(s->topo);
    ccn_name_init(s->prefix);
    return(s);
}
void
ccns_slice_destroy(struct ccns_slice **sp)
{
    struct ccns_slice *s = *sp;
    if (s != NULL) {
        ccn_charbuf_destroy(&(s->topo));
        ccn_charbuf_destroy(&(s->prefix));
        if (s->clauses != NULL) {
            while(s->nclauses > 0) {
                s->nclauses--;
                ccn_charbuf_destroy(&(s->clauses[s->nclauses]));
            }
            free(s->clauses);
        }
        free(s);
        *sp = NULL;
    }
}
/*
 * Utility routine to add a clause to a ccns_slice structure
 */
int
ccns_slice_add_clause(struct ccns_slice *s, struct ccn_charbuf *c)
{
    struct ccn_charbuf **clauses = NULL;
    struct ccn_charbuf *clause;
    clause = ccn_charbuf_create_n(c->length);
    if (clause == NULL)
        return(-1);
    if (s->clauses == NULL) {
        s->clauses = calloc(1, sizeof(s->clauses[0]));
        if (s->clauses == NULL)
            goto Cleanup;
    } else {
        clauses = realloc(s->clauses, (s->nclauses + 1) * sizeof(s->clauses[0]));
        if (clauses == NULL)
            goto Cleanup;
        s->clauses = clauses;
    }
    ccn_charbuf_append_charbuf(clause, c);
    s->clauses[s->nclauses++] = clause;
    return (0);

Cleanup:
    ccn_charbuf_destroy(&clause);
    return (-1);
}
/*
 * Utility routine to set the topo and prefix fields to copies of the
 * passed in charbufs
 */
int
ccns_slice_set_topo_prefix(struct ccns_slice *s, struct ccn_charbuf *t,
                           struct ccn_charbuf *p)
{
    int res = 0;
    if (t != NULL) {
        ccn_charbuf_reset(s->topo);
        res |= ccn_charbuf_append_charbuf(s->topo, t);
    }
    if (p != NULL) {
        ccn_charbuf_reset(s->prefix);
        res |= ccn_charbuf_append_charbuf(s->prefix, p);
    }
    return(res);
}
/*
 * utility, may need to be exported, to append the encoding of a
 * slice to a charbuf
 */
static int
append_slice(struct ccn_charbuf *c, struct ccns_slice *s)
{
    int res = 0;
    int i;

    res |= ccnb_element_begin(c, CCN_DTAG_SyncConfigSlice);
    res |= ccnb_tagged_putf(c, CCN_DTAG_SyncVersion, "%u", SLICE_VERSION);
    res |= ccn_charbuf_append_charbuf(c, s->topo);
    res |= ccn_charbuf_append_charbuf(c, s->prefix);
    res |= ccnb_element_begin(c, CCN_DTAG_SyncConfigSliceList);
    for (i = 0; i < s->nclauses ; i++) {
        res |= ccnb_tagged_putf(c, CCN_DTAG_SyncConfigSliceOp, "%u", 0);
        res |= ccn_charbuf_append_charbuf(c, s->clauses[i]);
    }
    res |= ccnb_element_end(c);
    res |= ccnb_element_end(c);
    return (res);
}
/*
 * utility, may need to be exported, to parse the buffer into a given slice
 * structure.
 */
static int
slice_parse(struct ccns_slice *s, const unsigned char *p, size_t size)
{
    int res = 0;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder, p, size);
    uintmax_t version;
    int op;
    int start;
    struct ccn_charbuf *clause = NULL;

    if (!ccn_buf_match_dtag(d, CCN_DTAG_SyncConfigSlice))
        return (-1);
    ccn_buf_advance(d);
    if (!ccn_buf_match_dtag(d, CCN_DTAG_SyncVersion))
        return (-1);
    ccn_buf_advance(d);
    ccn_parse_uintmax(d, &version);
    ccn_buf_check_close(d);
    if (version != SLICE_VERSION)
        return (-1);
    start = d->decoder.token_index;
    if (ccn_parse_Name(d, NULL) < 0)
        return(-1);
    ccn_charbuf_reset(s->topo);
    res = ccn_charbuf_append(s->topo, p + start, d->decoder.token_index - start);
    if (res < 0)
        return(-1);
    start = d->decoder.token_index;
    if (ccn_parse_Name(d, NULL) < 0)
        return(-1);
    ccn_charbuf_reset(s->prefix);
    res = ccn_charbuf_append(s->prefix, p + start, d->decoder.token_index - start);
    if (res < 0)
        return(-1);
    if (!ccn_buf_match_dtag(d, CCN_DTAG_SyncConfigSliceList))
        return(-1);
    ccn_buf_advance(d);
    clause = ccn_charbuf_create();
    if (clause == NULL)
        return(-1);
    while (ccn_buf_match_dtag(d, CCN_DTAG_SyncConfigSliceOp)) {
        ccn_buf_advance(d);
        op = ccn_parse_nonNegativeInteger(d); // op is a small integer
        ccn_buf_check_close(d);
        if (op != 0)
            break;
        ccn_charbuf_reset(clause);
        start = d->decoder.token_index;
        if (ccn_parse_Name(d, NULL) < 0)
            break;
        res = ccn_charbuf_append(clause, p + start, d->decoder.token_index - start);
        ccns_slice_add_clause(s, clause);
    }
    ccn_charbuf_destroy(&clause);
    ccn_buf_check_close(d); /* </SyncConfigSliceList> */
    ccn_buf_check_close(d); /* </SyncConfigSlice> */
    if (d->decoder.index != size || !CCN_FINAL_DSTATE(d->decoder.state))
        return(-1);
    return(0);
}
/**
 * Construct the name of a Sync configuration slice based on the parameters.
 * @param nm is the ccn_charbuf which will be set to the ccnb encoded Name
 * @param s is the definition of the slice for which the name is required.
 * @returns a ccn_charbuf with the ccnb encoded Name of the slice.
 */

int
ccns_slice_name(struct ccn_charbuf *nm, struct ccns_slice *s)
{
    struct ccn_charbuf *c;
    struct ccn_digest *digest = NULL;
    struct ccn_charbuf *hash = NULL;
    int res = 0;

    c = ccn_charbuf_create();
    if (c == NULL)
        return (-1);
    res = append_slice(c, s);
    if (res < 0)
        goto Cleanup;

    digest = ccn_digest_create(CCN_DIGEST_SHA256);
    hash = ccn_charbuf_create_n(ccn_digest_size(digest));
    if (hash == NULL)
        goto Cleanup;
    ccn_digest_init(digest);
    res |= ccn_digest_update(digest, c->buf, c->length);
    res |= ccn_digest_final(digest, hash->buf, hash->limit);
    if (res < 0)
        goto Cleanup;
    hash->length = hash->limit;
    if (ccn_name_from_uri(nm, "ccnx:/%C1.M.S.localhost/%C1.S.cs") < 0)
        res = -1;
    res |= ccn_name_append(nm, hash->buf, hash->length);

Cleanup:
    ccn_charbuf_destroy(&c);
    ccn_digest_destroy(&digest);
    ccn_charbuf_destroy(&hash);
    return (res);
}

/**
 * Read a slice (from a repository) given the name.
 * @param h is the ccn_handle on which to read.
 * @param name is the charbuf containing the name of the sync slice to be read.
 * @param slice is a pointer to a ccns_slice object which will be filled in
 *  on successful return.
 * @returns 0 on success, -1 otherwise.
 */
int
ccns_read_slice(struct ccn *h, struct ccn_charbuf *name,
                struct ccns_slice *slice)
{
    struct ccn_parsed_ContentObject pco_space = { 0 };
    struct ccn_parsed_ContentObject *pco = &pco_space;
    struct ccn_charbuf *nc = ccn_charbuf_create_n(name->length);
    struct ccn_charbuf *cob = ccn_charbuf_create();
    const unsigned char *content;
    size_t content_length;
    int res = -1;

    if (nc == NULL || cob == NULL)
        goto Cleanup;

    ccn_charbuf_append_charbuf(nc, name);
    res = ccn_resolve_version(h, nc,  CCN_V_HIGHEST, 100); // XXX: timeout
    if (res < 0)
        goto Cleanup;
    if (res == 0) {
        // TODO: check if the last component is a segment number, chop it off, try again.
    }
    res = ccn_get(h, nc, NULL, 100, cob, pco, NULL, 0);
    if (res < 0)
        goto Cleanup;
    if (pco->type != CCN_CONTENT_DATA) {
        res = -1;
        goto Cleanup;
    }
    res = ccn_content_get_value(cob->buf, cob->length, pco,
                                &content, &content_length);
    if (res < 0)
        goto Cleanup;
    res = slice_parse(slice, content, content_length);

Cleanup:
    ccn_charbuf_destroy(&nc);
    ccn_charbuf_destroy(&cob);
    return (res);
}

struct ccn_charbuf *
make_scope1_template(void)
{
    struct ccn_charbuf *templ = NULL;
    templ = ccn_charbuf_create_n(16);
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccnb_element_begin(templ, CCN_DTAG_Name);
    ccnb_element_end(templ); /* </Name> */
    ccnb_tagged_putf(templ, CCN_DTAG_Scope, "%u", 1);
    ccnb_element_end(templ); /* </Interest> */
    return(templ);
}

static enum ccn_upcall_res
write_interest_handler (struct ccn_closure *selfp,
                        enum ccn_upcall_kind kind,
                        struct ccn_upcall_info *info)
{
    struct ccn_charbuf *cob = selfp->data;
    struct ccn *h = info->h;

    if (kind != CCN_UPCALL_INTEREST)
        return(CCN_UPCALL_RESULT_OK);
    if (ccn_content_matches_interest(cob->buf, cob->length, 1, NULL,
                                     info->interest_ccnb,
                                     info->pi->offset[CCN_PI_E],
                                     info->pi)) {
        ccn_put(info->h, cob->buf, cob->length);
        selfp->intdata = 1;
        ccn_set_run_timeout(h, 0);
        return(CCN_UPCALL_RESULT_INTEREST_CONSUMED);
    }
    return(CCN_UPCALL_RESULT_OK);
}

static int
write_slice(struct ccn *h, struct ccns_slice *slice,
            struct ccn_charbuf *name)
{
    struct ccn_charbuf *content = NULL;
    unsigned char *cbuf = NULL;
    size_t clength = 0;
    struct ccn_charbuf *sw = NULL;
    struct ccn_charbuf *templ = NULL;
    struct ccn_charbuf *cob = NULL;
    struct ccn_signing_params sparm = CCN_SIGNING_PARAMS_INIT;
    struct ccn_closure *wc = NULL;
    int res;

    sw = ccn_charbuf_create_n(32 + name->length);
    if (sw == NULL) {
        res = -1;
        goto Cleanup;
    }
    ccn_charbuf_append_charbuf(sw, name);
    ccn_name_chop(sw, NULL, -1); // remove segment number
    ccn_name_from_uri(sw, "%C1.R.sw");
    ccn_name_append_nonce(sw);

    // create and sign the content object
    cob = ccn_charbuf_create();
    if (cob == NULL) {
        res = -1;
        goto Cleanup;
    }
    if (slice != NULL) {
        content = ccn_charbuf_create();
        if (content == NULL) {
            res = -1;
            goto Cleanup;
        }
        res = append_slice(content, slice);
        if (res < 0)
            goto Cleanup;
        cbuf = content->buf;
        clength = content->length;
    } else {
        sparm.type = CCN_CONTENT_GONE;
    }

    sparm.sp_flags = CCN_SP_FINAL_BLOCK;
    res = ccn_sign_content(h, cob, name, &sparm, cbuf, clength);
    if (res < 0)
        goto Cleanup;
    // establish handler for interest in the slice content object
    wc = calloc(1, sizeof(*wc));
    if (wc == NULL) {
        res = -1;
        goto Cleanup;
    }
    wc->p = &write_interest_handler;
    wc->data = cob;
    res = ccn_set_interest_filter(h, name, wc);
    if (res < 0)
        goto Cleanup;
    templ = make_scope1_template();
    if (templ == NULL) {
        res = -1;
        goto Cleanup;
    }
    res = ccn_get(h, sw, templ, 1000, NULL, NULL, NULL, 0);
    if (res < 0)
        goto Cleanup;
    ccn_run(h, 1000); // give the repository a chance to fetch the data
    if (wc->intdata != 1) {
        res = -1;
        goto Cleanup;
    }
    res = 0;
Cleanup:
    ccn_set_interest_filter(h, name, NULL);
    if (wc != NULL)
        free(wc);
    ccn_charbuf_destroy(&cob);
    ccn_charbuf_destroy(&content);
    ccn_charbuf_destroy(&sw);
    ccn_charbuf_destroy(&templ);
    return (res);
}
/**
 * Write a ccns_slice object to a repository.
 * @param h is the ccn_handle on which to write.
 * @param slice is a pointer to a ccns_slice object to be written.
 * @param name, if non-NULL, is a pointer to a charbuf which will be filled
 *  in with the name of the slice that was written.
 * @returns 0 on success, -1 otherwise.
 */
int
ccns_write_slice(struct ccn *h, struct ccns_slice *slice,
                 struct ccn_charbuf *name)
{
    struct ccn_charbuf *n = NULL;
    int res;
    // calculate versioned and segmented name for the slice
    n = ccn_charbuf_create();
    if (n == NULL)
        return(-1);
    res = ccns_slice_name(n, slice);
    if (res < 0)
        goto Cleanup;
    res |= ccn_create_version(h, n, CCN_V_NOW, 0, 0);
    if (name != NULL) {
        ccn_charbuf_reset(name);
        res |= ccn_charbuf_append_charbuf(name, n);
    }
    res |= ccn_name_append_numeric(n, CCN_MARKER_SEQNUM, 0);
    if (res < 0)
        goto Cleanup;
    res = write_slice(h, slice, n);

Cleanup:
    ccn_charbuf_destroy(&n);
    return (res);
}
/**
 * Delete a ccns_slice object from a repository.
 * @param h is the ccn_handle on which to write.
 * @param name is a pointer to a charbuf naming the slice to be deleted.
 * @returns 0 on success, -1 otherwise.
 */
int
ccns_delete_slice(struct ccn *h, struct ccn_charbuf *name)
{
    struct ccn_charbuf *n = NULL;
    int res;

    // calculate versioned and segmented name for the slice
    n = ccn_charbuf_create_n(32 + name->length);
    if (n == NULL)
        return(-1);
    res = ccn_charbuf_append_charbuf(n, name);
    res |= ccn_create_version(h, n, CCN_V_NOW | CCN_V_REPLACE, 0, 0);
    res |= ccn_name_append_numeric(n, CCN_MARKER_SEQNUM, 0);
    if (res < 0)
        return(-1);
    res = write_slice(h, NULL, n);
    ccn_charbuf_destroy(&n);
    return (res);
}

/*
 * local time source for event schedule
 */
static void
gettime(const struct ccn_gettime *self, struct ccn_timeval *result)
{
    struct timeval now = {0};
    gettimeofday(&now, 0);
    result->s = now.tv_sec;
    result->micros = now.tv_usec;
}

static int HeartbeatAction(struct ccn_schedule *sched,
                           void *clienth,
                           struct ccn_scheduled_event *ev,
                           int flags);

static int ccns_send_root_advise_interest(struct SyncRootStruct *root);

/**
 * Start notification of addition of names to a sync slice.
 * @param h is the ccn_handle on which to communicate
 * @param slice is the slice to be opened.
 * @param callback is the procedure which will be called for each new name,
 *  and returns 0 to continue enumeration, -1 to stop further enumeration.
 *  NOTE: It is not safe to call ccns_close from within the callback.
 * @param rhash
 *      If NULL, indicates that the enumeration should start from the empty set.
 *      If non-NULL but empty, indicates that the enumeration should start from
 *      the current root.
 *      If non-NULL, and not empty, indicates that the enumeration should start
 *      from the specified root hash
 * @param pname if non-NULL represents the starting name for enumeration within
 *  the sync tree represented by the root hash rhash.
 * @returns a pointer to a new sync handle, which will be freed at close.
 */
struct ccns_handle *
ccns_open(struct ccn *h,
          struct ccns_slice *slice,
          ccns_callback callback,
          struct ccn_charbuf *rhash,
          struct ccn_charbuf *pname)
{
    struct ccn_schedule *schedule;
    struct ccn_gettime *timer;
    struct ccns_handle *ccns = calloc(1, sizeof(*ccns));
    struct SyncHashCacheEntry *ceL = NULL;
    if (ccns == NULL)
        return(NULL);
    schedule = ccn_get_schedule(h);
    if (schedule == NULL) {
        timer = calloc(1, sizeof(*timer));
        timer->descr[0]='S';
        timer->micros_per_base = 1000000;
        timer->gettime = &gettime;
        timer->data = h;
        schedule = ccn_schedule_create(h, timer);
        ccn_set_schedule(h, schedule);
    }
    ccns->callback = callback;
    ccns->base = SyncNewBase((void *)ccns, h, schedule); // use ccns instead of ccnr handle
    ccns->base->priv->heartbeatMicros = 1000000;
    ccns->base->priv->rootAdviseLifetime = 20;
    ccns->base->priv->maxComparesBusy = 8;
    ccns->base->debug = CCNL_WARNING;
    ccns->root = SyncAddRoot(ccns->base, ccns->base->priv->syncScope,
                             slice->topo, slice->prefix, NULL);
    // TODO: no filters yet

    // starting at given root hash -- need to sanity check rhash, check node fetch works
    // current behavior on unknown hash is to report failure.
    if (rhash != NULL) {
        if (rhash->length > 0) {
            ccn_charbuf_reset(ccns->root->currentHash);
            ccn_charbuf_append_charbuf(ccns->root->currentHash, rhash);
            ceL = SyncHashEnter(ccns->root->ch, rhash->buf, rhash->length, 0);
        } else {
            ccns->flags |= CCNS_FLAGS_SC;
        }
    }

    ccns_send_root_advise_interest(ccns->root);
    ccns->ev = ccn_schedule_event(ccns->base->sched,
                                  ccns->base->priv->heartbeatMicros,
                                  &HeartbeatAction,
                                  ccns->base,
                                  0);

    return(ccns);
}
/**
 * Stop notification of changes of names in a sync slice and free the handle.
 * @param sh is a pointer (to a pointer) to the sync handle returned
 *  by ccns_open, which will be freed and set to NULL.
 * @param rhash if non-NULL will be filled in with the current root hash.
 * @param pname if non-NULL will be filled in with the starting name
 *  for enumeration within the sync tree represented by the root hash rhash.
 */
void ccns_close(struct ccns_handle **ccnsp, struct ccn_charbuf *rhash, struct ccn_charbuf *pname)
{
    struct ccns_handle *ccns = *ccnsp;
    ccn_schedule_cancel(ccns->base->sched, ccns->ev);
    // pick up current root hash if rhash is non-NULL
    if (rhash != NULL) {
        ccn_charbuf_reset(rhash);
        ccn_charbuf_append_charbuf(rhash, ccns->root->currentHash);
    }
    SyncFreeBase(&(*ccnsp)->base);
    free(*ccnsp);
    *ccnsp = NULL;
    return;
}

void
ccns_msg(struct ccnr_handle *h, const char *fmt, ...)
{
    struct timeval t;
    va_list ap;
    struct ccn_charbuf *b = ccn_charbuf_create();
    ccn_charbuf_reserve(b, 1024);
    gettimeofday(&t, NULL);
    ccn_charbuf_putf(b, "%s\n", fmt);
    char *fb = ccn_charbuf_as_string(b);
    va_start(ap, fmt);
    vfprintf(stdout, fb, ap);
    va_end(ap);
    fflush(stdout);
    ccn_charbuf_destroy(&b);
}

enum SyncCompareState {
    SyncCompare_init,
    SyncCompare_preload,
    SyncCompare_busy,
    SyncCompare_waiting,
    SyncCompare_done
};

struct SyncCompareData {
    struct SyncRootStruct *root;    /**< parent root for this comparison */
    struct SyncTreeWorkerHead *twL; /**< local tree walker state */
    struct SyncTreeWorkerHead *twR; /**< remote tree walker state */
    struct ccn_charbuf *hashL;      /**< hash for root of local sync tree */
    struct ccn_charbuf *hashR;      /**< hash for root of remote sync tree */
    struct ccn_charbuf *cbL;        /**< local tree scratch */
    struct ccn_charbuf *cbR;        /**< remote tree scratch */
    struct ccn_charbuf *lagL;       /**< local lag name */
    int *lagMatch;                  /**< lagging # of matching components */
    struct SyncActionData *errList; /**< actions that had errors for this compare */
    int errsQueued;                 /**< names added during this comparison */
    int namesAdded;                 /**< names added during this comparison */
    int nodeFetchBusy;              /**< number of busy remote node fetches */
    int nodeFetchFailed;            /**< number of failed remote node fetches */
    int contentPos;                 /**< position of next content to fetch */
    int contentFetchBusy;           /**< number of busy content fetches */
    int contentFetchFailed;         /**< number of failed content fetches */
    struct ccn_scheduled_event *ev; /**< progress event */
    enum SyncCompareState state;    /**< summary state of comparison */
    int64_t lastFetchOK;          /**< time marker for last successul node/content fetch */
    int64_t startTime;            /**< time marker for compare data creation */
    int64_t lastEnter;            /**< time marker for last compare step entry */
    int64_t lastMark;             /**< time marker for stall determination */
    int64_t maxHold;                /**< max time thread was held by compare */
};

static void
delinkActionData(struct SyncActionData *data) {
    if (data == NULL) return;
    if (data->state == SyncActionState_sent) {
        // remove from the action chain in the root
        struct SyncRootStruct *root = data->root;
        if (root == NULL) return;
        struct SyncActionData *each = root->actions;
        struct SyncActionData *lag = NULL;
        data->state = SyncActionState_loose;
        while (each != NULL) {
            struct SyncActionData *next = each->next;
            if (data == each) {
                data->next = NULL;
                if (lag == NULL) root->actions = next;
                else lag->next = next;
                break;
            }
            lag = each;
            each = next;
        }
    } else {
        if (data->state == SyncActionState_error) {
            // remove from the errList chain in the comparison
            struct SyncCompareData *comp = data->comp;
            if (comp == NULL) return;
            struct SyncActionData *each = comp->errList;
            struct SyncActionData *lag = NULL;
            data->state = SyncActionState_loose;
            while (each != NULL) {
                struct SyncActionData *next = each->next;
                if (data == each) {
                    data->next = NULL;
                    if (comp->errsQueued > 0) comp->errsQueued--;
                    if (lag == NULL) comp->errList = next;
                    else lag->next = next;
                    break;
                }
                lag = each;
                each = next;
            }
        }
    }
}

static int
moveActionData(struct SyncActionData *data, enum SyncActionState dstState) {
    // moves the action data to the given state queue
    // (must be SyncActionState_sent or SyncActionState_error)
    // returns 1 for success, 0 for not possible
    if (data == NULL) return 0;
    if (dstState == SyncActionState_error && data->state != SyncActionState_sent)
        return 0;
    if (dstState == SyncActionState_sent && data->state != SyncActionState_error)
        return 0;
    struct SyncRootStruct *root = data->root;
    struct SyncCompareData *comp = data->comp;
    if (root == NULL || comp == NULL) return 0;
    delinkActionData(data);
    if (dstState == SyncActionState_sent) {
        data->next = root->actions;
        root->actions = data;
    } else {
        data->next = comp->errList;
        comp->errList = data;
        comp->errsQueued++;
    }
    data->state = dstState;
    return 1;
}

static struct SyncActionData *
destroyActionData(struct SyncActionData *data) {
    if (data != NULL) {
        delinkActionData(data);
        // remove any resources
        if (data->prefix != NULL)
            ccn_charbuf_destroy(&data->prefix);
        if (data->hash != NULL)
            ccn_charbuf_destroy(&data->hash);
        data->next = NULL;
        data->root = NULL;
        data->comp = NULL;
        free(data);
    }
    return NULL;
}

static struct SyncActionData *
newActionData(enum SyncRegisterActionKind kind) {
    struct SyncActionData *data = calloc(1, sizeof(*data));
    data->startTime = SyncCurrentTime();
    data->kind = kind;
    data->state = SyncActionState_init;
    return data;
}

static void
linkActionData(struct SyncRootStruct *root, struct SyncActionData *data) {
    data->root = root;
    data->next = root->actions;
    data->client_handle = root->base->client_handle;
    data->state = SyncActionState_sent;
    root->actions = data;
}

static void
setCovered(struct SyncHashCacheEntry *ce) {
    char *here = "Sync.setCovered";
    if (ce->state & SyncHashState_covered) {
        // nothing to do, already covered
    } else if (ce->state & SyncHashState_remote) {
        // only set this bit if a remote hash has been entered
        struct SyncRootStruct *root = ce->head->root;
        if (root->base->debug >= CCNL_FINER) {
            char *hex = SyncHexStr(ce->hash->buf, ce->hash->length);
            SyncNoteSimple(root, here, hex);
            free(hex);
        }
        ce->state |= SyncHashState_covered;
    }
}

static int
isCovered(struct SyncHashCacheEntry *ce) {
    if (ce->state & SyncHashState_covered) return 1;
    if (ce->state & SyncHashState_local) {
        setCovered(ce);
        return 1;
    }
    return 0;
}

static int
compareHash(struct ccn_charbuf *hashX, struct ccn_charbuf *hashY) {
    if (hashX == hashY) return 0;
    if (hashX == NULL) return -1;
    if (hashY == NULL) return 1;
    size_t lenX = hashX->length;
    size_t lenY = hashY->length;
    if (lenX < lenY) return -1;
    if (lenX > lenY) return 1;
    return memcmp(hashX->buf, hashY->buf, lenX);
}

static struct SyncActionData *
SyncFindAction(struct SyncRootStruct *root, enum SyncRegisterActionKind kind) {
    struct SyncActionData *each = root->actions;
    while (each != NULL) {
        if (each->kind == kind) return each;
        each = each->next;
    }
    return NULL;
}

extern int
SyncAddName(struct SyncBaseStruct *base,
            struct ccn_charbuf *name,
            ccnr_accession item) {
    static char *here = "Sync.SyncAddName";
    struct SyncPrivate *priv = base->priv;
    int debug = base->debug;
    struct SyncRootStruct *root = priv->rootHead;
    int count = 0;
    while (root != NULL) {
        if (SyncRootLookupName(root, name) == SyncRootLookupCode_covered) {
            // ANY matching root gets an addition
            // add the name for later processing
            struct ccn_charbuf *prev = NULL;
            int pos = root->namesToAdd->len;
            if (pos > 0) prev = root->namesToAdd->ents[pos-1].name;
            if (prev != NULL && SyncCmpNames(name, prev) == 0) {
                // this is a duplicate, so forget it!
                if (debug >= CCNL_FINE) {
                    SyncNoteUri(root, here, "ignore dup", name);
                }
            } else {
                // not obviously a duplicate
                uintmax_t itemNum = ccns_accession_encode(base->client_handle, item);
                SyncNameAccumAppend(root->namesToAdd, SyncCopyName(name), itemNum);
                if (item != CCNR_NULL_ACCESSION)
                    root->priv->highWater = ccns_hwm_update(base->client_handle,
                                                            root->priv->highWater,
                                                            item);
                count++;
                if (debug >= CCNL_FINE) {
                    char temp[64];
                    // TBD: improve item reporting?
                    if (item >= CCNR_MIN_ACCESSION && item <= CCNR_MAX_ACCESSION) {
                        snprintf(temp, sizeof(temp), "added, %ju", itemNum);
                    } else {
                        snprintf(temp, sizeof(temp), "no accession");
                    }
                    SyncNoteUri(root, here, temp, name);
                }
            }
        }
        root = root->next;
    }
    if (item != CCNR_NULL_ACCESSION)
        base->highWater = ccns_hwm_update(base->client_handle, base->highWater, item);
    return count;
}

static struct SyncNodeComposite *
extractNode(struct SyncRootStruct *root, struct ccn_upcall_info *info) {
    // first, find the content
    char *here = "Sync.extractNode";
    const unsigned char *cp = NULL;
    size_t cs = 0;
    size_t ccnb_size = info->pco->offset[CCN_PCO_E];
    const unsigned char *ccnb = info->content_ccnb;
    int res = ccn_content_get_value(ccnb, ccnb_size, info->pco,
                                    &cp, &cs);
    if (res < 0 || cs < DEFAULT_HASH_BYTES) {
        SyncNoteFailed(root, here, "ccn_content_get_value", __LINE__);
        return NULL;
    }

    // second, parse the object
    struct SyncNodeComposite *nc = SyncAllocComposite(root->base);
    struct ccn_buf_decoder ds;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&ds, cp, cs);
    res |= SyncParseComposite(nc, d);
    if (res < 0) {
        // failed, so back out of the allocations
        SyncNoteFailed(root, here, "bad parse", -res);
        SyncFreeComposite(nc);
        nc = NULL;
    }
    return nc;
}

static int
noteRemoteHash(struct SyncRootStruct *root, struct SyncHashCacheEntry *ce, int add) {
    char *here = "Sync.noteRemoteHash";
    int debug = root->base->debug;
    struct ccnr_handle *ccnr = root->base->client_handle;
    struct ccn_charbuf *hash = ce->hash;
    int hl = hash->length;
    if (hl == 0) return 0;
    struct SyncHashInfoList *head = root->priv->remoteSeen;
    struct SyncHashInfoList *each = head;
    struct SyncHashInfoList *lag = NULL;
    int64_t mark = SyncCurrentTime();
    ce->lastUsed = mark;
    ce->lastRemoteFetch = mark;
    if (ce->state & SyncHashState_local)
        setCovered(ce);
    while (each != NULL) {
        if (ce == each->ce) {
            if (lag != NULL) {
                // move it to the front
                lag->next = each->next;
                each->next = head;
                root->priv->remoteSeen = each;
            }
            break;
        }
        lag = each;
        each = each->next;
    }
    if (each == NULL && add) {
        // need a new entry
        each = calloc(1, sizeof(*each));
        each->next = head;
        root->priv->remoteSeen = each;
    }
    if (debug >= CCNL_FINE) {
        char *hex = SyncHexStr(hash->buf, hash->length);
        char *extra = "";
        if (ce->state & SyncHashState_covered) extra = "covered, ";
        ccns_msg(ccnr, "%s, root#%u, %s%s", here, root->rootId, extra, hex);
        free(hex);
    }
    if (each != NULL) {
        each->ce = ce;
        ce->busy++;
        each->lastSeen = mark;
    }
    return 1;
}

static char *
getCmdStr(enum SyncRegisterActionKind kind) {
    switch (kind) {
        case SRI_Kind_AdviseInt:
        case SRI_Kind_RootAdvise:
            return "\xC1.S.ra";
        case SRI_Kind_FetchInt:
        case SRI_Kind_NodeFetch:
            return "\xC1.S.nf";
        case SRI_Kind_RootStats:
            return "\xC1.S.rs";
        default:
            return NULL;
    }
}

// take a list of names and sort them, removing duplicates!
// should leave src empty
static struct SyncNameAccum *
sortNames(struct SyncRootStruct *root, struct SyncNameAccum *src) {
    char *here = "Sync.sortNames";
    IndexSorter_Index ixLim = src->len;
    IndexSorter_Base ixBase = IndexSorter_New(ixLim, -1);
    ixBase->sorter = SyncNameAccumSorter;
    ixBase->client = src;
    IndexSorter_Index ix = 0;
    for (ix = 0; ix < ixLim; ix++) IndexSorter_Add(ixBase, ix);
    struct SyncNameAccum *dst = SyncAllocNameAccum(ixLim);
    struct ccn_charbuf *lag = NULL;
    for (ix = 0; ix < ixLim; ix++) {
        IndexSorter_Index j = IndexSorter_Rem(ixBase);
        if (j >= ixLim) {
            SyncNoteFailed(root, here, "rem failed", __LINE__);
            break;
        }
        struct ccn_charbuf *name = src->ents[j].name;
        src->ents[j].name = NULL;
        if (name == NULL) {
            SyncNoteFailed(root, here, "name == NULL", __LINE__);
            break;
        }
        if (lag == NULL || SyncCmpNames(lag, name) != 0) {
            // only append the name if it is not a duplicate
            SyncNameAccumAppend(dst, name, src->ents[j].data); // XXXXXX
            lag = name;
        } else {
            // this name needs to be destroyed
            ccn_charbuf_destroy(&name);
        }
    }
    src->len = 0;
    IndexSorter_Free(&ixBase);
    return dst;
}

static struct SyncNameAccum *
exclusionsFromHashList(struct SyncRootStruct *root, struct SyncHashInfoList *list) {
    struct SyncNameAccum *acc = SyncAllocNameAccum(0);
    int count = 0;
    int limit = 1000;   // exclusionLimit
    int64_t now = SyncCurrentTime();
    int64_t limitMicros = 1000000 * 5; // exclusionTrig

    if (root->currentHash->length > 0) {
        // if the current hash is not empty, start there
        struct ccn_charbuf *hash = root->currentHash;
        struct ccn_charbuf *name = ccn_charbuf_create();
        count = count + hash->length + 8;
        ccn_name_init(name);
        ccn_name_append(name, hash->buf, hash->length);
        SyncNameAccumAppend(acc, name, 0);
    }

    while (list != NULL) {
        struct SyncHashCacheEntry *ce = list->ce;
        if (ce != NULL && (ce->state & SyncHashState_remote)
            && (ce->state & SyncHashState_covered)
            && SyncDeltaTime(ce->lastUsed, now) < limitMicros) {
            // any remote root known to be covered is excluded
            struct ccn_charbuf *hash = ce->hash;
            count = count + hash->length + 8;
            if (count > limit)
                // exclusion list is getting too long, so ignore earlier roots
                break;
            struct ccn_charbuf *name = ccn_charbuf_create();
            ccn_name_init(name);
            ccn_name_append(name, hash->buf, hash->length);
            SyncNameAccumAppend(acc, name, 0);
        }
        list = list->next;
    }
    if (acc->len == 0) {
        SyncFreeNameAccum(acc);
        return NULL;
    }
    struct SyncNameAccum *lag = acc;
    if (acc->len == 0) {
        // empty liust convention is NULL
        acc = NULL;
    } else {
        // exclusion list must be sorted
        acc = sortNames(root, acc);
    }
    SyncFreeNameAccum(lag);
    return acc;
}

static struct ccn_charbuf *
constructCommandPrefix(struct SyncRootStruct *root,
                       enum SyncRegisterActionKind kind) {
    struct ccn_charbuf *prefix = ccn_charbuf_create();
    int res = 0;
    ccn_name_init(prefix);
    if (root->topoPrefix != NULL && root->topoPrefix->length > 0) {
        // the topo (if any) always comes first
        res |= SyncAppendAllComponents(prefix, root->topoPrefix);
    }
    // the command comes after the topo
    ccn_name_append_str(prefix, getCmdStr(kind));
    res |= ccn_name_append(prefix, root->sliceHash->buf, root->sliceHash->length);

    if (res < 0) {
        ccn_charbuf_destroy(&prefix);
    }
    return prefix;
}

// callback for when a root advise interest gets a response
enum ccn_upcall_res
ccns_root_advise_response(struct ccn_closure *selfp,
                          enum ccn_upcall_kind kind,
                          struct ccn_upcall_info *info) {
    static char *here = "Sync.SyncRootAdviseResponse";
    struct SyncActionData *data = selfp->data;
    enum ccn_upcall_res ret = CCN_UPCALL_RESULT_OK;
    switch (kind) {
        case CCN_UPCALL_FINAL:
            data = destroyActionData(data);
            free(selfp);
            break;
        case CCN_UPCALL_CONTENT_UNVERIFIED:
            ret = CCN_UPCALL_RESULT_VERIFY;
            break;
        case CCN_UPCALL_CONTENT_KEYMISSING:
            ret = CCN_UPCALL_RESULT_FETCHKEY;
            break;
        case CCN_UPCALL_INTEREST_TIMED_OUT:
            if (data == NULL || info == NULL ||
                data->root == NULL || data->kind != SRI_Kind_RootAdvise) {
                // not active, no useful info
            } else {
                int64_t now = SyncCurrentTime();
                struct SyncRootStruct *root = data->root;
                int debug = root->base->debug;
                // root->priv->stats->rootAdviseTimeout++;
                if (debug >= CCNL_INFO) {
                    char temp[64];
                    int64_t dt = SyncDeltaTime(data->startTime, now);
                    dt = (dt + 500) / 1000;
                    snprintf(temp, sizeof(temp),
                             "timeout, %d.%03d secs",
                             (int) (dt / 1000), (int) (dt % 1000));
                    SyncNoteUri(root, here, temp, data->prefix);
                }
                data->startTime = now;
                // as long as we need a response, keep expressing it
                ret = CCN_UPCALL_RESULT_REEXPRESS;
            }
            break;
        case CCN_UPCALL_CONTENT_RAW:
        case CCN_UPCALL_CONTENT:
            if (data == NULL || info == NULL ||
                data->root == NULL || data->kind != SRI_Kind_RootAdvise) {
                // not active, no useful info
                break;
            }
            struct SyncRootStruct *root = data->root;
            int debug = root->base->debug;
            if (debug >= CCNL_INFO) {
                struct ccn_charbuf *nm = SyncNameForIndexbuf(info->content_ccnb,
                                                             info->content_comps);
                size_t bytes = info->pco->offset[CCN_PCO_E];
                char temp[64];
                int64_t dt = SyncDeltaTime(data->startTime, SyncCurrentTime());
                dt = (dt + 500) / 1000;
                snprintf(temp, sizeof(temp),
                         "content, %d.%03d secs, %u bytes",
                         (int) (dt / 1000), (int) (dt % 1000),
                         (unsigned) bytes);
                SyncNoteUri(root, here, temp, nm);
                ccn_charbuf_destroy(&nm);
            }

            const unsigned char *hp = NULL;
            size_t hs = 0;
            size_t bytes = 0;
            int failed = 0;
            int cres = ccn_name_comp_get(info->content_ccnb,
                                         info->content_comps,
                                         data->skipToHash, &hp, &hs);
            if (cres < 0 || hp == NULL) {
                // bad hash, so complain
                failed++;
                SyncNoteFailed(root, here, "bad hash", __LINE__);
                //            } else if (fauxError(root->base)) {
                //                failed++;
                //                if (debug >= CCNL_WARNING)
                //                    SyncNoteSimple(root, here, "faux error");
            } else {
                struct SyncHashCacheEntry *ce = SyncHashEnter(root->ch, hp, hs,
                                                              SyncHashState_remote);
                noteRemoteHash(root, ce, 1);
                if (!isCovered(ce)) {
                    // may need to make an entry
                    struct SyncNodeComposite *nc = NULL;
                    char *hex = SyncHexStr(hp, hs);
                    if (ce != NULL && ce->ncR != NULL) {
                        nc = ce->ncR;
                        if (debug >= CCNL_INFO)
                            SyncNoteSimple2(root, here, "existing but not covered", hex);
                    } else {
                        nc = extractNode(root, info);
                        if (nc == NULL) {
                            // this is bad news, the parsing failed
                            failed++;
                            if (debug >= CCNL_SEVERE)
                                SyncNoteSimple2(root, here, "extractNode failed", hex);
                        } else {
                            // new entry
                            ce->ncR = nc;
                            SyncNodeIncRC(nc);
                            bytes = info->pco->offset[CCN_PCO_E];
                            if (debug >= CCNL_INFO)
                                SyncNoteSimple2(root, here, "remote entered", hex);
                        }
                    }
                    free(hex);
                }
            }
            if (failed) {
                root->priv->stats->rootAdviseFailed++;
            } else {
                root->priv->stats->rootAdviseReceived++;
                root->priv->stats->rootAdviseBytes += bytes;
            }
            break;
        default:
            // SHOULD NOT HAPPEN
            ret = CCN_UPCALL_RESULT_ERR;
            break;
    }
    return ret;
}

static int
ccns_send_root_advise_interest(struct SyncRootStruct *root) {
    static char *here = "Sync.SyncSendRootAdviseInterest";
    enum SyncRegisterActionKind kind = SRI_Kind_RootAdvise;
    int debug = root->base->debug;
    struct SyncActionData *data = SyncFindAction(root, kind);
    struct SyncHashCacheEntry *ce = NULL;
    if (root->currentHash->length > 0) {
        ce = SyncHashLookup(root->ch,
                            root->currentHash->buf,
                            root->currentHash->length);
    }
    if (data != NULL) {
        // don't override exiting interest for this root unless the root has changed
        if (ce == NULL || ce == root->priv->lastLocalSent)
            return 0;
        // mark this as inactive, response to be ignored
        data->kind = SRI_Kind_None;
        if (debug >= CCNL_FINE)
            SyncNoteSimple(root, here, "marked old interest as inactive");
    }
    struct ccn_closure *action = calloc(1, sizeof(*action));
    struct ccn_charbuf *prefix = constructCommandPrefix(root, kind);
    struct ccn_charbuf *hash = ccn_charbuf_create();

    ccn_charbuf_append_charbuf(hash, root->currentHash);
    ccn_name_append(prefix, hash->buf, hash->length);

    data = newActionData(kind);
    data->skipToHash = SyncComponentCount(prefix);
    data->hash = hash;
    data->prefix = prefix;
    action->data = data;
    action->p = &ccns_root_advise_response;

    struct SyncNameAccum *excl = exclusionsFromHashList(root, root->priv->remoteSeen);
    struct ccn_charbuf *template = SyncGenInterest(NULL,
                                                   1, // scope
                                                   root->base->priv->rootAdviseLifetime,
                                                   -1, -1,
                                                   excl);
    int res = ccn_express_interest(root->base->ccn,
                                   prefix,
                                   action,
                                   template);
    SyncFreeNameAccumAndNames(excl);
    ccn_charbuf_destroy(&template);
    if (res >= 0) {
        // link the request into the root
        if (root->priv->adviseNeed > 0) root->priv->adviseNeed--;
        linkActionData(root, data);
        root->priv->lastAdvise = SyncCurrentTime();
        root->priv->lastLocalSent = ce;
        root->priv->stats->rootAdviseSent++;
        if (debug >= CCNL_INFO)
            SyncNoteUri(root, here, "sent", prefix);
        return 1;
    } else {
        // failed, so return the storage
        data = destroyActionData(data);
        free(action);
        if (debug >= CCNL_ERROR)
            SyncNoteSimple(root, here, "ccn_express_interest failed");
        return -1;
    }
}

static int
CompareAction(struct ccn_schedule *sched,
              void *clienth,
              struct ccn_scheduled_event *ev,
              int flags);

static struct SyncHashInfoList *
chooseRemoteHash(struct SyncRootStruct *root) {
    struct SyncHashInfoList *each = root->priv->remoteSeen;
    int64_t now = SyncCurrentTime();
    int64_t limit = ((int64_t)root->base->priv->rootAdviseLifetime)*3*1000000;
    struct SyncHashInfoList *lag = NULL;
    while (each != NULL) {
        struct SyncHashCacheEntry *ce = each->ce;
        struct SyncHashInfoList *next = each->next;
        if (ce != NULL
            && (ce->state & SyncHashState_remote)
            && ((ce->state & SyncHashState_covered) == 0)) {
            // choose the first entry that is remote and not covered
            int64_t dt = SyncDeltaTime(ce->lastUsed, now);
            if (dt < limit) return each;
            ce = NULL;
        }
        if (ce == NULL || (ce->state & SyncHashState_covered)) {
            // prune this entry
            if (lag == NULL) root->priv->remoteSeen = next;
            else lag->next = next;
            free(each);
        } else
            lag = each;
        each = next;
    }
    return NULL;
}

static void
destroyCompareData(struct SyncCompareData *data) {
    if (data == NULL) return;
    struct SyncRootStruct *root = data->root;
    struct SyncPrivate *priv = root->base->priv;
    if (root != NULL) {
        while (data->errList != NULL) {
            struct SyncActionData *sad = data->errList;
            destroyActionData(sad);
        }
        root->namesToFetch = SyncFreeNameAccumAndNames(root->namesToFetch);
        root->compare = NULL;
        struct SyncActionData *each = root->actions;
        // break the link from the action to the compare
        while (each != NULL) {
            if (each->comp == data) each->comp = NULL;
            each = each->next;
        }
    }
    if (priv->comparesBusy > 0) priv->comparesBusy--;
    ccn_charbuf_destroy(&data->hashL);
    ccn_charbuf_destroy(&data->hashR);
    ccn_charbuf_destroy(&data->cbL);
    ccn_charbuf_destroy(&data->cbR);
    data->twL = SyncTreeWorkerFree(data->twL);
    data->twR = SyncTreeWorkerFree(data->twR);
    if (data->ev != NULL && root != NULL) {
        data->ev->evdata = NULL;
        ccn_schedule_cancel(root->base->sched, data->ev);
    }
    free(data);
}


static void
abortCompare(struct SyncCompareData *data, char *why) {
    // this compare failed due to a node fetch or content fetch failure
    // we could get repeated failures if we try the same remote node,
    // so remove it from the seen remote nodes, then destroy the compare data
    if (data == NULL) return;
    struct SyncRootStruct *root = data->root;
    if (root != NULL) {
        char *here = "Sync.abortCompare";
        struct SyncBaseStruct *base = root->base;
        struct SyncRootPrivate *priv = root->priv;
        struct SyncHashInfoList *list = priv->remoteSeen;
        struct SyncHashInfoList *lag = NULL;
        struct ccn_charbuf *hash = data->hashR;
        while (list != NULL) {
            struct SyncHashInfoList *next = list->next;
            struct SyncHashCacheEntry *ce = list->ce;
            if (ce != NULL && compareHash(ce->hash, hash) == 0) {
                // found the failed root, so remove the remote entry
                // if we really needed it it will come back via root advise
                if (base->debug >= CCNL_INFO) {
                    // maybe this should be a warning?
                    char *hex = SyncHexStr(hash->buf, hash->length);
                    ccns_msg(root->base->client_handle,
                             "%s, root#%u, remove remote hash %s",
                             here, root->rootId, hex);
                    free(hex);
                }
                list->next = NULL;
                list->ce = NULL;
                if (ce->busy > 0) ce->busy--;
                if (lag == NULL) priv->remoteSeen = next;
                else lag->next = next;
                free(list);
                break;
            }
            lag = list;
            list = next;
        }
        if (root->base->debug >= CCNL_WARNING)
            SyncNoteSimple(root, here, why);
    }
    destroyCompareData(data);
}

static int
comparisonFailed(struct SyncCompareData *data, char *why, int line) {
    SyncNoteFailed(data->root, "Sync.CompareAction", why, line);
    data->state = SyncCompare_waiting;
    return -1;
}

static int
extractBuf(struct ccn_charbuf *cb, struct SyncNodeComposite *nc, struct SyncNodeElem *ne) {
    struct ccn_buf_decoder ds;
    struct ccn_buf_decoder *d = SyncInitDecoderFromElem(&ds, nc, ne);
    ccn_charbuf_reset(cb);
    int res = SyncAppendElementInner(cb, d);
    return res;
}

static struct SyncHashCacheEntry *
ensureRemoteEntry(struct SyncCompareData *data,
                  const unsigned char * xp,
                  ssize_t xs) {
    char *here = "Sync.ensureRemoteEntry";
    struct SyncRootStruct *root = data->root;
    struct SyncHashCacheEntry *ce = SyncHashEnter(root->ch, xp, xs, SyncHashState_remote);
    if (ce == NULL) {
        // and why did this fail?
        SyncNoteFailed(root, here, "bad enter", __LINE__);
        return ce;
    }
    if (ce->state & SyncHashState_local) setCovered(ce);
    return ce;
}

static struct SyncHashCacheEntry *
cacheEntryForElem(struct SyncCompareData *data,
                  struct SyncNodeComposite *nc,
                  struct SyncNodeElem *ne,
                  int remote) {
    char *here = "Sync.cacheEntryForElem";
    struct SyncRootStruct *root = data->root;
    struct ccn_buf_decoder ds;
    struct ccn_buf_decoder *d = SyncInitDecoderFromOffset(&ds, nc,
                                                          ne->start,
                                                          ne->stop);
    const unsigned char * xp = NULL;
    ssize_t xs = 0;
    SyncGetHashPtr(d, &xp, &xs);
    if (xs == 0 || xp == NULL) {
        // no hash?  this could be a problem
        SyncNoteFailed(root, here, "no hash", __LINE__);
        return NULL;
    }
    struct SyncHashCacheEntry *ce = NULL;
    if (remote > 0) {
        // the entry should be remote
        ce = ensureRemoteEntry(data, xp, xs);
    } else {
        // local entry, fetch it if missing
        ce = SyncHashLookup(root->ch, xp, xs);
        if (SyncCacheEntryFetch(ce) < 0) {
            SyncNoteFailed(root, here, "bad fetch", __LINE__);
            return NULL;
        }
    }
    if (ce == NULL) {
        // this entry should already exist
        SyncNoteFailed(root, here, "bad lookup", __LINE__);
        return ce;
    }
    ce->lastUsed = data->lastEnter;
    return ce;
}

static void
kickCompare(struct SyncCompareData *scd, struct SyncActionData *action) {
    // we just got content for a particular action
    // may need to restart CompareAction
    if (scd != NULL && scd->ev == NULL) {
        struct ccn_scheduled_event *ev = ccn_schedule_event(scd->root->base->sched,
                                                            2000,
                                                            CompareAction,
                                                            scd,
                                                            0);
        scd->ev = ev;
    }
}

// callback for when an interest gets a response
// used when fetching a remote content object by explicit name
// or when fetching a remote node
extern enum ccn_upcall_res
SyncRemoteFetchResponse(struct ccn_closure *selfp,
                        enum ccn_upcall_kind kind,
                        struct ccn_upcall_info *info) {
    static char *here = "Sync.SyncRemoteFetchResponse";
    struct SyncActionData *data = selfp->data;
    enum ccn_upcall_res ret = CCN_UPCALL_RESULT_OK;
    switch (kind) {
        case CCN_UPCALL_FINAL:
            selfp->data = destroyActionData(data);
            free(selfp);
            break;
        case CCN_UPCALL_CONTENT_UNVERIFIED:
            // TBD: fix this when we can actually verify
            // return CCN_UPCALL_RESULT_VERIFY;
#if (CCN_API_VERSION >= 4004)
        case CCN_UPCALL_CONTENT_RAW:
        case CCN_UPCALL_CONTENT_KEYMISSING:
#endif
        case CCN_UPCALL_INTEREST_TIMED_OUT:
        case CCN_UPCALL_CONTENT: {
            if (data == NULL) break;
            struct ccnr_handle *ccnr = data->client_handle;
            struct SyncRootStruct *root = data->root;
            struct SyncCompareData *comp = data->comp;
            if (root == NULL) break;
            int debug = root->base->debug;
            struct SyncRootStats *stats = root->priv->stats;
            size_t bytes = 0;
            int64_t now = SyncCurrentTime();
            if (ccnr != NULL && info != NULL && info->pco != NULL
                && kind != CCN_UPCALL_INTEREST_TIMED_OUT)
                bytes = info->pco->offset[CCN_PCO_E];
            if (debug >= CCNL_INFO) {
                char temp[64];
                char *ns = "node";
                char *ks = "ok";
                if (data->kind == SRI_Kind_Content) ns = "content";
                if (kind == CCN_UPCALL_INTEREST_TIMED_OUT) ks = "timeout!";
                int64_t dt = SyncDeltaTime(data->startTime, now);
                dt = (dt + 500) / 1000;
                if (bytes > 0)
                    snprintf(temp, sizeof(temp),
                             "%s, %s, %d.%03d secs, %u bytes",
                             ns, ks, (int) (dt / 1000), (int) (dt % 1000),
                             (unsigned) bytes);
                else
                    snprintf(temp, sizeof(temp),
                             "%s, %s, %d.%03d secs",
                             ns, ks, (int) (dt / 1000), (int) (dt % 1000));
                SyncNoteUri(root, here, temp, data->prefix);
            }

            switch (data->kind) {
                case SRI_Kind_NodeFetch: {
                    // node fetch response
                    const unsigned char *xp = data->hash->buf;
                    ssize_t xs = data->hash->length;
                    char *hex = SyncHexStr(xp, xs);
                    struct SyncHashCacheEntry *ce = SyncHashLookup(root->ch, xp, xs);
                    if (bytes <= 0) {
                        // did not get the node at all
                    } else if (ce != NULL && (isCovered(ce) || ce->ncR != NULL)) {
                        // there was a race, and we no longer need this
                        // for stats, count this as a success
                        if (debug >= CCNL_FINE) {
                            SyncNoteSimple2(root, here, "remote node covered", hex);
                        }
                    } else {
                        // we actually need the node that arrived
                        struct SyncNodeComposite *ncR = extractNode(root, info);
                        if (ncR == NULL) {
                            // decoding error, so can't use
                            if (debug >= CCNL_SEVERE)
                                SyncNoteSimple2(root, here, "extractNode failed", hex);
                            bytes = 0;
                        } else {
                            // the entry can now be completed
                            ce = SyncHashEnter(root->ch, xp, xs, SyncHashState_remote);
                            ce->ncR = ncR;
                            SyncNodeIncRC(ncR);
                            // for the library, need to duplicate into the Local side
                            if (ce->ncL == NULL) {
                                ce->ncL = ncR;
                                SyncNodeIncRC(ncR);
                            }
                            // end
                            if (debug >= CCNL_INFO) {
                                SyncNoteSimple2(root, here, "remote node entered", hex);
                            }
                            if (comp == NULL) {
                                if (debug >= CCNL_ERROR)
                                    SyncNoteSimple(root, here, "remote node comp == NULL");
                            }
                        }
                    }
                    if (comp != NULL && comp->nodeFetchBusy > 0)
                        comp->nodeFetchBusy--;
                    if (bytes > 0) {
                        // node fetch wins
                        stats->nodeFetchReceived++;
                        stats->nodeFetchBytes += bytes;
                        if (comp != NULL)
                            comp->lastFetchOK = now;
                    } else {
                        // node fetch fails
                        if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
                            stats->nodeFetchTimeout++;
                        else stats->nodeFetchFailed++;
                        if (comp != NULL) {
                            // remember that this one failed
                            if (!moveActionData(data, SyncActionState_error))
                                SyncNoteFailed(root, here, "moveActionData", __LINE__);
                            comp->nodeFetchFailed++;
                            selfp->data = NULL;
                        }
                    }
                    if (ce != NULL && (ce->state & SyncHashState_fetching))
                        // we are no longer fetching this node
                        ce->state -= SyncHashState_fetching;
                    kickCompare(comp, data);
                    free(hex);
                    break;
                }
                default:
                    // SHOULD NOT HAPPEN
                    ret = CCN_UPCALL_RESULT_ERR;
                    break;
            }
            break;
        }
        default:
            // SHOULD NOT HAPPEN
            ret = CCN_UPCALL_RESULT_ERR;
            break;
    }
    return ret;
}

extern int
SyncStartNodeFetch(struct SyncRootStruct *root,
                   struct SyncHashCacheEntry *ce,
                   struct SyncCompareData *comp) {
    static char *here = "Sync.SyncStartNodeFetch";
    enum SyncRegisterActionKind kind = SRI_Kind_NodeFetch;
    struct SyncBaseStruct *base = root->base;
    int debug = base->debug;
    struct ccn *ccn = base->ccn;
    if (ccn == NULL)
        return SyncNoteFailed(root, here, "bad ccn handle", __LINE__);
    // first, check for existing fetch of same hash
    struct ccn_charbuf *hash = ce->hash;
    struct SyncActionData *data = root->actions;
    if (ce->state & SyncHashState_fetching)
        // already busy
        return 0;
    while (data != NULL) {
        if (data->kind == kind && compareHash(data->hash, hash) == 0)
            return 0;
        data = data->next;
    }

    struct ccn_closure *action = calloc(1, sizeof(*action));
    data = newActionData(kind);
    struct ccn_charbuf *name = constructCommandPrefix(root, kind);
    int res = -1;
    char *why = "constructCommandPrefix";
    if (name != NULL) {
        data->skipToHash = SyncComponentCount(name);
        ccn_name_append(name, hash->buf, hash->length);
        data->prefix = name;
        data->hash = ccn_charbuf_create();
        ccn_charbuf_append_charbuf(data->hash, hash);
        data->comp = comp;
        action->data = data;
        action->p = &SyncRemoteFetchResponse;

        struct ccn_charbuf *template = SyncGenInterest(NULL,
                                                       1,
                                                       base->priv->fetchLifetime,
                                                       -1, 1, NULL);
        res = ccn_express_interest(ccn, name, action, template);
        if (res < 0) {
            why = "ccn_express_interest";
            if (debug >= CCNL_SEVERE) {
                char *hex = SyncHexStr(hash->buf, hash->length);
                SyncNoteSimple2(root, here, "failed to express interest", hex);
                free(hex);
            }
        } else {
            root->priv->stats->nodeFetchSent++;
            if (debug >= CCNL_INFO) {
                char *hex = SyncHexStr(hash->buf, hash->length);
                SyncNoteSimple2(root, here, "fetching", hex);
                free(hex);
            }
        }
        ccn_charbuf_destroy(&template);
    }
    if (res >= 0) {
        // link the request into the root
        linkActionData(root, data);
        comp->nodeFetchBusy++;
        ce->state |= SyncHashState_fetching;
        res = 1;
    } else {
        // return the storage
        comp->nodeFetchFailed++;
        data = destroyActionData(data);
        free(action);
        if (debug >= CCNL_SEVERE)
            SyncNoteFailed(root, here, why, __LINE__);
    }
    return res;
}

/*
 * doPreload(data) walks the remote tree, and requests a fetch for every remote
 * node that is not covered locally and has not been fetched,
 * and is not being fetched.  This allows large trees to be fetched in parallel,
 * speeding up the load process.
 */
static int
doPreload(struct SyncCompareData *data, struct SyncTreeWorkerHead *twHead) {
    struct SyncRootStruct *root = data->root;
    int busyLim = root->base->priv->maxFetchBusy;
    for (;;) {
        if (data->nodeFetchBusy > busyLim) return 0;
        if (twHead->level <= 0) break;
        struct SyncTreeWorkerEntry *ent = SyncTreeWorkerTop(twHead);
        if (ent->cacheEntry == NULL)
            return -1;
        struct SyncHashCacheEntry *ceR = ent->cacheEntry;
        if (ceR == NULL
            || ceR->state & SyncHashState_fetching
            || ceR->state & SyncHashState_covered
            || ceR->state & SyncHashState_local) {
            // not a needed node, so pop it
        } else if (ceR->ncR != NULL) {
            // visit the children
            struct SyncNodeComposite *ncR = ceR->ncR;
            int lim = ncR->refLen;
            while (ent->pos < lim) {
                struct SyncNodeElem *ep = &ncR->refs[ent->pos];
                if ((ep->kind & SyncElemKind_leaf) == 0)
                    break;
                ent->pos++;
            }
            if (ent->pos < lim) {
                struct SyncNodeElem *ep = &ncR->refs[ent->pos];
                struct SyncHashCacheEntry *sub = cacheEntryForElem(data, ncR, ep, 1);
                if (sub == NULL)
                    return -1;
                ent = SyncTreeWorkerPush(twHead);
                if (ent == NULL)
                    return -1;
                continue;
            }
        } else {
            // init the fetch, then pop
            SyncStartNodeFetch(root, ceR, data);
        }
        // common exit to pop and iterate
        ent = SyncTreeWorkerPop(twHead);
        if (ent != NULL) ent->pos++;
    }
    while (data->nodeFetchBusy < busyLim) {
        // restart the failed node fetches (while we can)
        struct SyncActionData *sad = data->errList;
        if (sad == NULL) break;
        struct SyncHashCacheEntry *ceR = SyncHashLookup(root->ch,
                                                        sad->hash->buf,
                                                        sad->hash->length);
        SyncStartNodeFetch(root, ceR, data);
        destroyActionData(sad);
    }

    if (data->nodeFetchBusy > 0) return 0;
    if (data->errList != NULL) return 0;
    if (twHead->level > 0) return 0;
    return 1;
}

static int
addNameFromCompare(struct SyncCompareData *data) {
    char *here = "Sync.addNameFromCompare";
    struct SyncRootStruct *root = data->root;
    struct ccns_handle *ccns = (struct ccns_handle *)root->base->client_handle;
    int debug = root->base->debug;
    struct ccn_charbuf *name = data->cbR;
    int res;
    // callback for new name
    struct SyncTreeWorkerEntry *tweR = SyncTreeWorkerTop(data->twR);
    tweR->pos++;
    tweR->count++;
    data->namesAdded++;
    res = ccns->callback(ccns, root->currentHash, data->hashR, name);
    if (debug >= CCNL_FINE) {
        SyncNoteUri(root, here, "added", name);
    }
    return 0;
}

/*
 * doComparison(data) is a key routine, because it determines what is
 * present in data->twR that is not present in data->twL.  It does so by
 * walking the two trees, L and R, in increasing name order.  To gain efficiency
 * doComparison avoids examining nodes in R that are already covered, and nodes
 * in L that have been bypassed in the walk of R.
 *
 * Ideally doComparison allows determination of k differences in O(k*log(N))
 * steps, where N is the number of names in the union of L and R.  However, if
 * the tree structures differ significantly the cost can be as high as O(N).
 */
static int
doComparison(struct SyncCompareData *data) {
    struct SyncRootStruct *root = data->root;
    struct SyncTreeWorkerHead *twL = data->twL;
    struct SyncTreeWorkerHead *twR = data->twR;

    for (;;) {
        struct SyncTreeWorkerEntry *tweR = SyncTreeWorkerTop(twR);
        if (tweR == NULL) {
            // the remote is done, so no more names to add
            return 1;
        }
        struct SyncHashCacheEntry *ceR = tweR->cacheEntry;
        if (ceR == NULL)
            return comparisonFailed(data, "bad cache entry for R", __LINE__);
        ceR->lastUsed = data->lastEnter;
        if (tweR->pos == 0 && isCovered(ceR)) {
            // short cut, nothing in R we don't have
            size_t c = tweR->count;
            tweR = SyncTreeWorkerPop(twR);
            if (tweR != NULL) {
                tweR->pos++;
                tweR->count += c;
            }
            continue;
        }
        struct SyncNodeComposite *ncR = ceR->ncR;
        if (ncR == NULL) {
            // top remote node not present, so go get it
            int nf = SyncStartNodeFetch(root, ceR, data);
            if (nf == 0) {
                // TBD: duplicate, so ignore the fetch?
                // for now, this is an error!
                return comparisonFailed(data, "node fetch duplicate?", __LINE__);
            } else if (nf > 0) {
                // node fetch started OK
            } else {
                // node fetch failed to initiate
                return comparisonFailed(data, "bad node fetch for R", __LINE__);
            }
            return 0;
        }
        if (tweR->pos >= ncR->refLen) {
            // we just went off the end of the current remote node, so pop it
            // skip over the processed element if we still have a node
            size_t c = tweR->count;
            if (c == 0) {
                // nothing was added, so this node must be covered
                setCovered(ceR);
            }
            tweR = SyncTreeWorkerPop(twR);
            if (tweR != NULL) {
                tweR->pos++;
                tweR->count += c;
            }
            continue;
        }
        struct SyncNodeElem *neR = SyncTreeWorkerGetElem(twR);
        if (neR == NULL)
            return comparisonFailed(data, "bad element for R", __LINE__);

        if (extractBuf(data->cbR, ncR, neR) < 0)
            // the remote name/hash extract failed
            return comparisonFailed(data, "bad extract for R", __LINE__);

        struct SyncTreeWorkerEntry *tweL = SyncTreeWorkerTop(twL);
        if (tweL == NULL) {
            // L is now empty, so add R
            if (neR->kind == SyncElemKind_node) {
                // to add a node R, push into it
                struct SyncHashCacheEntry *subR = cacheEntryForElem(data, ncR, neR, 1);
                if (subR == NULL || SyncTreeWorkerPush(twR) == NULL)
                    return comparisonFailed(data, "bad cache entry for R", __LINE__);
            } else {
                // R is a leaf
                addNameFromCompare(data);
            }
        } else {
            struct SyncHashCacheEntry *ceL = tweL->cacheEntry;
            //// Need to duplicate what happens for remote
            struct SyncNodeComposite *ncL = ceL->ncL;
            if (ncL == NULL) {
                // top remote node not present, so go get it
                int nf = SyncStartNodeFetch(root, ceL, data);
                if (nf == 0) {
                    // TBD: duplicate, so ignore the fetch?
                    // for now, this is an error!
                    return comparisonFailed(data, "node fetch duplicate?", __LINE__);
                } else if (nf > 0) {
                    // node fetch started OK
                } else {
                    // node fetch failed to initiate
                    return comparisonFailed(data, "bad node fetch for R", __LINE__);
                }
                return 0;
            }
            /////
            ceL->lastUsed = data->lastEnter;
            if (tweL->pos >= ncL->refLen) {
                // we just went off the end of the current local node, so pop it
                tweL = SyncTreeWorkerPop(twL);
                if (tweL != NULL) tweL->pos++;
                continue;
            }
            struct SyncNodeElem *neL = SyncTreeWorkerGetElem(twL);
            if (neL == NULL || extractBuf(data->cbL, ncL, neL) < 0) {
                // the local name/hash extract failed
                return comparisonFailed(data, "bad extract for L", __LINE__);
            }
            if (neR->kind == SyncElemKind_node) {
                // quick kill for a remote node?
                struct SyncHashCacheEntry *subR = cacheEntryForElem(data, ncR, neR, 1);
                if (subR == NULL)
                    return comparisonFailed(data, "bad element for R", __LINE__);
                if (isCovered(subR)) {
                    // nothing to add, this node is already covered
                    // note: this works even if the remote node is not present!
                    tweR->pos++;
                    continue;
                }
                if (subR->ncR == NULL) {
                    // there is a remote hash, but no node present,
                    // so push into it to force the fetch
                    if (SyncTreeWorkerPush(twR) == NULL)
                        return comparisonFailed(data, "bad push for R", __LINE__);
                    continue;
                }

                if (neL->kind == SyncElemKind_leaf) {
                    // L is a leaf, R is a node that is present
                    enum SyncCompareResult scr = SyncNodeCompareMinMax(subR->ncR, data->cbL);
                    switch (scr) {
                        case SCR_before:
                            // L < Min(R), so advance L
                            tweL->pos++;
                            break;
                        case SCR_max:
                            // L == Max(R), advance both
                            tweL->pos++;
                            tweR->pos++;
                            break;
                        default:
                            // in all other cases, dive into R
                            if (SyncTreeWorkerPush(twR) == NULL)
                                return comparisonFailed(data, "bad push for R", __LINE__);
                            break;
                    }

                } else {
                    // both L and R are nodes, test for L being present
                    struct SyncHashCacheEntry *subL = cacheEntryForElem(data, ncL, neL, 1); // HACK it's remote.
                    if (subL == NULL || subL->ncL == NULL)
                        return comparisonFailed(data, "bad cache entry for L", __LINE__);
                    // both L and R are nodes, and both are present
                    struct SyncNodeComposite *ncL = subL->ncL;
                    struct SyncNodeComposite *ncR = subR->ncR;
                    int cmp = SyncCmpNames(ncR->minName, ncL->maxName);
                    if (cmp > 0) {
                        // Min(R) > Max(L), so advance L
                        tweL->pos++;
                    } else {
                        // dive into both nodes
                        if (SyncTreeWorkerPush(twL) == NULL)
                            return comparisonFailed(data, "bad push for L", __LINE__);
                        if (SyncTreeWorkerPush(twR) == NULL)
                            return comparisonFailed(data, "bad push for R", __LINE__);
                    }
                }
            } else {
                // R is a leaf
                if (neL->kind == SyncElemKind_leaf) {
                    // both L and R are names, so the compare is simple
                    int cmp = SyncCmpNames(data->cbL, data->cbR);
                    if (cmp == 0) {
                        // L == R, so advance both
                        tweL->pos++;
                        tweR->pos++;
                    } else if (cmp < 0) {
                        // L < R, advance L
                        tweL->pos++;
                    } else {
                        // L > R, so add R
                        addNameFromCompare(data);
                    }
                } else {
                    // R is a leaf, but L is a node
                    struct SyncHashCacheEntry *subL = cacheEntryForElem(data, ncL, neL, 1);  // HACK, it's remote
                    if (subL == NULL || subL->ncL == NULL)
                        return comparisonFailed(data, "bad cache entry for L", __LINE__);
                    enum SyncCompareResult scr = SyncNodeCompareMinMax(subL->ncL, data->cbR);
                    switch (scr) {
                        case SCR_before:
                            // R < Min(L), so add R
                            addNameFromCompare(data);
                            break;
                        case SCR_max:
                            // R == Max(L), advance both
                            tweL->pos++;
                            tweR->pos++;
                            break;
                        case SCR_min:
                            // R == Min(L), advance R
                            tweR->pos++;
                            break;
                        case SCR_after:
                            // R > Max(L), advance L
                            tweL->pos++;
                            break;
                        case SCR_inside:
                            // Min(L) < R < Max(L), so dive into L
                            if (SyncTreeWorkerPush(twL) == NULL)
                                return comparisonFailed(data, "bad push for L", __LINE__);
                            break;
                        default:
                            // this is really broken
                            return comparisonFailed(data, "bad min/max compare", __LINE__);
                    }

                }
            }
        }
    }
}

static int
CompareAction(struct ccn_schedule *sched,
              void *clienth,
              struct ccn_scheduled_event *ev,
              int flags) {
    char *here = "Sync.CompareAction";
    struct SyncCompareData *data = (struct SyncCompareData *) ev->evdata;
    int res;

    if (data == NULL || data->root == NULL) {
        // invalid, not sure how we got here
        return -1;
    }
    data->lastEnter = SyncCurrentTime();
    struct SyncRootStruct *root = data->root;
    struct ccns_handle *ccns = (struct ccns_handle *)root->base->client_handle;
    int debug = root->base->debug;
    if (data->ev != ev || flags & CCN_SCHEDULE_CANCEL) {
        // orphaned or cancelled
        if (debug >= CCNL_FINE)
            SyncNoteSimple(root, here, "orphan?");
        data->ev = NULL;
        return -1;
    }

    int delay = 2000;
    switch (data->state) {
        case SyncCompare_init:
            // nothing to do, flow into next state
            if (debug >= CCNL_FINE)
                SyncNoteSimple(root, here, "init");
            data->state = SyncCompare_preload;
        case SyncCompare_preload:
            // nothing to do (yet), flow into next state
            if (debug >= CCNL_FINE)
                SyncNoteSimple(root, here, "preload");
            // For library, need to preload for Local as well as Remote.
            struct SyncHashCacheEntry *ceL = SyncHashLookup(root->ch,
                                                            data->hashL->buf,
                                                            data->hashL->length);
            if (ceL != NULL) {
                SyncTreeWorkerInit(data->twL, ceL, 1);
                res = doPreload(data, data->twL);
                if (res < 0) {
                    abortCompare(data, "doPreloadL failed");
                    return -1;
                }
                if (res == 0) {
                    // not yet preloaded
                    if (data->nodeFetchBusy > 0) {
                        // rely on SyncRemoteFetchResponse to restart us
                        data->ev = NULL;
                        delay = -1;
                    }
                    break;
                }
                // before switch to busy, reset the remote tree walker
                SyncTreeWorkerInit(data->twL, ceL, 1);
            }
            struct SyncHashCacheEntry *ceR = SyncHashLookup(root->ch,
                                                            data->hashR->buf,
                                                            data->hashR->length);
            SyncTreeWorkerInit(data->twR, ceR, 1);
            res = doPreload(data, data->twR);
            if (res < 0) {
                abortCompare(data, "doPreloadR failed");
                return -1;
            }
            if (res == 0) {
                // not yet preloaded
                if (data->nodeFetchBusy > 0) {
                    // rely on SyncRemoteFetchResponse to restart us
                    data->ev = NULL;
                    delay = -1;
                }
                break;
            }
            // before switch to busy, reset the remote tree walker
            SyncTreeWorkerInit(data->twR, ceR, 1);
            // If library indicates start at current root, skip to done and
            // reset the restart at current root flag
            if (ccns->flags & CCNS_FLAGS_SC) {
                ccns->flags &= ~CCNS_FLAGS_SC;
                data->state = SyncCompare_done;
                delay = 20;  // reschedule in a short time.
                break; 
            }
            data->state = SyncCompare_busy;
        case SyncCompare_busy:
            // come here when we are comparing the trees
            if (debug >= CCNL_FINE)
                SyncNoteSimple(root, here, "busy");
            res = doComparison(data);
            if (res < 0) {
                abortCompare(data, "doComparison failed");
                return -1;
            }
            if (data->errList != NULL) {
                // we had a load started during compare, so retreat a state
                data->state = SyncCompare_preload;
                if (debug >= CCNL_WARNING)
                    SyncNoteSimple(root, here, "retreat one state");
                break;
            }
            if (res == 0)
                // comparison not yet complete
                break;
            // either full success or failure gets here
            data->state = SyncCompare_waiting;
        case SyncCompare_waiting:
            if (debug >= CCNL_FINE)
                SyncNoteSimple(root, here, "waiting");
            data->state = SyncCompare_done;
        case SyncCompare_done: {
            // for library, when we're done, we copy the remote to the local hash
            struct SyncHashCacheEntry *ce = SyncHashLookup(root->ch,
                                                           data->hashR->buf,
                                                           data->hashR->length);
            ccn_charbuf_reset(root->currentHash);
            ccn_charbuf_append_charbuf(root->currentHash, data->hashR);
            SyncNodeIncRC(ce->ncR);
            if (ce->ncL != NULL)
                SyncNodeDecRC(ce->ncL);
            ce->ncL = ce->ncR;
            // cleanup
            int64_t now = SyncCurrentTime();
            int64_t mh = SyncDeltaTime(data->lastEnter, now);
            int64_t dt = SyncDeltaTime(data->startTime, now);
            root->priv->stats->comparesDone++;
            root->priv->stats->lastCompareMicros = dt;
            if (mh > data->maxHold) data->maxHold = mh;
            mh = (mh + 500) / 1000;
            dt = (dt + 500) / 1000;

            if (debug >= CCNL_INFO) {
                int reportStats = 1;
                char temp[64];
                snprintf(temp, sizeof(temp)-2,
                         "%d.%03d secs [%d.%03d], %d names added",
                         (int) (dt / 1000), (int) (dt % 1000),
                         (int) (mh / 1000), (int) (mh % 1000),
                         (int) data->namesAdded);
                SyncNoteSimple2(root, here, "done", temp);
                if (reportStats) {
                    struct ccn_charbuf *cb = ccn_charbuf_create();
                    // formatStats(root, cb);
                    char *str = ccn_charbuf_as_string(cb);
                    ccns_msg(root->base->client_handle, "%s, %s", here, str);
                    ccn_charbuf_destroy(&cb);
                }
            }
            destroyCompareData(data);
            return -1;
        }
        default: break;
    }
    int64_t mh = SyncDeltaTime(data->lastEnter, SyncCurrentTime());
    if (mh > data->maxHold) data->maxHold = mh;
    return delay;
}

int
SyncStartCompareAction(struct SyncRootStruct *root, struct ccn_charbuf *hashR) {
    char *here = "Sync.SyncStartCompareAction";
    struct SyncPrivate *priv = root->base->priv;
    if (root->compare != NULL
        || priv->comparesBusy >= priv->maxComparesBusy)
        return 0;

    struct ccn_charbuf *hashL = root->currentHash;
    struct SyncHashCacheEntry *ceL = NULL;

    if (hashL->length > 0) {
        // if L is not empty, check the cache entry
        ceL = SyncHashLookup(root->ch, hashL->buf, hashL->length);
        if (ceL == NULL)
            return SyncNoteFailed(root, here, "bad lookup for L", __LINE__);
    }
    struct SyncHashCacheEntry *ceR = SyncHashEnter(root->ch,
                                                   hashR->buf,
                                                   hashR->length,
                                                   SyncHashState_remote);
    if (ceR == NULL)
        return SyncNoteFailed(root, here, "bad lookup for R", __LINE__);

    int debug = root->base->debug;
    struct ccnr_handle *ccnr = root->base->client_handle;
    struct SyncCompareData *data = calloc(1, sizeof(*data));
    int64_t mark = SyncCurrentTime();
    data->startTime = mark;
    data->lastEnter = mark;
    data->lastMark = mark;
    data->lastFetchOK = mark;
    data->root = root;
    root->compare = data;
    root->namesToFetch = SyncFreeNameAccumAndNames(root->namesToFetch);
    data->twL = SyncTreeWorkerCreate(root->ch, ceL, 0);
    if (ceL != NULL) ceL->lastUsed = mark;
    data->twR = SyncTreeWorkerCreate(root->ch, ceR, 1);
    ceR->lastUsed = mark;
    data->hashL = ccn_charbuf_create();
    ccn_charbuf_append_charbuf(data->hashL, hashL);
    data->hashR = ccn_charbuf_create();
    ccn_charbuf_append_charbuf(data->hashR, hashR);

    data->cbL = ccn_charbuf_create();
    data->cbR = ccn_charbuf_create();

    data->state = SyncCompare_init;
    priv->comparesBusy++;

    kickCompare(data, NULL);

    if (debug >= CCNL_INFO) {
        char *hexL = SyncHexStr(hashL->buf, hashL->length);
        char *msgL = ((hashL->length > 0) ? hexL : "empty");
        char *hexR = SyncHexStr(hashR->buf, hashR->length);
        char *msgR = ((hashR->length > 0) ? hexR : "empty");
        ccns_msg(ccnr, "%s, root#%u, L %s, R %s",
                 here, root->rootId, msgL, msgR);
        free(hexL);
        free(hexR);
    }

    return 1;
}

static int
HeartbeatAction(struct ccn_schedule *sched,
                void *clienth,
                struct ccn_scheduled_event *ev,
                int flags) {
    char *here = "Sync.HeartbeatAction";
    struct SyncBaseStruct *base = (struct SyncBaseStruct *) ev->evdata;
    if (base == NULL || base->priv == NULL || (flags & CCN_SCHEDULE_CANCEL)) {
        // TBD: and why did this happen? (can't report it, though)
        return -1;
    }

    struct SyncPrivate *priv = base->priv;
    int64_t now = SyncCurrentTime();
    struct SyncRootStruct *root = priv->rootHead;

    while (root != NULL) {
        struct SyncCompareData *comp = root->compare;
        if (comp == NULL) {
            // only run the update when not comparing
            struct SyncHashInfoList *x = chooseRemoteHash(root);
            if (x != NULL) {
                SyncStartCompareAction(root, x->ce->hash);
            }
        } else {
            // running a compare, check for stall or excessive time since last fetch
            int64_t dt = SyncDeltaTime(comp->lastMark, now);
            if (dt > 15*1000000) {  // updateStallDelta
                // periodic stall warning
                if (base->debug >= CCNL_WARNING)
                    SyncNoteSimple(root, here, "compare stalled?");
                comp->lastMark = now;
            }
            // test for fatal stall (based on last fetch time)
            dt = SyncDeltaTime(comp->lastFetchOK, now);
            if (dt > 20*1000000) {  // compareAssumeBad
                abortCompare(comp, "no progress");
            }
        }
        // TBD: prune eldest remote roots from list
        // TBD: prune old remote node entries from cache
        root = root->next;
    }
    return priv->heartbeatMicros;
}
extern enum ccn_upcall_res
SyncInterestArrived(struct ccn_closure *selfp,
                    enum ccn_upcall_kind kind,
                    struct ccn_upcall_info *info) {
    static char *here = "Sync.SyncInterestArrived";
    struct SyncActionData *data = selfp->data;
    enum ccn_upcall_res ret = CCN_UPCALL_RESULT_OK;
    switch (kind) {
        case CCN_UPCALL_FINAL:
            data = destroyActionData(data);
            free(selfp);
            break;
        case CCN_UPCALL_INTEREST: {
            struct SyncRootStruct *root = data->root;
            if (root == NULL) break;
            struct SyncRootPrivate *rp = root->priv;
            struct SyncBaseStruct *base = root->base;
            int debug = base->debug;
            int skipToHash = data->skipToHash;
            const unsigned char *buf = info->interest_ccnb;
            struct ccn_indexbuf *comps = info->interest_comps;
            char *hexL = NULL;
            char *hexR = NULL;
            if ((info->pi->answerfrom & CCN_AOK_NEW) == 0) {
                // TBD: is this the right thing to do?
                if (debug >= CCNL_INFO)
                    SyncNoteUri(root, here, "CCN_AOK_NEW = 0", data->prefix);
                break;
            }
            switch (data->kind) {
                case SRI_Kind_None:
                    // not an active request, so ignore
                    break;
                case SRI_Kind_AdviseInt: {
                    const unsigned char *bufR = NULL;
                    size_t lenR = 0;
                    struct SyncHashCacheEntry *ceR = NULL;
                    const unsigned char *bufL = root->currentHash->buf;
                    char *who = "RootAdvise";
                    size_t lenL = root->currentHash->length;
                    ccn_name_comp_get(buf, comps, skipToHash, &bufR, &lenR);
                    if (bufR == NULL || lenR == 0) {
                        if (data->kind == SRI_Kind_FetchInt) {
                            // not well-formed, so ignore it
                            if (debug >= CCNL_SEVERE)
                                SyncNoteSimple2(root, here, who, "failed, no remote hash");
                            return ret;
                        }
                    } else hexR = SyncHexStr(bufR, lenR);

                    if (debug >= CCNL_INFO) {
                        if (hexR == NULL)
                            SyncNoteSimple2(root, here, who, "empty remote hash");
                        else SyncNoteSimple3(root, here, who, "remote hash", hexR);
                    }
                    if (data->kind == SRI_Kind_AdviseInt) {
                        // SYNC LIBRARY - no, it's not worth pulling in the exclude printer for this
                        // worth noting the remote root
                        //if (debug >= CCNL_FINER) {
                        //    ssize_t start = info->pi->offset[CCN_PI_B_Exclude];
                        //    ssize_t stop = info->pi->offset[CCN_PI_E_Exclude];
                        //    if (stop > start) {
                        //// we appear to have an exclusion
                        //        struct ccn_buf_decoder ds;
                        //        struct ccn_buf_decoder *d = &ds;
                        //         ccn_buf_decoder_start(d, buf+start, stop - start);
                        //         reportExclude(root, d);
                        //     }
                        //  }
                        if (lenR != 0) {
                            ceR = SyncHashEnter(root->ch, bufR, lenR, SyncHashState_remote);
                            int64_t lastMark = ceR->lastRemoteFetch;
                            noteRemoteHash(root, ceR, 1);
                            rp->adviseNeed = ADVISE_NEED_RESET;
                            // force any old interest to be inactive
                            if (lastMark == 0) {
                                // not entered, so we need to do a RootAdvise
                                struct SyncActionData *data = SyncFindAction(root, SRI_Kind_RootAdvise);
                                if (data != NULL) data->kind = SRI_Kind_None;
                            }
                        }
                        rp->stats->rootAdviseSeen++;
                    } else {
                        rp->stats->nodeFetchSeen++;
                    }

                    if (lenL == 0) {
                        if (debug >= CCNL_INFO)
                            SyncNoteSimple2(root, here, who, "ignored (empty local root)");
                        if (lenR == 0) {
                            // both L and R are empty, so suppress short-term thrashing
                            rp->adviseNeed = 0;
                        } else if (root->namesToAdd->len > 0) {
                            if (debug >= CCNL_FINE)
                                SyncNoteSimple2(root, here, who, "new tree needed");
                        }
                        break;
                    }
                    // SYNC LIBRARY, just return after noting the hash, don't gen response
                    break;

                    if (data->kind == SRI_Kind_AdviseInt
                        && lenR == lenL && memcmp(bufL, bufR, lenR) == 0) {
                        // hash given is same as our root hash, so ignore the request
                        if (debug >= CCNL_INFO)
                            SyncNoteSimple2(root, here, who, "ignored (same hash)");
                        //purgeOldEntries(root);
                        break;
                    }
                    break;
                }
                default:
                    // SHOULD NOT HAPPEN
                    ret = CCN_UPCALL_RESULT_ERR;
                    break;
            }
            if (hexL != NULL) free(hexL);
            if (hexR != NULL) free(hexR);
            break;
        }
        default:
            // SHOULD NOT HAPPEN
            ret = CCN_UPCALL_RESULT_ERR;
            break;
    }
    return ret;
}

int
SyncRegisterInterests(struct SyncRootStruct *root) {
    char *here = "Sync.SyncRegisterInterests";
    struct SyncBaseStruct *base = root->base;
    struct ccn *ccn = base->ccn;
    struct ccn_charbuf *prefix;
    struct ccn_closure *action;
    struct SyncActionData *data;

    if (ccn == NULL) return -1;
    int res = 0;

    prefix = constructCommandPrefix(root, SRI_Kind_AdviseInt);
    if (prefix == NULL) {
        res = SyncNoteFailed(root, here, "bad prefix", __LINE__);
        return(res);
    }
    action = calloc(1, sizeof(*action));
    data = newActionData(SRI_Kind_AdviseInt);
    data->prefix = prefix;
    data->skipToHash = SyncComponentCount(prefix);
    action->data = data;
    action->p = &SyncInterestArrived;
    res |= ccn_set_interest_filter(root->base->ccn, prefix, action);
    if (res < 0) {
        if (base->debug >= CCNL_SEVERE)
            SyncNoteUri(root, here, "ccn_set_interest_filter failed", prefix);
        data = destroyActionData(data);
    } else {
        linkActionData(root, data);
        if (base->debug >= CCNL_INFO)
            SyncNoteUri(root, here, "RootAdvise", prefix);
    }
    return(res);
}

int
SyncHandleSlice(struct SyncBaseStruct *base, struct ccn_charbuf *name) {
    return(0);
}

int
SyncStartSliceEnum(struct SyncRootStruct *root) {
    return(0);
}

int
SyncStartHeartbeat(struct SyncBaseStruct *base) {
    return(0);
}
/*
 * Functions that are reference directly from the Sync code that should
 * along with some others, be turned into methods
 */

static int
r_sync_lookup(struct ccnr_handle *ccnr,
              struct ccn_charbuf *interest,
              struct ccn_charbuf *content_ccnb)
{
    int ans = -1;
    ccns_msg(ccnr, "WARNING: r_sync_lookup should not be called in sync library");
    return(ans);
}

/**
 * Called when a content object has been constructed locally by sync
 * and needs to be committed to stable storage by the repo.
 * returns 0 for success, -1 for error.
 */

static int
r_sync_local_store(struct ccnr_handle *ccnr,
                   struct ccn_charbuf *content)
{
    int ans = -1;
    ccns_msg(ccnr, "WARNING: r_sync_local_store should not be called in sync library");
    return(ans);
}

static uintmax_t
ccns_accession_encode(struct ccnr_handle *ccnr, ccnr_accession a)
{
    return(a);
}

static ccnr_hwm
ccns_hwm_update(struct ccnr_handle *ccnr, ccnr_hwm hwm, ccnr_accession a)
{
    return(a <= hwm ? hwm : a);
}
#define extern
#define SYNCLIBRARY
#define ccnr_msg ccns_msg
#define ccnr_hwm_update ccns_hwm_update
#include <sync/IndexSorter.c>
#include <sync/SyncBase.c>
#include <sync/SyncHashCache.c>
#include <sync/SyncNode.c>
#include <sync/SyncRoot.c>
#include <sync/SyncTreeWorker.c>
#include <sync/SyncUtil.c>
#undef extern
