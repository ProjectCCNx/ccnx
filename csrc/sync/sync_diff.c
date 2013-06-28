/**
 * @file sync/sync_diff.c
 *
 * Part of CCNx Sync.
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
#include <string.h>
#include <sys/time.h>
#include <ccn/ccn.h>
#include <ccn/coding.h>
#include <ccn/digest.h>
#include <ccn/schedule.h>
#include <ccn/sync.h>
#include <ccn/uri.h>

#include "IndexSorter.h"
#include "SyncNode.h"
#include "SyncPrivate.h"
#include "SyncTreeWorker.h"

#include "sync_plumbing.h"
#include "sync_diff.h"

static int nodeSplitTrigger = 4000;     // in bytes, triggers node split
static int hashSplitTrigger = 17;       // trigger for splitting based on hash (n/255)
static int shortDelayMicros = 1000;     // short delay for quick reschedule
static int namesYieldInc = 100;         // number of names to inc between yield tests
static int namesYieldMicros = 20*1000;  // number of micros to use as yield trigger

static void
setCovered(struct SyncHashCacheEntry *ce) {
    if (ce->state & SyncHashState_covered) {
        // nothing to do, already covered
    } else if (ce->state & SyncHashState_remote) {
        // only set this bit if a remote hash has been entered
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

static struct sync_diff_fetch_data *
allocNodeFetch(struct sync_diff_data *sdd, struct SyncHashCacheEntry *ce) {
    struct sync_diff_fetch_data *fd = calloc(1, sizeof(*fd));
    fd->diff_data = sdd;
    fd->hash_cache_entry = ce;
    return fd;
}

// addNodeFetch adds the tracking info for a fetch of a cache entry
// the client must ensure that the fetch is in progress
// duplicate fetches are detected, however (and ignored)
// returns NULL if duplicate, otherwise returns the tracking sync_diff_fetch_data
static struct sync_diff_fetch_data *
addNodeFetch(struct sync_diff_data *sdd,
             struct SyncHashCacheEntry *ce,
             enum sync_diff_side side) {
    struct sync_diff_fetch_data *fd = sdd->fetchQ;
    struct sync_diff_fetch_data *lag = NULL;
    // check for the entry already being present
    while (fd != NULL) {
        struct sync_diff_fetch_data *next = fd->next;
        if (ce == fd->hash_cache_entry) return NULL;
        lag = fd;
        fd = next;
    }
    // ce not in the fetchQ, so add it
    fd = allocNodeFetch(sdd, ce);
    if (lag == NULL) sdd->fetchQ = fd;
    else lag->next = fd;
    sdd->nodeFetchBusy++;
    ce->state |= SyncHashState_fetching;
    fd->startTime = SyncCurrentTime();
    fd->side = side;
    return fd;
}

// remNodeFetch removes the tracking data for a cache entry
// returns NULL if there was no sync_diff_fetch_data found,
// returns the sync_diff_fetch_data if it was found (and removed)
static struct sync_diff_fetch_data *
remNodeFetch(struct sync_diff_data *sdd, struct SyncHashCacheEntry *ce) {
    if (ce != NULL) {
        struct sync_diff_fetch_data *fd = sdd->fetchQ;
        struct sync_diff_fetch_data *lag = NULL;
        while (fd != NULL) {
            struct sync_diff_fetch_data *next = fd->next;
            if (fd->hash_cache_entry == ce) {
                if (lag == NULL) sdd->fetchQ = next;
                else lag->next = next;
                fd->next = NULL;
                sdd->nodeFetchBusy--;
                return fd;
            }
            lag = fd;
            fd = next;
        }
    }
    return NULL;
}

static int
formatCacheEntry(struct SyncRootStruct *root, char *dst, int lim,
               char *prefix, struct SyncHashCacheEntry *ce) {
    int n = 0;
    if (ce == NULL) n = snprintf(dst, lim, "%shash#null", prefix);
    else n = snprintf(dst, lim, "%shash#%08x", prefix, (uint32_t) ce->small);
    return n;
}

static void
showCacheEntry1(struct SyncRootStruct *root, char *here, char *msg,
                struct SyncHashCacheEntry *ce) {
    char temp[1024];
    formatCacheEntry(root, temp, sizeof(temp), "", ce);
    SyncNoteSimple2(root, here, msg, temp);
}

static void
showCacheEntry2(struct SyncRootStruct *root, char *here, char *msg,
                struct SyncHashCacheEntry *ce1, struct SyncHashCacheEntry *ce2) {
    char temp[1024];
    int n = formatCacheEntry(root, temp, sizeof(temp), "", ce1);
    formatCacheEntry(root, temp+n, sizeof(temp)-n, ", ", ce2);
    SyncNoteSimple2(root, here, msg, temp);
}

// forward declaration
static int
compareAction(struct ccn_schedule *sched,
              void *clienth,
              struct ccn_scheduled_event *ev,
              int flags);

static int
updateAction(struct ccn_schedule *sched,
             void *clienth,
             struct ccn_scheduled_event *ev,
             int flags);

static void
freeFetchData(struct sync_diff_fetch_data *fd) {
    struct ccn_closure *action = fd->action;
    if (action != NULL && action->data == fd) {
        // don't follow the link to something that is gone
        action->data = NULL;
    }
    free(fd);
}

static void
resetDiffData(struct sync_diff_data *sdd) {
    if (sdd == NULL) return;
    struct SyncRootStruct *root = sdd->root;
    if (root == NULL) return;
    struct sync_diff_fetch_data *fd = sdd->fetchQ;
    sdd->fetchQ = NULL;
    struct ccn_scheduled_event *ev = sdd->ev;
    sdd->ev = NULL;
    ccn_charbuf_destroy(&sdd->cbX);
    ccn_charbuf_destroy(&sdd->cbY);
    while (fd != NULL) {
        struct sync_diff_fetch_data *lag = fd;
        fd = fd->next;
        freeFetchData(lag);
    }
    sdd->twX = SyncTreeWorkerFree(sdd->twX);
    sdd->twY = SyncTreeWorkerFree(sdd->twY);
    if (ev != NULL && ev->evdata == sdd) {
        ccn_schedule_cancel(root->base->sd->sched, ev);
    }
}

static void
resetUpdateData(struct sync_update_data *ud) {
    if (ud == NULL) return;
    struct SyncRootStruct *root = ud->root;
    if (root == NULL) return;
    if (ud->cb != NULL)
        ccn_charbuf_destroy(&ud->cb);
    ud->adding = SyncFreeNameAccumAndNames(ud->adding);
    ud->names = SyncFreeNameAccumAndNames(ud->names);
    ud->nodes = SyncFreeNodeAccum(ud->nodes);
    ud->tw = SyncTreeWorkerFree(ud->tw);
    struct ccn_scheduled_event *ev = ud->ev;
    if (ev != NULL && ev->evdata == ud) {
        ccn_schedule_cancel(root->base->sd->sched, ev);
    }
}

static int
abortCompare(struct sync_diff_data *sdd, char *why) {
    // this compare failed due to a node fetch or content fetch failure
    // we could get repeated failures if we try the same remote node,
    // so remove it from the seen remote nodes, then destroy the compare data
    if (sdd == NULL) return -1;
    struct SyncRootStruct *root = sdd->root;
    if (root != NULL) {
        if (root->base->debug >= CCNL_WARNING)
            SyncNoteSimple(root, "Sync.compare", why);
    }
    sdd->ev = NULL;
    sdd->state = sync_diff_state_error;
    if (sdd->add_closure != NULL && sdd->add_closure->add != NULL)
        // give the client a last shot at the data
        sdd->add_closure->add(sdd->add_closure, NULL);
    resetDiffData(sdd);
    return -1;
}

static int
comparisonFailed(struct sync_diff_data *sdd, char *why, int line) {
    SyncNoteFailed(sdd->root, "Sync.compare", why, line);
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
entryForHash(struct SyncRootStruct *root, struct ccn_charbuf *hash) {
    struct SyncHashCacheEntry *ce = NULL;
    if (hash != NULL && hash->length > 0)
        ce = SyncHashLookup(root->ch, hash->buf, hash->length);
    return ce;
}

static void
initWorkerFromHash(struct SyncRootStruct *root,
                   struct SyncTreeWorkerHead *tw,
                   struct ccn_charbuf *hash) {
    struct SyncHashCacheEntry *ce = entryForHash(root, hash);
    SyncTreeWorkerInit(tw, ce);
}

static struct SyncHashCacheEntry *
cacheEntryForElem(struct sync_diff_data *sdd,
                  struct SyncNodeComposite *nc,
                  struct SyncNodeElem *ne) {
    char *here = "Sync.cacheEntryForElem";
    if (ne->kind == SyncElemKind_leaf) return NULL;
    struct SyncRootStruct *root = sdd->root;
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
    struct SyncHashCacheEntry *ce = SyncHashLookup(root->ch, xp, xs);
    if (ce == NULL) {
        ce = SyncHashEnter(root->ch, xp, xs, SyncHashState_null);
        if (ce == NULL) {
            // and why did this fail?
            SyncNoteFailed(root, here, "bad enter", __LINE__);
            return ce;
        }
    }
    if (ce->ncL != NULL)
        ce->state |= SyncHashState_local;
    if (ce->ncR != NULL) {
        ce->state |= SyncHashState_remote;
        if (ce->ncL != NULL)
            ce->state |= SyncHashState_covered;
    }
    ce->lastUsed = sdd->lastEnter;
    return ce;
}

static void
kickCompare(struct sync_diff_data *sdd, int micros) {
    // we need to restart compareAction
    struct SyncRootStruct *root = sdd->root;
    struct SyncBaseStruct *base = root->base;
    struct ccn_scheduled_event *ev = sdd->ev;
    if (ev != NULL && ev->evdata == sdd) {
        // this one may wait too long, kick it now!
        ccn_schedule_cancel(base->sd->sched, ev);
    }
    sdd->ev = ccn_schedule_event(base->sd->sched,
                                 micros,
                                 compareAction,
                                 sdd,
                                 0);
    return;
}

static void
kickUpdate(struct sync_update_data *ud, int micros) {
    // we need to restart compareAction
    struct SyncRootStruct *root = ud->root;
    struct SyncBaseStruct *base = root->base;
    struct ccn_scheduled_event *ev = ud->ev;
    if (ev != NULL && ev->evdata == ud) {
        // this one may wait too long, kick it now!
        ccn_schedule_cancel(base->sd->sched, ev);
    }
    ud->ev = ccn_schedule_event(base->sd->sched,
                                micros,
                                updateAction,
                                ud,
                                0);
    return;
}

static struct ccn_charbuf *
constructCommandPrefix(struct SyncRootStruct *root,
                       struct ccn_charbuf *hash,
                       char *cmd) {
    struct ccn_charbuf *prefix = ccn_charbuf_create();
    int res = 0;
    ccn_name_init(prefix);
    if (root->topoPrefix != NULL && root->topoPrefix->length > 0) {
        // the topo (if any) always comes first
        res |= SyncAppendAllComponents(prefix, root->topoPrefix);
    }
    // the command comes after the topo
    ccn_name_append_str(prefix, cmd);
    if (hash != NULL)
        res |= ccn_name_append(prefix, hash->buf, hash->length);
    
    if (res < 0) {
        ccn_charbuf_destroy(&prefix);
    }
    return prefix;
}


/*
 * start_node_fetch initiates a node fetch for a cache entry
 * using the client-supplied lookup and get methods
 * the lookup method is tried first, and if it wins then
 * the get method is not called, and ce->state |= SyncHashState_local
 * if lookup is not present or does not find the entry then sdd->get is called
 * to initiate a "remote" fetch, which does not have an immediate response
 * (sync_diff_note_node is called when the node shows up)
 * returns < 0 for failure, 0 for node already present (or being fetched),
 * and > 0 for success
 */
static int
start_node_fetch(struct sync_diff_data *sdd,
                 struct SyncHashCacheEntry *ce,
                 enum sync_diff_side side) {
    struct SyncRootStruct *root = sdd->root;
    struct sync_diff_get_closure *get = sdd->get_closure;
    if (ce == NULL)
        // not supposed to happen, bad call
        return -1;
    if (ce->state & SyncHashState_fetching)
        // already busy
        return 0;
    if (ce->ncL != NULL || ce->ncR != NULL)
        // we already have the node
        return 0;
    struct sync_plumbing *sd = root->base->sd;
    struct sync_plumbing_client_methods *sdcm = sd->client_methods;
    if (sdcm != NULL && sdcm->r_sync_lookup != NULL) {
        // we have a means for local lookup (like a Repo)
        struct SyncNodeComposite *nc = NULL;
        struct ccn_charbuf *content = ccn_charbuf_create();
        struct ccn_charbuf *name = constructCommandPrefix(root, root->sliceHash, "\xC1.S.nf");
        int res = 0;
        if (ce != NULL) {
            // append the best component seen
            res |= ccn_name_append(name, ce->hash->buf, ce->hash->length);
        }
        res |= sdcm->r_sync_lookup(sd, name, content);
        ccn_charbuf_destroy(&name);
        if (res > 0) {
            struct ccn_parsed_ContentObject pcos;
            struct ccn_parsed_ContentObject *pco = &pcos;
            res = ccn_parse_ContentObject(content->buf, content->length, pco, NULL);
            if (res >= 0) {
                nc = SyncNodeFromParsedObject(root, content->buf, pco);
                if (nc != NULL) {
                    // found it!
                    SyncNodeIncRC(nc);
                    ce->ncL = nc;
                    ce->state |= SyncHashState_local;
                    if (ce->state & SyncHashState_remote)
                        ce->state |= SyncHashState_covered;
                    return 0;
                }
            }
        }
    }
    // if there is a get method supplied, call it
    if (get != NULL && get->get != NULL) {
        // we have a hash and a get method
        struct sync_diff_fetch_data *fd = addNodeFetch(sdd, ce, side);
        if (fd == NULL)
            // already in the fetchQ, don't make me do this again
            return 0;
        struct ccn_charbuf *name = constructCommandPrefix(root, root->sliceHash, "\xC1.S.nf");
        int res = get->get(get, fd);
        ccn_charbuf_destroy(&name);
        if (res > 0 && ce->ncL == NULL && ce->ncR == NULL) {
            // we have a node being fetched
        } else {
            // no fetch, so remove the entry
            fd = remNodeFetch(sdd, ce);
            if (fd != NULL) freeFetchData(fd);
            if (res > 0) res = 0;
        }
        return res;
    }
    return -1;
}

/**
 * doPreload(sdd) walks the given tree, and requests a fetch for every
 * node that is not covered and is not in the cache, and is not being fetched.
 * This allows sync trees to be fetched in parallel.
 * @returns < 0 for failure, 0 for incomplete, and > 0 for success
 */
static int
doPreload(struct sync_diff_data *sdd,
          struct SyncTreeWorkerHead *twHead,
          enum sync_diff_side side) {
    struct SyncRootStruct *root = sdd->root;
    int busyLim = root->base->priv->maxFetchBusy;
    int debug = root->base->debug;
    int incomplete = 0;
    if (debug >= CCNL_FINE) {
        static char *here = "Sync.doPreload";
        char temp[256];
        int pos = 0;
        pos += snprintf(temp+pos, sizeof(temp)-pos, "side %d", side);
        pos += snprintf(temp+pos, sizeof(temp)-pos, ", level %d",
                        twHead->level);
        struct SyncTreeWorkerEntry *ent = SyncTreeWorkerTop(twHead);
        if (ent != NULL)
            pos += snprintf(temp+pos, sizeof(temp)-pos,
                            ", pos %d, count %d",
                            (int) ent->pos, (int) ent->count);
        SyncNoteSimple(root, here, temp);
    }
    for (;;) {
        if (sdd->nodeFetchBusy > busyLim) return 0;
        if (twHead->level <= 0) break;
        struct SyncTreeWorkerEntry *ent = SyncTreeWorkerTop(twHead);
        struct SyncHashCacheEntry *ce = ent->cacheEntry;
        if (ce == NULL)
            return abortCompare(sdd, "bad cache entry");
        if (ce->state & SyncHashState_fetching) {
            // already underway
            incomplete++;
        } else {
            struct SyncNodeComposite *nc = ce->ncL;
            if (nc == NULL) nc = ce->ncR;
            if (nc != NULL) {
                // we can visit the children
                int lim = nc->refLen;
                while (ent->pos < lim) {
                    // push into node refs that need fetching
                    struct SyncNodeElem *ep = &nc->refs[ent->pos];
                    if (ep->kind == SyncElemKind_node) {
                        struct SyncHashCacheEntry *sub = cacheEntryForElem(sdd, nc, ep);
                        if (sub == NULL)
                            // really broken, somehow
                            return abortCompare(sdd, "bad cache entry");
                        // push into the node to visit the children
                        ent = SyncTreeWorkerPush(twHead);
                        if (ent == NULL)
                            return abortCompare(sdd, "bad push");
                        goto Advance;
                    }
                    ent->pos++;
                }
            } else {
                // we need to start a fetch
                int res = start_node_fetch(sdd, ce, side);
                if (ce->ncL != NULL || ce->ncR != NULL)
                    // we scored using a local fetch, so loop to try again
                    continue;
                if (res > 0)
                    // fetch started, result not ready
                    return 0;
                // we failed to initiate a fetch
                return abortCompare(sdd, "fetch failed");
            }
        }
        // common exit to pop and iterate
        ent = SyncTreeWorkerPop(twHead);
    Advance:
        if (ent != NULL) ent->pos++;
    }
    while (sdd->nodeFetchBusy < busyLim) {
        // restart the failed node fetches (while we can)
        struct sync_diff_fetch_data *fd = sdd->fetchQ;
        if (fd == NULL) break;
        sdd->fetchQ = fd->next;
        fd->next = NULL;
        struct SyncHashCacheEntry *ce = fd->hash_cache_entry;
        start_node_fetch(sdd, ce, side);
        freeFetchData(fd);
    }
    
    if (sdd->nodeFetchBusy > 0 || sdd->fetchQ != NULL) return 0;
    if (twHead->level > 0 || incomplete) return 0;
    return 1;
}

// call the client to add the name from R's current position (in sdd->cbY),
// then advance the tree worker for R
// return < 0 to stop the comparison (without error)
static int
addNameFromCompare(struct sync_diff_data *sdd) {
    //struct SyncRootStruct *root = sdd->root;
    struct ccn_charbuf *name = sdd->cbY;
    // callback for new name
    struct SyncTreeWorkerEntry *tweR = SyncTreeWorkerTop(sdd->twY);
    if (sdd->add_closure != NULL && sdd->add_closure->add != NULL) {
        int res = sdd->add_closure->add(sdd->add_closure, name);
        if (res < 0) {
            sdd->state = sync_diff_state_done;
            return res;
        }
    }
    // advance R
    sdd->namesAdded++;
    tweR->pos++;
    tweR->count++;
    return 1;
}

/*
 * doComparison(sdd) is a key routine, because it determines what is
 * present in sdd->twY that is not present in sdd->twX.  It does so by
 * walking the two trees, L and R, in increasing name order.  To gain efficiency
 * doComparison avoids examining nodes in R that are already covered, and nodes
 * in L that have been bypassed in the walk of R.
 *
 * Ideally doComparison allows determination of k differences in O(k*log(N))
 * steps, where N is the number of names in the union of L and R.  However, if
 * the tree structures differ significantly the cost can be as high as O(N).
 *
 * @returns < 0 for failure, 0 for incomplete, and > 0 for success
 */
static int
doComparison(struct sync_diff_data *sdd) {
    //struct SyncRootStruct *root = sdd->root;
    struct SyncTreeWorkerHead *twX = sdd->twX;
    struct SyncTreeWorkerHead *twY = sdd->twY;
    
    for (;;) {
        struct SyncTreeWorkerEntry *tweR = SyncTreeWorkerTop(twY);
        if (tweR == NULL) {
            // the "remote" is done, so no more names to add
            return 1;
        }
        struct SyncHashCacheEntry *ceR = tweR->cacheEntry;
        if (ceR == NULL)
            return comparisonFailed(sdd, "bad cache entry for R", __LINE__);
        ceR->lastUsed = sdd->lastEnter;
        if (tweR->pos == 0 && isCovered(ceR)) {
            // short cut, nothing in R we don't have
            size_t c = tweR->count;
            tweR = SyncTreeWorkerPop(twY);
            if (tweR != NULL) {
                tweR->pos++;
                tweR->count += c;
            }
            continue;
        }
        struct SyncNodeComposite *ncR = ceR->ncL;
        if (ncR == NULL) ncR = ceR->ncR;
        if (ncR == NULL) {
            // "remote" node not present, so go get it
            int nf = start_node_fetch(sdd, ceR, sync_diff_Y);
            if (nf < 0)
                // node fetch failed to initiate
                return comparisonFailed(sdd, "bad node fetch for R", __LINE__);
            // fetch started OK or no fetch needed
            if (ceR->ncL == NULL && ceR->ncR == NULL)
                // hope to get it later
                return 0;
            continue;
        }
        if (tweR->pos >= ncR->refLen) {
            // we just went off the end of the current remote node, so pop it
            // skip over the processed element if we still have a node
            size_t c = tweR->count;
            if (c == 0) {
                // nothing was added, so this node must be covered
                setCovered(ceR);
            }
            tweR = SyncTreeWorkerPop(twY);
            if (tweR != NULL) {
                tweR->pos++;
                tweR->count += c;
            }
            continue;
        }
        struct SyncNodeElem *neR = SyncTreeWorkerGetElem(twY);
        if (neR == NULL || extractBuf(sdd->cbY, ncR, neR) < 0)
            return comparisonFailed(sdd, "bad extract for R", __LINE__);
        
        struct SyncTreeWorkerEntry *tweL = SyncTreeWorkerTop(twX);
        if (tweL == NULL) {
            // L is now empty, so add R
            if (neR->kind == SyncElemKind_node) {
                // to add a node R, push into it
                struct SyncHashCacheEntry *subR = cacheEntryForElem(sdd, ncR, neR);
                if (subR == NULL || SyncTreeWorkerPush(twY) == NULL)
                    return comparisonFailed(sdd, "bad cache entry for R", __LINE__);
            } else {
                // R is a leaf, so add it (and advance R)
                if (addNameFromCompare(sdd) < 0)
                    return 1;
            }
        } else {
            // L and R are both not empty
            struct SyncHashCacheEntry *ceL = tweL->cacheEntry;
            if (ceL == NULL)
                return comparisonFailed(sdd, "bad cache entry for L", __LINE__);
            struct SyncNodeComposite *ncL = ceL->ncL;
            if (ncL == NULL) ncL = ceL->ncR;
            if (ncL == NULL) {
                // "local" node not present, so go get it
                int nf = start_node_fetch(sdd, ceL, sync_diff_X);
                if (nf < 0) {
                    // node fetch failed to initiate
                    return comparisonFailed(sdd, "bad node fetch for L", __LINE__);
                }
                // fetch started OK or no fetch needed
                if (ceL->ncL == NULL && ceL->ncR == NULL)
                    // hope to get it later
                    return 0;
                continue;
            }
            // both L and R nodes are present
            ceL->lastUsed = sdd->lastEnter;
            if (tweL->pos >= ncL->refLen) {
                // we just went off the end of the current local node, so pop it
                tweL = SyncTreeWorkerPop(twX);
                if (tweL != NULL) tweL->pos++;
                continue;
            }
            // both L and R nodes are present, and both have remaining elements
            // subR and subL are the elements
            struct SyncNodeElem *neL = SyncTreeWorkerGetElem(twX);
            if (neL == NULL || extractBuf(sdd->cbX, ncL, neL) < 0) {
                // the local name/hash extract failed
                return comparisonFailed(sdd, "bad extract for L", __LINE__);
            }
            if (neR->kind == SyncElemKind_node) {
                // subR is a node
                struct SyncHashCacheEntry *subR = cacheEntryForElem(sdd, ncR, neR);
                if (subR == NULL)
                    return comparisonFailed(sdd, "bad element for R", __LINE__);
                if (isCovered(subR)) {
                    // nothing to add, this node is already covered
                    // note: this works even if the remote node is not present!
                    tweR->pos++;
                    continue;
                }
                struct SyncNodeComposite *ncS = subR->ncL;
                if (ncS == NULL) ncS = subR->ncR;
                if (ncS == NULL) {
                    // there is a remote hash, but no node present,
                    // so push into it to force the fetch
                    if (SyncTreeWorkerPush(twY) == NULL)
                        return comparisonFailed(sdd, "bad push for R", __LINE__);
                    continue;
                }
                
                if (neL->kind == SyncElemKind_leaf) {
                    // subL is a leaf, subR is a node that is present
                    enum SyncCompareResult scr = SyncNodeCompareMinMax(ncS, sdd->cbX);
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
                            if (SyncTreeWorkerPush(twY) == NULL)
                                return comparisonFailed(sdd, "bad push for R", __LINE__);
                            break;
                    }
                    
                } else {
                    // both subL and subR are nodes
                    struct SyncHashCacheEntry *subL = cacheEntryForElem(sdd, ncL, neL);
                    if (subL == NULL)
                        return comparisonFailed(sdd, "bad cache entry for L", __LINE__);
                    struct SyncHashCacheEntry *subR = cacheEntryForElem(sdd, ncR, neR);
                    if (subR == NULL)
                        return comparisonFailed(sdd, "bad cache entry for R", __LINE__);
                    
                    // both L and R are nodes, and both have cache entries
                    if (subL == subR) {
                        // same hashes, so same contents, so advance both
                        tweL->pos++;
                        tweR->pos++;
                    } else {
                        // different hashes, try for the children
                        struct SyncNodeComposite *sncL = subL->ncL;
                        if (sncL == NULL) sncL = subL->ncR;
                        struct SyncNodeComposite *sncR = subR->ncL;
                        if (sncR == NULL) sncR = subR->ncR;
                        if (sncL == NULL) {
                            // no node for subL
                            if (SyncTreeWorkerPush(twX) == NULL)
                                return comparisonFailed(sdd, "bad push for L", __LINE__);
                        } else if (sncR == NULL) {
                            // no node for subR
                            if (SyncTreeWorkerPush(twY) == NULL)
                                return comparisonFailed(sdd, "bad push for R", __LINE__);
                        } else {
                            // now use the name bounds comparison to skip work (if possible)
                            int cmp = SyncCmpNames(sncR->minName, sncL->maxName);
                            if (cmp > 0) {
                                // Min(subR) > Max(subL), so advance subL
                                tweL->pos++;
                            } else {
                                // dive into both nodes
                                if (SyncTreeWorkerPush(twX) == NULL)
                                    return comparisonFailed(sdd, "bad push for L", __LINE__);
                                if (SyncTreeWorkerPush(twY) == NULL)
                                    return comparisonFailed(sdd, "bad push for R", __LINE__);
                            }
                        }
                    }
                }
            } else {
                // R is a leaf
                if (neL->kind == SyncElemKind_leaf) {
                    // both L and R are names, so the compare is simple
                    int cmp = SyncCmpNames(sdd->cbX, sdd->cbY);
                    if (cmp == 0) {
                        // L == R, so advance both
                        tweL->pos++;
                        tweR->pos++;
                    } else if (cmp < 0) {
                        // L < R, advance L
                        tweL->pos++;
                    } else {
                        // L > R, so add R (and advance R)
                        if (addNameFromCompare(sdd) < 0)
                            return 1;
                    }
                } else {
                    // R is a leaf, but L is a node
                    struct SyncHashCacheEntry *subL = cacheEntryForElem(sdd, ncL, neL);
                    if (subL == NULL)
                        return comparisonFailed(sdd, "bad cache entry for L", __LINE__);
                    struct SyncNodeComposite *sncL = subL->ncL;
                    if (sncL == NULL) sncL = subL->ncR;
                    if (sncL == NULL)
                        return comparisonFailed(sdd, "sncL == NULL", __LINE__);
                    enum SyncCompareResult scr = SyncNodeCompareMinMax(sncL, sdd->cbY);
                    switch (scr) {
                        case SCR_before:
                            // R < Min(L), so add R (and advance R)
                            if (addNameFromCompare(sdd) < 0)
                                return 1;
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
                            if (SyncTreeWorkerPush(twX) == NULL)
                                return comparisonFailed(sdd, "bad push for L", __LINE__);
                            break;
                        default:
                            // this is really broken
                            return comparisonFailed(sdd, "bad min/max compare", __LINE__);
                    }
                    
                }
            }
        }
    }
}

static int
compareAction(struct ccn_schedule *sched,
              void *clienth,
              struct ccn_scheduled_event *ev,
              int flags) {
    char *here = "Sync.compareAction";
    struct sync_diff_data *sdd = (struct sync_diff_data *) ev->evdata;
    int res = -1;
    
    if (sdd == NULL) {
        // invalid, not sure how we got here, can't report
        return -1;
    }
    struct SyncRootStruct *root = sdd->root;
    sdd->lastEnter = SyncCurrentTime();
    int debug = root->base->debug;
    if (sdd->ev != ev) {
        // orphaned?
        return -1;
    }
    if (flags & CCN_SCHEDULE_CANCEL) {
        // cancelled (rescheduled)
        sdd->ev = NULL;
        return -1;
    }
    
    int delay = 1000; // microseconds
    if (sdd->nodeFetchFailed > 0)
        return abortCompare(sdd, "node fetch failed");
    switch (sdd->state) {
        case sync_diff_state_init: {
            // nothing to do, flow into next state
            if (debug >= CCNL_FINE) {
                struct SyncHashCacheEntry *ceX = entryForHash(root, sdd->hashX);
                struct SyncHashCacheEntry *ceY = entryForHash(root, sdd->hashY);
                showCacheEntry2(root, here, "at init", ceX, ceY);
            }
            initWorkerFromHash(root, sdd->twX, sdd->hashX);
            initWorkerFromHash(root, sdd->twY, sdd->hashY);
            sdd->state = sync_diff_state_preload;
        }
        case sync_diff_state_preload:
            // nothing to do (yet), flow into next state
            delay = 2000000;
            // For library, need to preload for Local as well as Remote.
            int resX = doPreload(sdd, sdd->twX, sync_diff_X);
            if (resX < 0)
                return abortCompare(sdd, "doPreload L failed");
            int resY = doPreload(sdd, sdd->twY, sync_diff_Y);
            if (resY < 0)
                return abortCompare(sdd, "doPreload R failed");
            // before switch to busy, reset the tree walkers
            initWorkerFromHash(root, sdd->twX, sdd->hashX);
            initWorkerFromHash(root, sdd->twY, sdd->hashY);
            if (sdd->fetchQ != NULL || resX == 0 || resY == 0) {
                // incomplete, so restart the preload
                break;
            }
            sdd->state = sync_diff_state_busy;
        case sync_diff_state_busy:
            // come here when we are comparing the trees
            if (debug >= CCNL_FINE)
                SyncNoteSimple(root, here, "busy");
            res = doComparison(sdd);
            if (res < 0)
                return abortCompare(sdd, "doComparison failed");
            if (sdd->fetchQ != NULL) {
                // we had a load start during compare, so stall
                delay = 100000;
                if (debug >= CCNL_WARNING)
                    SyncNoteSimple(root, here, "doComparison fetch stall");
                break;
            }
            if (res == 0 || sdd->fetchQ != NULL)
                // comparison not yet complete
                break;
            sdd->state = sync_diff_state_done;
        case sync_diff_state_done: {
            // there is no change to the root hash when we are done
            // the client may wish to fetch content, then alter the hash state
            // what we do here is to log the result
            int64_t now = SyncCurrentTime();
            int64_t mh = SyncDeltaTime(sdd->lastEnter, now);
            int64_t dt = SyncDeltaTime(sdd->startTime, now);
            if (mh > sdd->maxHold) sdd->maxHold = mh;
            
            if (sdd->nodeFetchFailed > 0)
                return abortCompare(sdd, "node fetch failed");
            if (debug >= CCNL_INFO) {
                char temp[64];
                mh = (mh + 500) / 1000;
                dt = (dt + 500) / 1000;
                snprintf(temp, sizeof(temp)-2,
                         "%d.%03d secs [%d.%03d], %d names added",
                         (int) (dt / 1000), (int) (dt % 1000),
                         (int) (mh / 1000), (int) (mh % 1000),
                         (int) sdd->namesAdded);
                SyncNoteSimple2(root, here, "done", temp);
            }
            if (sdd->add_closure != NULL && sdd->add_closure->add != NULL)
                // give the client a last shot at the data
                sdd->add_closure->add(sdd->add_closure, NULL);
            delay = -1;
            sdd->ev = NULL; // event will not be rescheduled
            resetDiffData(sdd);
            break;
        }
        case sync_diff_state_error: {
            if (sdd->add_closure != NULL && sdd->add_closure->add != NULL)
                // give the client a last shot at the data
                sdd->add_closure->add(sdd->add_closure, NULL);
            return abortCompare(sdd, "sync_diff_state_error");
        }
        default: {
            return abortCompare(sdd, "bad state");
        };
    }
    int64_t mh = SyncDeltaTime(sdd->lastEnter, SyncCurrentTime());
    if (mh > sdd->maxHold) sdd->maxHold = mh;
    return delay;
}

/////////////////////////////////
//// Update support
/////////////////////////////////

static struct SyncHashCacheEntry *
newNodeCommon(struct SyncRootStruct *root,
              struct SyncNodeAccum *nodes,
              struct SyncNodeComposite *nc) {
    // finish building and inserting a local node
    char *here = "Sync.newNodeCommon";
    struct SyncBaseStruct *base = root->base;
    int debug = base->debug;
    if (nc == NULL || nc->hash == NULL) {
        SyncNoteFailed(root, here, "bad node", __LINE__);
        return NULL;
    }
    struct ccn_charbuf *hash = nc->hash;
    struct SyncHashCacheEntry *ce = entryForHash(root, hash);
    SyncCacheEntryFetch(ce);
    if (ce != NULL && ce->ncL != NULL) {
        // an equivalent local node is already in the cache
        // so get rid of the new node and return the existing entry
        if (debug >= CCNL_FINE) {
            char *hex = SyncHexStr(hash->buf, hash->length);
            SyncNoteSimple2(root, here, "suppressed duplicate", hex);
            free(hex);
        }
        SyncFreeComposite(nc);
        nc = ce->ncL;
        root->priv->stats->nodesShared++;
    } else {
        // no local cache entry, so make one
        struct SyncPrivate *priv = base->priv;
        ce = SyncHashEnter(root->ch, hash->buf, hash->length, SyncHashState_local);
        if (ce == NULL) {
            // this should not have happened!
            SyncNoteFailed(root, here, "bad enter", __LINE__);
            SyncNodeDecRC(nc);
            return NULL;
        }
        SyncNodeIncRC(nc);
        ce->ncL = nc;
        if (ce->state & SyncHashState_remote)
            setCovered(ce);
        // queue this cache entry for storing
        ce->state |= SyncHashState_storing;
        if (priv->storingTail == NULL) {
            // storing queue is empty
            priv->storingHead = ce;
        } else {
            // append to the tail
            priv->storingTail->storing = ce;
        }
        priv->storingTail = ce;
        priv->nStoring++;
        root->priv->stats->nodesCreated++;
        if (nc->cb->length >= nodeSplitTrigger) {
            // if this happens then our split estimate was wrong!
            if (debug >= CCNL_INFO)
                sync_msg(base,
                         "%s, root#%u, cb->length (%d) >= nodeSplitTrigger (%d)",
                         here, root->rootId,
                         (int) nc->cb->length, (int) nodeSplitTrigger);
        }
    }
    SyncAccumNode(nodes, nc);
    return ce;
}

static struct SyncHashCacheEntry *
node_from_nodes(struct SyncRootStruct *root, struct SyncNodeAccum *na) {
    char *here = "Sync.node_from_nodes";
    struct SyncBaseStruct *base = root->base;
    int debug = base->debug;
    int lim = na->len;
    if (lim == 0) {
        SyncNoteFailed(root, here, "empty", __LINE__);
        return NULL;
    }
    if (lim == 1) {
        // just return the singleton node
        struct SyncNodeComposite *nc = na->ents[0];
        if (nc == NULL || nc->hash == NULL) {
            SyncNoteFailed(root, here, "bad node", __LINE__);
            return NULL;
        }
        struct SyncHashCacheEntry *ce = entryForHash(root, nc->hash);
        if (ce == NULL)
            SyncNoteFailed(root, here, "bad lookup", __LINE__);
        return ce;
    }
    
    int accLim = nodeSplitTrigger - nodeSplitTrigger/8;
    struct SyncNodeAccum *nodes = SyncAllocNodeAccum(0);
    struct SyncHashCacheEntry *ce = NULL;
    int j = 0;
    while (j < lim) {
        int maxLen = 0;
        int i = j;
        struct SyncNodeComposite *nc = SyncAllocComposite(base);
        int accLen = nc->cb->length;
        // first, loop to find the run length
        while (i < lim && accLen < accLim) {
            struct SyncNodeComposite *elem = na->ents[i];
            i++;
            int nodeLen = elem->hash->length + 8;
            if (nodeLen > maxLen) maxLen = nodeLen;
            accLen = accLen + nodeLen + (maxLen - nodeLen) * 2;
        }
        
        // append the references in the run
        while (j < i) {
            struct SyncNodeComposite *elem = na->ents[j];
            j++;
            SyncNodeAddNode(nc, elem);
        }
        SyncEndComposite(nc); // finish the node
        ce = newNodeCommon(root, nodes, nc);
    }
    // go recursive just in case we need the extra levels
    ce = node_from_nodes(root, nodes);
    nodes = SyncFreeNodeAccum(nodes);
    if (debug >= CCNL_FINE) {
        sync_msg(base, "%s, root#%u, %d refs", here, root->rootId, lim);
    }
    return ce;
}

static int
node_from_names(struct sync_update_data *ud, int split) {
    char *here = "Sync.node_from_names";
    struct SyncRootStruct *root = ud->root;
    int debug = root->base->debug;
    struct SyncNameAccum *na = ud->names;
    int lim = na->len;
    if (lim == 0)
        // should not have been called, but no harm done
        return 0;
    int i = 0;
    if (split == 0) split = lim;
    if (debug >= CCNL_FINE) {
        char tmp[64];
        snprintf(tmp, sizeof(tmp),
                 "split %d, lim %d",
                 split, lim);
        SyncNoteSimple(root, here, tmp);
    }
    
    // accum the hash for the node, and see if it exists
    struct SyncLongHashStruct longHash;
    memset(&longHash, 0, sizeof(struct SyncLongHashStruct));
    longHash.pos = MAX_HASH_BYTES;
    for (i = 0; i < split; i++) {
        struct ccn_charbuf *name = na->ents[i].name;
        SyncAccumHash(&longHash, name);
    }
    ssize_t hs = MAX_HASH_BYTES-longHash.pos;
    unsigned char *hp = longHash.bytes+longHash.pos;
    struct SyncHashCacheEntry *ce = SyncHashLookup(root->ch, hp, hs);
    if (ce != NULL && ce->ncL != NULL) {
        // node already exists
        struct SyncNodeComposite *nc = ce->ncL;
        SyncAccumNode(ud->nodes, nc);
        if (debug >= CCNL_FINE) {
            char *hex = SyncHexStr(hp, hs);
            SyncNoteSimple2(root, here, "existing local node", hex);
            free(hex);
        }
    } else {
        // need to create a new node
        if (debug >= CCNL_FINE) {
            char *hex = SyncHexStr(hp, hs);
            SyncNoteSimple2(root, here, "need new local node", hex);
            free(hex);
        }
        struct SyncNodeComposite *nc = SyncAllocComposite(root->base);
        for (i = 0; i < split; i++) {
            struct ccn_charbuf *name = na->ents[i].name;
            SyncNodeAddName(nc, name);
        }
        SyncEndComposite(nc);
        newNodeCommon(root, ud->nodes, nc);
    }
    // names 0..split - 1 must be freed as they are either represented by
    // an existing node or have been copied to a new node
    for (i = 0; i < split; i++) {
        ccn_charbuf_destroy(&na->ents[i].name);
    }
    // shift remaining elements down in the name accum
    ud->nameLenAccum = 0;
    i = 0;
    while (split < lim) {
        struct ccn_charbuf *name = na->ents[split].name;
        ud->nameLenAccum += name->length;
        na->ents[i] = na->ents[split];
        na->ents[split].name = NULL;
        i++;
        split++;
    }
    na->len = i;
    return i;
}

static int
try_node_split(struct sync_update_data *ud) {
    char *here = "Sync.try_node_split";
    struct SyncNameAccum *na = ud->names;
    int lim = na->len;
    if (lim == 0)
        // should not have been called, but no harm done
        return 0;
    struct SyncRootStruct *root = ud->root;
    int debug = root->base->debug;
    int accLim = nodeSplitTrigger - nodeSplitTrigger/8;
    int accMin = nodeSplitTrigger/2;
    int res = 0;
    int splitMethod = 3;  // was variable, now is constantly enabled
    int maxLen = 0;
    int accLen = 0;
    int prevMatch = 0;
    int split = 0;
    if (debug >= CCNL_FINE) {
        char tmp[64];
        snprintf(tmp, sizeof(tmp),
                 "entered, %d names",
                 lim);
        SyncNoteSimple(root, here, tmp);
    }
    for (split = 0; split < lim; split++) {
        struct ccn_charbuf *name = na->ents[split].name;
        int nameLen = name->length + 8;
        if (nameLen > maxLen) maxLen = nameLen;
        accLen = accLen + nameLen + (maxLen - nameLen) * 2;
        if (split+1 < lim) {
            if (splitMethod & 1) {
                // use level shift to split
                struct ccn_charbuf *next = na->ents[split+1].name;
                int match = SyncComponentMatch(name, next);
                if (accLen >= accMin
                    && (match < prevMatch || (match > prevMatch+1))) {
                    // force a break due to level changes
                    if (debug >= CCNL_FINE) {
                        char tmp[64];
                        snprintf(tmp, sizeof(tmp),
                                 "split %d, lim %d, match %d, prev %d, accLen %d",
                                 split, lim, match, prevMatch, accLen);
                        SyncNoteSimple2(root, here, "level split found", tmp);
                    }
                    break;
                }
                prevMatch = match;
            }
            if (splitMethod & 2) {
                // use bits of hash to split
                int pos = name->length - 9;
                if (pos > 0 && accLen >= accMin) {
                    unsigned c = name->buf[pos] & 255;
                    if (c < hashSplitTrigger) {
                        if (debug >= CCNL_FINE) {
                            char tmp[64];
                            snprintf(tmp, sizeof(tmp),
                                     "split %d, lim %d, x %u, accLen %d",
                                     split, lim, c, accLen);
                            SyncNoteSimple2(root, here, "hash split found", tmp);
                        }
                        break;
                    }
                }
            }
        }
        if (accLen >= accLim) {
            break;
        }
    }
    // at this point we take the first "split" elements into a node
    res = node_from_names(ud, split);
    return res;
}

// add_update_name adds a name to the current update name accumulator
// and adds it to the deltas if it is a new name and can be added
static int
add_update_name(struct sync_update_data *ud, struct ccn_charbuf *name, int isNew) {
    static char *here = "Sync.add_update_name";
    struct SyncRootStruct *root = ud->root;
    int debug = root->base->debug;
    struct SyncNameAccum *dst = ud->names;
    int nameLen = name->length;
    int accLim = nodeSplitTrigger - nodeSplitTrigger/8;
    int res = 0;
    name = SyncCopyName(name);
    SyncNameAccumAppend(dst, name, 0);
    if (debug >= CCNL_FINE) {
        char *msg = ((isNew) ? "added+" : "added");
        SyncNoteUri(root, here, msg, name);
    }
    ud->nameLenAccum += nameLen;
    ud->namesAdded++;
    if (ud->nameLenAccum >= accLim) {
        // we should split, if it is possible
        res = try_node_split(ud);
    }
    return res;
}

// merge the semi-sorted names and the old sync tree
// returns -1 for failure, 0 for incomplete, 1 for complete
static int
merge_names(struct sync_update_data *ud) {
    char *here = "Sync.merge_names";
    struct SyncRootStruct *root = ud->root;
    //struct SyncRootPrivate *rp = root->priv;
    int debug = root->base->debug;
    struct ccn_charbuf *cb = ud->cb;
    struct SyncTreeWorkerHead *head = ud->tw;
    int res = 0;
    int namesLim = ud->namesAdded+namesYieldInc;
    if (head != NULL) {
        while (res == 0) {
            struct SyncTreeWorkerEntry *ent = SyncTreeWorkerTop(head);
            if (ent == NULL) break;
            struct SyncHashCacheEntry *ce = ent->cacheEntry;
            if (ce == NULL)  {
                // should not happen
                res = -__LINE__;
                break;
            }
            struct SyncNodeComposite *nc = ce->ncL;
            if (nc == NULL) nc = ce->ncR;
            if (nc == NULL) {
                // should not happen
                res = -__LINE__;
                break;
            }
            int lim = nc->refLen;
            if (ent->pos >= lim) {
                // done with the current level, go back to the previous level
                ent = SyncTreeWorkerPop(head);
                if (ent == NULL) break;
                ent->pos++;
            } else {
                struct SyncNodeElem *ep = &nc->refs[ent->pos];
                if (ep->kind & SyncElemKind_leaf) {
                    // a leaf, so the element name is inline
                    enum SyncCompareResult cmp = SCR_after;
                    struct ccn_charbuf *name = NULL;
                    int ax = ud->ax;
                    int aLen = ud->adding->len;

                    if (ax < aLen) {
                        name = ud->adding->ents[ax].name;
                        if (name != NULL) cmp = SyncNodeCompareLeaf(nc, ep, name);
                    }
                    switch (cmp) {
                        case SCR_before:
                            // add the name from src
                            ud->adding->ents[ax].name = NULL;
                            add_update_name(ud, name, 1);
                            ud->ax = ax+1;
                        case SCR_min:
                            // advance the src, don't add
                            ud->ax = ax+1;
                            break;
                        case SCR_after:
                            // add the name from the tree
                            extractBuf(cb, nc, ep);
                            add_update_name(ud, cb, 0);
                            ent->pos++;
                            break;
                        default:
                            // this is not kosher
                            res = -__LINE__;
                            break;
                    }
                    if (ud->namesAdded >= namesLim) {
                        int64_t dt = SyncDeltaTime(ud->entryTime, SyncCurrentTime());
                        if (dt >= namesYieldMicros) {
                            // need to yield
                            if (debug >= CCNL_FINE)
                                SyncNoteSimple(root, here, "yield");
                            return 0;
                        }
                        namesLim += namesYieldInc;
                    }
                } else {
                    // a node, so push into it
                    ent = SyncTreeWorkerPush(head);
                    if (ent == NULL) {
                        res = -__LINE__;
                        break;
                    }                
                }
            }
        }
    }
    if (res == 0) {
        // done with the tree, move items from the src
        int ax = ud->ax;
        int aLen = ud->adding->len;
        while (ax < aLen) {
            struct ccn_charbuf *name = ud->adding->ents[ax].name;
            ud->adding->ents[ax].name = NULL;
            add_update_name(ud, name, 1);
            ax++;
        }
        ud->ax = ax;
        res = 1;
    }
    return res;
}

static int
updateError(struct sync_update_data *ud) {
    if (ud != NULL) {
        struct ccn_scheduled_event *ev = ud->ev;
        if (ev != NULL) {
            ud->ev = NULL;
            ev->evdata = NULL;
        }
        ud->state = sync_update_state_error;
    }
    return -1;
}

static int
updateAction(struct ccn_schedule *sched,
             void *clienth,
             struct ccn_scheduled_event *ev,
             int flags) {
    char *here = "Sync.updateAction";
    int64_t now = SyncCurrentTime();
    struct sync_update_data *ud = (struct sync_update_data *) ev->evdata;
    struct SyncRootStruct *root = ud->root;
    struct SyncBaseStruct *base = root->base;
    int debug = base->debug;
    
    if (ud == NULL) {
        // cancelled some time ago
        return -1;
    }
    if (ud->ev != ev) {
        // orphaned?
        return -1;
    }
    if (flags & CCN_SCHEDULE_CANCEL) {
        // cancelled (rescheduled)
        ud->ev = NULL;
        return -1;
    }
    
    ud->entryTime = now;
    
    switch (ud->state) {
        case sync_update_state_init: {
            // we are mostly initialized
            if (debug >= CCNL_FINE) {
                showCacheEntry1(root, here, "at init", ud->ceStart);
            }
            int res = merge_names(ud);
            if (res == 0) break;
            // not done yet, pause requested
            res = node_from_names(ud, 0);
            // done, either normally or with error
            // free the resources
            ccn_charbuf_destroy(&ud->cb);
            if (res < 0) {
                // this is bad news!
                SyncNoteFailed(root, here, "merge names", __LINE__);
                return updateError(ud);
            }
            ud->state = sync_update_state_busy;
        }
        case sync_update_state_busy: {
            // ud->nodes has the nodes created from the names
            // the last step is to make up the node superstructure
            if (debug >= CCNL_FINE) {
                SyncNoteSimple(root, here, "sync_update_state_busy");
            }
            int initCount = root->priv->currentSize;
            int count = ud->namesAdded;
            if (count > 0) {
                struct SyncHashCacheEntry *ce = node_from_nodes(root, ud->nodes);
                if (ce == NULL) {
                    count = SyncNoteFailed(root, here, "bad node_from_nodes()", __LINE__);
                } else {
                    SyncCacheEntryFetch(ce);
                    struct SyncNodeComposite *nc = ce->ncL;
                    if (nc == NULL) nc = ce->ncR;
                    if (nc != NULL) {
                        struct ccn_charbuf *hash = SyncLongHashToBuf(&nc->longHash);
                        char *hex = SyncHexStr(hash->buf, hash->length);
                        struct SyncHashCacheEntry *ce = SyncHashEnter(root->ch, 
                                                                      hash->buf, hash->length,
                                                                      SyncHashState_local);
                        now = SyncCurrentTime();
                        ud->ceStop = ce;
                        // now that we have a new current hash, close out the deltas
                        int64_t dt = SyncDeltaTime(ud->startTime, now);
                        dt = (dt + 500) / 1000;
                        int64_t mh = SyncDeltaTime(ud->entryTime, now);
                        if (mh < ud->maxHold) mh = ud->maxHold;
                        mh = (mh + 500) / 1000;
                        if (debug >= CCNL_INFO) {
                            char temp[256];
                            snprintf(temp, sizeof(temp)-2,
                                     "%d.%03d secs [%d.%03d], %d names, depth %d, hash %s",
                                     (int) (dt / 1000), (int) (dt % 1000),
                                     (int) (mh / 1000), (int) (mh % 1000),
                                     (int) count, (int) nc->treeDepth, hex);
                            SyncNoteSimple2(root, here, "done", temp);
                        }
                        if (ud->ceStart != ud->ceStop) {
                            // only do this if the update got something
                            if (debug >= CCNL_INFO) {
                                char temp[64];
                                snprintf(temp, sizeof(temp),
                                         "done (%d)",
                                         count);
                                showCacheEntry2(root, "Sync.$Update", temp,
                                                ud->ceStart, ud->ceStop);
                            }
                        } 
                        free(hex);
                        ccn_charbuf_destroy(&hash);
                    } else {
                        count = SyncNoteFailed(root, here, "bad node", __LINE__);
                    }
                }
            }
            if (count <= initCount) {
                // we were supposed to add something?
                if (debug >= CCNL_INFO) {
                    struct ccn_charbuf *hash = root->currentHash;
                    char *hex = SyncHexStr(hash->buf, hash->length);
                    sync_msg(base,
                             "%s, root#%u, note, count %d, initCount %d, hash %s",
                             here, root->rootId, count, initCount, hex);
                    free(hex);
                }
            }
            ud->ev = NULL;
            ev->evdata = NULL;
            ud->state = ((count < 0) ? sync_update_state_error : sync_update_state_done);
            if (debug >= CCNL_FINE)
                showCacheEntry2(root, here, "at exit", ud->ceStart, ud->ceStop);
            if (ud->done_closure != NULL) {
                // notify the caller
                ud->done_closure->update_data = ud;
                ud->done_closure->done(ud->done_closure);
            }
            return -1;
        }
        default: {
            // no reschedule
            ud->ev = NULL;
            ev->evdata = NULL;
            return -1;
        }
    }
    int64_t edt = SyncDeltaTime(ud->entryTime, SyncCurrentTime());
    if (edt > ud->maxHold) ud->maxHold = edt;
    return shortDelayMicros;
}

/////////////////////////////////
//// External operations
/////////////////////////////////

int
sync_diff_start(struct sync_diff_data *sdd) {
    struct SyncRootStruct *root = sdd->root;
    int64_t mark = SyncCurrentTime();
    struct SyncHashCacheEntry *ceX = entryForHash(root, sdd->hashX);
    struct SyncHashCacheEntry *ceY = entryForHash(root, sdd->hashY);
    
    if (ceX != NULL) ceX->lastUsed = mark;
    if (ceY != NULL) ceY->lastUsed = mark;
    resetDiffData(sdd);
    sdd->twX = SyncTreeWorkerCreate(root->ch, ceX);
    sdd->twY = SyncTreeWorkerCreate(root->ch, ceY);
    sdd->startTime = mark;
    sdd->lastEnter = mark;
    sdd->lastMark = mark;
    sdd->lastFetchOK = mark;
    sdd->cbX = ccn_charbuf_create();
    sdd->cbY = ccn_charbuf_create();
    sdd->namesAdded = 0;
    sdd->nodeFetchBusy = 0;
    sdd->nodeFetchFailed = 0;
    
    sdd->state = sync_diff_state_init;
    
    kickCompare(sdd, 1);
    // XXX - documented returns of negative and 0 can't happen.
    return(1);
}

// the client uses sync_diff_note_node to note the completion of a node fetch
// the success of the fetch is inferred from the presence of a node object
// in the cache entry (either ce->ncL or ce->ncR will do)
int
sync_diff_note_node(struct sync_diff_data *sdd,
                    struct SyncHashCacheEntry *ce) {
    int res = 0;
    if (ce != NULL) {
        struct sync_diff_fetch_data *fd = remNodeFetch(sdd, ce);
        struct SyncRootStruct *root = sdd->root;
        int debug = root->base->debug;
        if (debug >= CCNL_FINE) {
            static char *here = "Sync.sync_diff_note_node";
            char temp[256];
            int pos = 0;
            if (fd == NULL)
                pos += snprintf(temp+pos, sizeof(temp)-pos, "NULL!!!");
            else {
                pos += snprintf(temp+pos, sizeof(temp)-pos, "fd OK");
            }
            if (ce->ncL != NULL)
                pos += snprintf(temp+pos, sizeof(temp)-pos, ", ce->ncL OK");
            if (ce->ncL != NULL)
                pos += snprintf(temp+pos, sizeof(temp)-pos, ", ce->ncR OK");
            SyncNoteSimple(root, here, temp);
        }
        if (fd == NULL)
            // the supplied hash entry is not queued
            res = -1;
        else {
            // so far so good
            enum SyncHashState es = ce->state;
            if (ce->ncL != NULL) {
                es |= SyncHashState_local;
            }
            if (ce->ncR != NULL) {
                es |= SyncHashState_remote;
                if (es & SyncHashState_local)
                    es |= SyncHashState_covered;
            }
            if (es & SyncHashState_fetching) {
                es = es - SyncHashState_fetching;
            }
            ce->state = es;
            if (ce->ncL != NULL || ce->ncR != NULL) {
                // the fetch is OK!
                int64_t mark = SyncCurrentTime();
                res = 1;
                sdd->lastFetchOK = mark;
            } else {
                // the fetch was not successful
                res = 0;
                sdd->nodeFetchFailed++;
            }
            freeFetchData(fd);
        }
    }
    kickCompare(sdd, 1);
    return res;
}

int
sync_diff_stop(struct sync_diff_data *sdd) {
    struct SyncRootStruct *root = sdd->root;
    if (sdd == NULL)
        return 0;
    struct ccn_scheduled_event *ev = sdd->ev;
    if (ev != NULL && ev->evdata == sdd) {
        // no more callbacks
        ccn_schedule_cancel(root->base->sd->sched, ev);
    }
    resetDiffData(sdd);
    return 1; 
}

int
sync_update_start(struct sync_update_data *ud, struct SyncNameAccum *acc) {
    char *here = "Sync.sync_update_start";
    int64_t now = SyncCurrentTime();
    struct SyncRootStruct *root = ud->root;
    struct SyncBaseStruct *base = root->base;
    int debug = base->debug;
    switch (ud->state) {
        case sync_update_state_init:
        case sync_update_state_error:
        case sync_update_state_done: {
            // OK to restart
            if (acc == NULL || acc->len == 0) return 0;
            if (debug >= CCNL_FINE) {
                SyncNoteSimple(root, here, "starting");
            }
            struct SyncHashCacheEntry *ent = ud->ceStart;
            ud->startTime = now;
            ud->ceStop = NULL;
            resetUpdateData(ud);
            ud->adding = SyncSortNames(root, acc);
            acc->len = 0; // source no longer owns the names
            ud->cb = ccn_charbuf_create();
            ud->names = SyncAllocNameAccum(0);
            ud->nodes = SyncAllocNodeAccum(0);
            ud->namesAdded = 0;
            ud->nameLenAccum = 0;
            ud->state = sync_update_state_init;
            if (ent != NULL) {
                SyncCacheEntryFetch(ent);
                ud->tw = SyncTreeWorkerCreate(root->ch, ent);
            }
            kickUpdate(ud, 1);
            return 1;
        }
        default:
            return 0;
            // don't restart a busy updater
            return -1;
    }
}

int
sync_update_stop(struct sync_update_data *ud) {
    char *here = "Sync.sync_update_stop";
    struct SyncRootStruct *root;
    if (ud == NULL)
        return 0;
    root = ud->root;
    int debug = root->base->debug;
    if (debug >= CCNL_FINE) {
        SyncNoteSimple(root, here, "stopping");
    }
    resetUpdateData(ud);
    ud->state = sync_update_state_done;
    return 1;
}

