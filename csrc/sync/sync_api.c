/**
 * @file sync/sync_api.c
 *
 * Sync library interface.
 * Implements a library interface to the Sync protocol facilities implemented
 * by the Repository
 *
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2012-2013 Palo Alto Research Center, Inc.
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
#include <stdarg.h>
#include <string.h>
#include <sys/time.h>
#include <ccn/ccn.h>
#include <ccn/coding.h>
#include <ccn/digest.h>
#include <ccn/loglevels.h>
#include <ccn/schedule.h>
#include <ccn/sync.h>
#include <ccn/uri.h>
#include <ccn/ccn_private.h>

#include "sync_diff.h"
#include "SyncUtil.h"
#include "SyncNode.h"
#include "SyncPrivate.h"

struct ccns_slice {
    unsigned version;
    unsigned nclauses;
    struct ccn_charbuf *topo;
    struct ccn_charbuf *prefix;
    struct ccn_charbuf **clauses; // contents defined in documentation, need utils
};

struct ccns_handle {
    struct sync_plumbing *sync_plumbing;
    struct SyncBaseStruct *base;
    struct SyncRootStruct *root;
    struct ccn_scheduled_event *ev;
    struct ccns_name_closure *nc;
    struct SyncHashCacheEntry *last_ce;
    struct SyncHashCacheEntry *next_ce;
    struct SyncHashCacheEntry *pending_ce; // Of the RA we last sent
    struct SyncNameAccum *namesToAdd;
    struct SyncHashInfoList *hashSeen;
    //struct ccn_closure *registered; // registered action for RA interests
    int debug;
    struct ccn *ccn;
    struct sync_diff_fetch_data *fetch_data;
    struct sync_diff_data *diff_data;
    struct sync_update_data *update_data;
    int needUpdate;
    int ppkd_size;
    unsigned char ppkd[32]; // key digest of tracked repo, if known.
    int64_t add_accum;
    int64_t startTime;
};

/*
 * Utility routines to allocate/deallocate ccns_slice structures
 */
struct ccns_slice *
ccns_slice_create() {
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
    } else {
        ccn_name_init(s->topo);
        ccn_name_init(s->prefix);
    }
    return(s);
}
void
ccns_slice_destroy(struct ccns_slice **sp) {
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
ccns_slice_add_clause(struct ccns_slice *s, struct ccn_charbuf *c) {
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
ccns_slice_set_topo_prefix(struct ccns_slice *s,
                           struct ccn_charbuf *t,
                           struct ccn_charbuf *p) {
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
append_slice(struct ccn_charbuf *c, struct ccns_slice *s) {
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
slice_parse(struct ccns_slice *s, const unsigned char *p, size_t size) {
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
                struct ccns_slice *slice) {
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
make_scope1_template(void) {
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
                        struct ccn_upcall_info *info) {
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
write_slice(struct ccn *h,
            struct ccns_slice *slice,
            struct ccn_charbuf *name) {
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
 * @param name if non-NULL, is a pointer to a charbuf which will be filled
 *  in with the name of the slice that was written.
 * @returns 0 on success, -1 otherwise.
 */
int
ccns_write_slice(struct ccn *h,
                 struct ccns_slice *slice,
                 struct ccn_charbuf *name) {
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
ccns_delete_slice(struct ccn *h, struct ccn_charbuf *name) {
    struct ccn_charbuf *n = NULL;
    int res = 0;

    // calculate versioned and segmented name for the slice
    n = ccn_charbuf_create_n(32 + name->length);
    if (n == NULL)
        return(-1);
    res |= ccn_charbuf_append_charbuf(n, name);
    res |= ccn_create_version(h, n, CCN_V_NOW | CCN_V_REPLACE, 0, 0);
    res |= ccn_name_append_numeric(n, CCN_MARKER_SEQNUM, 0);
    if (res >= 0)
        res = write_slice(h, NULL, n);
    ccn_charbuf_destroy(&n);
    return (res);
}

/*
 * local time source for event schedule
 */
static void
gettime(const struct ccn_gettime *self, struct ccn_timeval *result) {
    struct timeval now = {0};
    gettimeofday(&now, 0);
    result->s = now.tv_sec;
    result->micros = now.tv_usec;
}

// types

enum local_flags {
    LF_NULL,
    LF_ADVISE,
    LF_NODE,
    LF_OTHER
};

struct hash_list {
    struct hash_list *next;
    struct SyncHashCacheEntry *ce;
    int64_t lastSeen;
};

// forward declarations

static int
start_interest(struct sync_diff_data *diff_data);


// utilities and stuff

static void
my_r_sync_msg(struct sync_plumbing *sd, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stdout, fmt, ap);
    va_end(ap);
    fprintf(stdout, "\n");
    fflush(stdout);
}

// extractNode parses and creates a sync tree node from an upcall info
// returns NULL if there was any kind of error
static struct SyncNodeComposite *
extractNode(struct SyncRootStruct *root, struct ccn_upcall_info *info) {
    // first, find the content
    char *here = "sync_track.extractNode";
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

static struct sync_diff_fetch_data *
find_fetch_data(struct ccns_handle *ch, struct SyncHashCacheEntry *ce) {
    struct sync_diff_fetch_data *each = ch->fetch_data;
    while (each != NULL) {
        struct sync_diff_fetch_data *next = each->next;
        if (each->hash_cache_entry == ce) return each;
        each = next;
    }
    return NULL;
}

static int
delink_fetch_data(struct ccns_handle *ch, struct sync_diff_fetch_data *fd) {
    if (fd != NULL) {
        struct sync_diff_fetch_data *each = ch->fetch_data;
        struct sync_diff_fetch_data *lag = NULL;
        while (each != NULL) {
            struct sync_diff_fetch_data *next = each->next;
            if (each == fd) {
                if (lag == NULL) ch->fetch_data = next;
                else lag->next = next;
                return 1;
            }
            lag = each;
            each = next;
        }
    }
    return 0;
}

static void
free_fetch_data(struct ccns_handle *ch, struct sync_diff_fetch_data *fd) {
    if (delink_fetch_data(ch, fd)) {
        struct ccn_closure *action = fd->action;
        if (action != NULL && action->data == fd)
            // break the link here
            action->data = NULL;
        fd->action = NULL;
        // only free the data if it is ours
        free(fd);
    }
}

static void
setCurrentHash(struct SyncRootStruct *root, struct SyncHashCacheEntry *ce) {
    struct ccn_charbuf *hash = root->currentHash;
    hash->length = 0;
    if (ce != NULL)
        ccn_charbuf_append_charbuf(hash, ce->hash);
}

static struct SyncHashCacheEntry *
chooseNextHash(struct ccns_handle *ch) {
    struct SyncHashCacheEntry *nce = ch->next_ce;
    if (nce != NULL && (nce->state & SyncHashState_covered) == 0
        && find_fetch_data(ch, nce) == NULL)
        return nce;
    struct SyncHashInfoList *each = ch->hashSeen;
    while (each != NULL) {
        struct SyncHashCacheEntry *ce = each->ce;
        if (ce != NULL && (ce->state & SyncHashState_covered) == 0
            && (nce == NULL || SyncCompareHash(ce->hash, nce->hash) > 0)
            && find_fetch_data(ch, ce) == NULL)
            return ce;
        each = each->next;
    }
    return NULL;
}

// each_round starts a new comparison or update round,
// provided that the attached sync_diff is not busy
// we reuse the sync_diff_data, but reset the comparison hashes
// if we can't start one, we wait and try again
static int
each_round(struct ccn_schedule *sched,
           void *clienth,
           struct ccn_scheduled_event *ev,
           int flags) {
    if (ev == NULL)
        // not valid
        return -1;
    struct ccns_handle *ch = ev->evdata;
    if (flags & CCN_SCHEDULE_CANCEL || ch == NULL) {
        if (ch != NULL && ch->ev == ev)
            ch->ev = NULL;
        return -1;
    }
    if (ch->needUpdate) {
        // do an update
        switch (ch->update_data->state) {
            case sync_update_state_init:
            case sync_update_state_error:
            case sync_update_state_done: {
                if (ch->namesToAdd != NULL && ch->namesToAdd->len > 0) {
                    sync_update_start(ch->update_data, ch->namesToAdd);
                } else {
                    // update not very useful
                    ch->needUpdate = 0;
                    return 1000;
                }
            }
            default:
                // we are busy right now
                break;
        }
    } else {
        // do a comparison
        struct sync_diff_data *diff_data = ch->diff_data;
        switch (diff_data->state) {
            case sync_diff_state_init:
            case sync_diff_state_error:
            case sync_diff_state_done: {
                // there is no comparison active
                struct SyncHashCacheEntry *ce = ch->next_ce;
                if (ce != NULL
                    && ((ce->state & SyncHashState_covered) != 0))
                    ce = chooseNextHash(ch);
                if (ce != NULL
                    && ((ce->state & SyncHashState_covered) == 0)
                    && ce != ch->last_ce) {
                    // worth trying
                    ch->next_ce = ce;
                    if (ch->last_ce != NULL)
                        diff_data->hashX = ch->last_ce->hash;
                    if (ch->next_ce != NULL)
                        diff_data->hashY = ch->next_ce->hash;
                    sync_diff_start(diff_data);
                }
                else {
                    // Time to start asking for a new root
                    start_interest(diff_data);
                }
            }
            default:
                // we are busy right now
                break;
        }
    }
    return 500000; // 0.5 seconds
}

// start_round schedules a new comparison round,
// cancelling any previously scheduled round
static void
start_round(struct ccns_handle *ch, int micros) {
    struct ccn_scheduled_event *ev = ch->ev;
    if (ev != NULL && ev->action != NULL && ev->evdata == ch)
        // get rid of the existing event
        ccn_schedule_cancel(ch->sync_plumbing->sched, ev);
    if (ch->ev != NULL) abort();
    // start a new event
    ch->ev = ccn_schedule_event(ch->sync_plumbing->sched,
                                micros,
                                each_round,
                                ch,
                                0);
    return;
}

// my_response is used to handle a reply
static enum ccn_upcall_res
my_response(struct ccn_closure *selfp,
            enum ccn_upcall_kind kind,
            struct ccn_upcall_info *info) {
    static char *here = "sync_track.my_response";
    enum ccn_upcall_res ret = CCN_UPCALL_RESULT_ERR;
    switch (kind) {
        case CCN_UPCALL_FINAL:
            free(selfp);
            ret = CCN_UPCALL_RESULT_OK;
            break;
        case CCN_UPCALL_CONTENT_UNVERIFIED:
            ret = CCN_UPCALL_RESULT_VERIFY;
            break;
        case CCN_UPCALL_CONTENT_KEYMISSING:
            ret = CCN_UPCALL_RESULT_FETCHKEY;
            break;
        case CCN_UPCALL_INTEREST_TIMED_OUT: {
            struct sync_diff_fetch_data *fd = selfp->data;
            enum local_flags flags = selfp->intdata;
            if (fd == NULL) break;
            struct sync_diff_data *diff_data = fd->diff_data;
            if (diff_data == NULL) break;
            struct ccns_handle *ch = diff_data->client_data;
            if (flags == LF_ADVISE) {
                if (ch->pending_ce != NULL && ch->pending_ce == ch->next_ce) {
                    ret = CCN_UPCALL_RESULT_REEXPRESS;
                    break;
                }
                ch->pending_ce = NULL;
            }
            free_fetch_data(ch, fd);
            ret = CCN_UPCALL_RESULT_OK;
            break;
        }
        case CCN_UPCALL_CONTENT_RAW:
        case CCN_UPCALL_CONTENT: {
            struct sync_diff_fetch_data *fd = selfp->data;
            enum local_flags flags = selfp->intdata;
            if (fd == NULL) break;
            struct sync_diff_data *diff_data = fd->diff_data;
            if (diff_data == NULL) break;
            struct SyncRootStruct *root = diff_data->root;
            if (root == NULL) break;
            struct ccns_handle *ch = diff_data->client_data;
            struct SyncNodeComposite *nc = extractNode(root, info);
            if (ch->debug >= CCNL_FINE) {
                char fs[1024];
                int pos = 0;
                switch (flags) {
                    case LF_NULL: 
                        pos += snprintf(fs+pos, sizeof(fs)-pos, "null");
                        break;
                    case LF_ADVISE:
                        pos += snprintf(fs+pos, sizeof(fs)-pos, "advise");
                        break;
                    case LF_NODE:
                        pos += snprintf(fs+pos, sizeof(fs)-pos, "node");
                        break;
                    default: 
                        pos += snprintf(fs+pos, sizeof(fs)-pos, "??%d", flags);
                        break;
                }
                if (nc != NULL)
                    pos += snprintf(fs+pos, sizeof(fs)-pos, ", nc OK");
                struct ccn_charbuf *nm = SyncNameForIndexbuf(info->content_ccnb,
                                                             info->content_comps);
                struct ccn_charbuf *uri = SyncUriForName(nm);
                pos += snprintf(fs+pos, sizeof(fs)-pos, ", %s", ccn_charbuf_as_string(uri));
                SyncNoteSimple(diff_data->root, here, fs);
                ccn_charbuf_destroy(&nm);
                ccn_charbuf_destroy(&uri);
            }
            if (nc != NULL) {
                // the node exists, so store it
                // TBD: check the hash?
                struct ccns_handle *ch = diff_data->client_data;
                struct SyncHashCacheEntry *ce = SyncHashEnter(root->ch,
                                                              nc->hash->buf, nc->hash->length,
                                                              SyncHashState_remote);
                if (flags == LF_ADVISE) {
                    ch->pending_ce = NULL;
                    ch->hashSeen = SyncNoteHash(ch->hashSeen, ce);
                    if (ch->next_ce == NULL)
                        // have to have an initial place to start
                        ch->next_ce = ce;
                    if (ch->ppkd_size == 0) {
                        // Should belong the repo we want to track.
                        // Remember it so we work from the same repo.
                        const unsigned char *blob = NULL;
                        size_t blob_size = 0;
                        int res;
                        res = ccn_ref_tagged_BLOB(CCN_DTAG_PublisherPublicKeyDigest, info->content_ccnb,
                                                  info->pco->offset[CCN_PCO_B_PublisherPublicKeyDigest],
                                                  info->pco->offset[CCN_PCO_E_PublisherPublicKeyDigest],
                                                  &blob, &blob_size);
                        if (res >= 0 && blob_size <= sizeof(ch->ppkd)) {
                            memcpy(ch->ppkd, blob, blob_size);
                            ch->ppkd_size = blob_size;
                        }
                    }
                }
                if (ce->ncR == NULL) {
                    // store the node
                    ce->ncR = nc;
                    SyncNodeIncRC(nc);
                } else {
                    // flush the node
                    SyncNodeDecRC(nc);
                    nc = NULL;
                }
                if (flags != LF_NULL) {
                    // from start_interest
                    start_round(ch, 10);
                } else {
                    // from sync_diff
                    sync_diff_note_node(diff_data, ce);
                }
                ret = CCN_UPCALL_RESULT_OK;
            }
            free_fetch_data(ch, fd);
            break;
        default:
            // SHOULD NOT HAPPEN
            break;
        }
    }
    return ret;
}

/*
 * Make a template for our root advise interest.  We only want to track
 * stuff from a local repo, so use a scope 1 interest.  Use an exclude so that
 * we only see forward progress.  If c is not NULL, it holds the current hash.
 */
static struct ccn_charbuf *
make_ra_template(struct ccns_handle *ch, struct ccn_charbuf *c)
{
    struct ccn_charbuf *templ = NULL;
    templ = ccn_charbuf_create();
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccnb_element_begin(templ, CCN_DTAG_Name);
    ccnb_element_end(templ); /* </Name> */
    if (ch->ppkd_size != 0)
        ccnb_append_tagged_blob(templ, CCN_DTAG_PublisherPublicKeyDigest,
                                ch->ppkd, ch->ppkd_size);
    if (c != NULL) {
        ccnb_element_begin(templ, CCN_DTAG_Exclude);
        ccnb_tagged_putf(templ, CCN_DTAG_Any, "");
        ccnb_append_tagged_blob(templ, CCN_DTAG_Component, c->buf, c->length);
        ccnb_element_end(templ); /* </Exclude> */
    }
    if (ch->ppkd_size == 0)
        ccnb_tagged_putf(templ, CCN_DTAG_AnswerOriginKind, "%u", CCN_AOK_NEW);
    ccnb_tagged_putf(templ, CCN_DTAG_Scope, "%u", 1);
    // Repo sync keeps a PIT, so a long lifetime is fine.
    // Try to match our polling interval.
    ccnb_append_tagged_binary_number(templ, CCN_DTAG_InterestLifetime,
                                     25 * 4096);
    ccnb_element_end(templ); /* </Interest> */
    return(templ);
}

static int
start_interest(struct sync_diff_data *diff_data) {
    static char *here = "sync_track.start_interest";
    struct SyncRootStruct *root = diff_data->root;
    struct SyncBaseStruct *base = root->base;
    struct ccns_handle *ch = diff_data->client_data;
    struct SyncHashCacheEntry *ce = ch->next_ce;
    struct ccn_charbuf *prefix = NULL;
    int res = 0;
    struct ccn *ccn = base->sd->ccn;
    if (ccn == NULL)
        return SyncNoteFailed(root, here, "bad ccn handle", __LINE__);
    if (ce != NULL && ce == ch->pending_ce) {
        /* We already have a pending interest */
        return(0);
    }
    ch->pending_ce = NULL;
    prefix = SyncCopyName(diff_data->root->topoPrefix);
    res |= ccn_name_append_str(prefix, "\xC1.S.ra");
    res |= ccn_name_append(prefix, root->sliceHash->buf, root->sliceHash->length);
    if (ce != NULL) {
        // append the best component seen
        res |= ccn_name_append(prefix, ce->hash->buf, ce->hash->length);
    } else {
        // append an empty component
        res |= ccn_name_append(prefix, "", 0);
    }
    struct ccn_charbuf *template = make_ra_template(ch, ce == NULL ? NULL : ce->hash);
    struct ccn_closure *action = calloc(1, sizeof(*action));
    struct sync_diff_fetch_data *fetch_data = calloc(1, sizeof(*fetch_data));
    fetch_data->diff_data = diff_data;
    fetch_data->action = action;
    fetch_data->startTime = SyncCurrentTime();
    // note: no ce available yet
    action->data = fetch_data;
    action->intdata = LF_ADVISE;
    action->p = &my_response;
    fetch_data->next = ch->fetch_data;
    ch->fetch_data = fetch_data;
    res |= ccn_express_interest(ccn, prefix, action, template);
    ccn_charbuf_destroy(&template);
    if (ch->debug >= CCNL_FINE) {
        SyncNoteUri(diff_data->root, here, "start_interest", prefix);
    }
    ccn_charbuf_destroy(&prefix);
    if (res < 0) {
        SyncNoteFailed(root, here, "ccn_express_interest failed", __LINE__);
        // return the resources, must free fd first!
        free_fetch_data(ch, fetch_data);
        free(action);
        return -1;
    }
    ch->pending_ce = ce;
    return 1;
}

static int
my_get(struct sync_diff_get_closure *gc,
       struct sync_diff_fetch_data *fd) {
    char *here = "sync_track.my_get";
    struct sync_diff_data *diff_data = gc->diff_data;
    struct ccns_handle *ch = diff_data->client_data;
    struct SyncRootStruct *root = diff_data->root;
    struct SyncBaseStruct *base = root->base;
    struct SyncHashCacheEntry *ce = fd->hash_cache_entry;
    int res = 0;
    struct ccn *ccn = base->sd->ccn;
    if (ccn == NULL)
        return SyncNoteFailed(root, here, "bad ccn handle", __LINE__);
    if (ce == NULL)
        return SyncNoteFailed(root, here, "bad cache entry", __LINE__);
    // first, check for existing fetch of same hash
    struct ccn_charbuf *hash = ce->hash;
    struct ccn_charbuf *name = SyncCopyName(diff_data->root->topoPrefix);
    ccn_name_append_str(name, "\xC1.S.nf");
    res |= ccn_name_append(name, root->sliceHash->buf, root->sliceHash->length);
    if (hash == NULL || hash->length == 0)
        res |= ccn_name_append(name, "", 0);
    else
        res |= ccn_name_append(name, ce->hash->buf, ce->hash->length);
    if (ch->debug >= CCNL_FINE) {
        SyncNoteUri(diff_data->root, here, "starting", name);
    }
    // note, this fd belongs to sync_diff, not us
    struct ccn_closure *action = calloc(1, sizeof(*action));
    action->data = fd;
    action->p = &my_response;
    fd->action = action;
    
    struct ccn_charbuf *template = SyncGenInterest(NULL,
                                                   root->priv->syncScope,
                                                   base->priv->fetchLifetime,
                                                   -1, 1, NULL);
    
    res = ccn_express_interest(ccn, name, action, template);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&template);
    if (res < 0) {
        SyncNoteFailed(root, here, "ccn_express_interest failed", __LINE__);
        free(action);
        return -1;
    }
    return 1;
}

// my_add is called when sync_diff discovers a new name
static int
my_add(struct sync_diff_add_closure *ac, struct ccn_charbuf *name) {
    char *here = "sync_track.my_add";
    struct sync_diff_data *diff_data = ac->diff_data;
    struct ccns_handle *ch = diff_data->client_data;
    if (name == NULL) {
        // end of comparison, so fire off another round
        struct SyncRootStruct *root = diff_data->root;
        // struct ccn_charbuf *hash = ch->next_ce->hash;
        struct SyncHashCacheEntry *ce = ch->next_ce;
        int delay = 1000000;
        if (ch->debug >= CCNL_INFO) {
            char temp[1024];
            int pos = 0;
            ch->add_accum += diff_data->namesAdded;
            pos += snprintf(temp+pos, sizeof(temp)-pos, "added %jd, accum %jd",
                            (intmax_t) diff_data->namesAdded, (intmax_t) ch->add_accum);
            SyncNoteSimple(diff_data->root, here, temp);
        }
        if (diff_data->state == sync_diff_state_done) {
            // successful difference, so next_ce is covered
            ce->state |= SyncHashState_covered;
            delay = 10000;
            if (ch->last_ce == NULL) {
                // first time through, just accept the new entry
                ch->last_ce = ce;
                setCurrentHash(root, ce);
                ch->update_data->ceStart = ce;
            } else if (ch->namesToAdd != NULL && ch->namesToAdd->len > 0) {
                // need to update the entry
                ch->needUpdate = 1;
                ch->last_ce = ce;
                ch->update_data->ceStart = ce;
                delay = 1000;
            } else {
                // the last guess was not so good for the max, so revert
                ce = ch->last_ce;
                ch->next_ce = ce;
            }
        }
        start_round(ch, delay);
    } else {
        // accumulate the names
        if (ch->namesToAdd == NULL) {
            ch->namesToAdd = SyncAllocNameAccum(4);
        }
        SyncNameAccumAppend(ch->namesToAdd, SyncCopyName(name), 0);
        if (ch->debug >= CCNL_INFO)
            SyncNoteUri(diff_data->root, here, "adding", name);
        if (ch->nc != NULL) {
            // callback per name
            struct ccn_charbuf *lhash = ((ch->last_ce != NULL)
                                         ? ch->last_ce->hash : NULL);
            struct ccn_charbuf *rhash = ((ch->next_ce != NULL)
                                         ? ch->next_ce->hash : NULL);
            int res = ch->nc->callback(ch->nc, lhash, rhash, name);
            if (res < 0) {
                // stop the comparison here
                // TBD: anything else to do?
                return -1;
            }
        }
    }
    return 0;
}

static int
note_update_done(struct sync_done_closure *dc) {
    struct ccns_handle *ch = dc->data;
    struct sync_update_data *ud = dc->update_data;
    if (ch != NULL && ch->update_data == ud && ud != NULL && ud->done_closure == dc) {
        // passes sanity check
        static char *here = "sync_track.note_update_done";
        if (ud->ceStop != ud->ceStart && ud->ceStop != NULL) {
            // we have a new hash that is better
            setCurrentHash(ud->root, ud->ceStop);
            ud->ceStart = ud->ceStop;
            if (ch->debug >= CCNL_FINE)
                SyncNoteSimple(ud->root, here, "new hash set");
        } else {
            if (ch->debug >= CCNL_FINE)
                SyncNoteSimple(ud->root, here, "no new hash");
        }
        ch->needUpdate = 0;
        return 1;
    }
    return -1;
}

// the only client routine we might need is the logger
// there is no Repo in this application
static struct sync_plumbing_client_methods client_methods = {
    my_r_sync_msg, NULL, NULL, NULL, NULL, NULL
};

struct ccns_handle *
ccns_open(struct ccn *h,
          struct ccns_slice *slice,
          struct ccns_name_closure *nc,
          struct ccn_charbuf *rhash,
          struct ccn_charbuf *pname) {
    struct ccns_handle *ch = calloc(1, sizeof(*ch));
    struct SyncBaseStruct *base = NULL;
    struct SyncRootStruct *root = NULL;
    struct sync_plumbing *sync_plumbing = NULL;

    if (nc == NULL || nc->callback == NULL) return NULL;
    
    sync_plumbing = calloc(1, sizeof(*sync_plumbing));
    sync_plumbing->client_methods = &client_methods;
    sync_plumbing->ccn = h;
    sync_plumbing->sched = ccn_get_schedule(h);
    if (sync_plumbing->sched == NULL) {
        struct ccn_schedule *schedule;
        struct ccn_gettime *timer = calloc(1, sizeof(*timer));
        timer->descr[0]='S';
        timer->micros_per_base = 1000000;
        timer->gettime = &gettime;
        timer->data = h;
        schedule = ccn_schedule_create(h, timer);
        ccn_set_schedule(h, schedule);
        sync_plumbing->sched = schedule;
    }
    ch->sync_plumbing = sync_plumbing;
    ch->nc = nc;
    nc->ccns = ch;
    ch->ccn = h;
    
    // gen the closure for diff data
    struct sync_diff_data *diff_data = calloc(1, sizeof(*diff_data));
    struct sync_diff_get_closure *get_closure = calloc(1, sizeof(*get_closure));
    struct sync_diff_add_closure *add_closure = calloc(1, sizeof(*add_closure));
    diff_data->add_closure = add_closure;
    add_closure->diff_data = diff_data;
    add_closure->add = my_add;
    add_closure->data = ch;
    diff_data->get_closure = get_closure;
    get_closure->diff_data = diff_data;
    get_closure->get = my_get;
    get_closure->data = ch;
    
    diff_data->hashX = NULL;
    diff_data->hashY = NULL;
    diff_data->client_data = ch;
    ch->diff_data = diff_data;
    
    // gen the closure for update data
    struct sync_done_closure *done_closure = calloc(1, sizeof(*done_closure));
    struct sync_update_data *update_data = calloc(1, sizeof(*update_data));
    update_data->done_closure = done_closure;
    update_data->done_closure->done = note_update_done;
    update_data->done_closure->update_data = update_data;
    update_data->done_closure->data = ch;
    update_data->client_data = ch;
    ch->update_data = update_data;
    
    base = SyncNewBase(sync_plumbing);
    ch->base = base;
    struct sync_plumbing_sync_methods *sync_methods = ch->sync_plumbing->sync_methods;
    if (sync_methods!= NULL && sync_methods->sync_start != NULL) {
        // read the initial options, start life for the base
        sync_methods->sync_start(ch->sync_plumbing, NULL); 
    }
    
    // make the debug levels agree
    int debug = base->debug; // TBD: how to let client set this?
    if (debug < CCNL_WARNING) debug = CCNL_WARNING;
    base->debug = debug;
    ch->debug = debug;
    root = SyncAddRoot(base, base->priv->syncScope,
                       slice->topo, slice->prefix, NULL);
    ch->root = root;
    diff_data->root = root;
    update_data->root = root;
    start_round(ch, 10);
    return ch;
}

void
ccns_close(struct ccns_handle **sh,
           struct ccn_charbuf *rhash,
           struct ccn_charbuf *pname) {
    // Use this to shut down a ccns_handle and return the resources
    // This should work any legal state!
    // TBD: fill in pname argument
    if (sh != NULL) {
        struct ccns_handle *ch = *sh;
        *sh = NULL;
        if (ch != NULL) {
            struct SyncRootStruct *root = ch->root;
            
            struct ccn_closure *registered = NULL; //ch->registered;
            if (registered != NULL) {
                // break the link, remove this particular registration
                registered->data = NULL;
                ccn_set_interest_filter_with_flags(ch->sync_plumbing->ccn,
                                                   root->topoPrefix,
                                                   registered,
                                                   0);
            }
            // cancel my looping event
            if (ch->ev != NULL) {
                ccn_schedule_cancel(ch->sync_plumbing->sched, ch->ev);
            }
            // stop any differencing
            struct sync_diff_data *diff_data = ch->diff_data;
            if (diff_data != NULL) {
                // no more differencing
                ch->diff_data = NULL;
                free(diff_data->add_closure);
                diff_data->add_closure = NULL;
                free(diff_data->get_closure);
                diff_data->get_closure = NULL;
                sync_diff_stop(diff_data);
                free(diff_data);
            }
            // stop any updating
            struct sync_update_data *ud = ch->update_data;
            if (ud != NULL) {
                ch->update_data = NULL;
                free(ud->done_closure);
                ud->done_closure = NULL;
                sync_update_stop(ud);
                free(ud);
            }
            // stop any fetching
            while (ch->fetch_data != NULL) {
                free_fetch_data(ch, ch->fetch_data);
            }
            
            if (rhash != NULL) {
                // save the current root hash
                rhash->length = 0;
                if (root->currentHash != NULL)
                    ccn_charbuf_append_charbuf(rhash, root->currentHash);
            }
            SyncFreeNameAccumAndNames(ch->namesToAdd);
            // get rid of the root
            ch->root = NULL;
            SyncRemRoot(root);
            // XXX: what about the ch->hashSeen?
            // get rid of the base
            if (ch->base != NULL) {
                struct sync_plumbing_sync_methods *sm = ch->sync_plumbing->sync_methods;
                ch->base = NULL;
                if (sm != NULL && sm->sync_stop != NULL) {
                    sm->sync_stop(ch->sync_plumbing, NULL); 
                }
            }
            free(ch->sync_plumbing);
            free(ch);
            
        }
    }
}
