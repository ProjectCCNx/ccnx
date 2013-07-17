/**
 * @file sync/SyncRoot.c
 *  
 * Part of CCNx Sync.
 */
/*
 * Copyright (C) 2011-2013 Palo Alto Research Center, Inc.
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
#include <string.h>
#include <strings.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/digest.h>
#include <ccn/indexbuf.h>
#include <ccn/schedule.h>
#include <ccn/uri.h>
#include <ccn/loglevels.h>

#include "SyncMacros.h"
#include "SyncPrivate.h"
#include "SyncHashCache.h"
#include "SyncUtil.h"
#include "SyncRoot.h"

///////////////////////////////////////////////////////
// Routines for root management
///////////////////////////////////////////////////////

/**
 * copies the filter, including copies of the names
 */
static struct SyncNameAccum *
copyFilter(struct SyncNameAccum *filter) {
    int i = 0;
    int len = filter->len;
    struct SyncNameAccum *canon = SyncAllocNameAccum(len);
    for (i = 0; i < len; i++) {
        struct ccn_charbuf *name = filter->ents[i].name;
        struct ccn_charbuf *copy = ccn_charbuf_create();
        ccn_charbuf_append_charbuf(copy, name);
        canon->ents[i].name = copy;
        canon->ents[i].data = filter->ents[i].data;
        
    }
    canon->len = len;
    return canon;
}

/**
 * canonicalizes the filter
 * returns an existing equivalent filter if one is found
 * otherwise copies the input filter, links it in, and returns the copy
 */
static struct SyncNameAccum *
canonFilter(struct SyncBaseStruct *base, struct SyncNameAccum *filter) {
    struct SyncPrivate *priv = base->priv;
    struct SyncNameAccumList *filters = priv->filters;
    while (filters != NULL) {
        struct SyncNameAccum *accum = filters->accum;
        if (accum != NULL) {
            if (accum->len == filter->len) {
                int i = 0;
                int equal = 1;
                while (i < filter->len) {
                    struct ccn_charbuf *x = filter->ents[i].name;
                    struct ccn_charbuf *y = accum->ents[i].name;
                    if (SyncCmpNames(x, y) != 0) {
                        equal = 0;
                        break;
                    }
                    i++;
                }
                if (equal)
                    // found an existing filter
                    return accum;
            }
        }
        filters = filters->next;
    }
    // copy the filter, then link it in to the private data
    struct SyncNameAccum *canon = copyFilter(filter);
    filters = NEW_STRUCT(1, SyncNameAccumList);
    filters->next = priv->filters;
    priv->filters = filters;
    filters->accum = canon;
    return canon;
}

extern struct SyncRootStruct *
SyncAddRoot(struct SyncBaseStruct *base,
            int syncScope,
            const struct ccn_charbuf *topoPrefix,
            const struct ccn_charbuf *namingPrefix,
            struct SyncNameAccum *filter) {
    struct SyncRootStruct *root = NEW_STRUCT(1, SyncRootStruct);
    struct SyncPrivate *priv = base->priv;
    int newTopo = 0;
    root->base = base;
    root->priv = NEW_STRUCT(1, SyncRootPrivate);
    root->priv->stats = NEW_STRUCT(1, SyncRootStats);
    int64_t now = SyncCurrentTime();
    root->priv->lastAdvise = now;
    root->priv->lastUpdate = now;
    if (syncScope < 1 || syncScope > 2)
        // invalid scopes treated as unscoped
        syncScope = -1;
    root->priv->syncScope = syncScope;
    root->priv->sliceBusy = -1;
    base->lastRootId++;
    root->rootId = base->lastRootId;
    if (topoPrefix != NULL) {
        int oldLen = priv->topoAccum->len;
        root->topoPrefix = SyncNameAccumCanon(priv->topoAccum, topoPrefix);
        if (oldLen < priv->topoAccum->len) newTopo++;
    }
    if (namingPrefix != NULL) {
        root->namingPrefix = SyncNameAccumCanon(priv->prefixAccum, namingPrefix);
    }
    if (filter != NULL) {
        root->filter = canonFilter(base, filter);
    }
    struct SyncRootStruct *lag = priv->rootHead;
    while (lag != NULL) {
        struct SyncRootStruct *next = lag->next;
        if (next == NULL) break;
        lag = next;
    }
    if (lag != NULL) lag->next = root;
    else priv->rootHead = root;
    priv->nRoots++;
    struct SyncHashCacheHead *ch = SyncHashCacheCreate(root, 64);
    root->ch = ch;
    root->currentHash = ccn_charbuf_create(); // initially empty!
    
    // init the state for name processing
    root->namesToAdd = SyncAllocNameAccum(0);
    root->namesToFetch = SyncAllocNameAccum(0);
    
    root->sliceCoding = ccn_charbuf_create();
    root->sliceHash = ccn_charbuf_create();
    root->heldRAInterest = ccn_charbuf_create();
    if (SyncRootAppendSlice(root->sliceCoding, root) >= 0) {
        // make a hash code from the encoding
        struct ccn_digest *cow = ccn_digest_create(CCN_DIGEST_DEFAULT);
        size_t sz = ccn_digest_size(cow);
        unsigned char *dst = ccn_charbuf_reserve(root->sliceHash, sz);
        ccn_digest_init(cow);
        ccn_digest_update(cow, root->sliceCoding->buf, root->sliceCoding->length);
        ccn_digest_final(cow, dst, sz);
        root->sliceHash->length = sz;
        ccn_digest_destroy(&cow);
    }
    
    return root;
}

extern struct SyncRootStruct *
SyncRemRoot(struct SyncRootStruct *root) {
    if (root == NULL || root->base == NULL || root->compare != NULL)
        // NOTE: caller must ensure that there are no comparisons active!
        return root;
    struct SyncBaseStruct *base = root->base;
    struct SyncPrivate *priv = base->priv;
    struct SyncRootStruct *lag = NULL;
    struct SyncRootStruct *this = priv->rootHead;
    while (this != NULL) {
        struct SyncRootStruct *next = this->next;
        if (this == root) {
            struct SyncRootPrivate *rp = root->priv;
            if (lag != NULL) lag->next = next;
            else priv->rootHead = next;
            if (root->ch != NULL)
                root->ch = SyncHashCacheFree(root->ch);
            if (root->currentHash != NULL)
                ccn_charbuf_destroy(&root->currentHash);
            if (root->namesToAdd != NULL)
                SyncFreeNameAccumAndNames(root->namesToAdd);
            if (root->namesToFetch != NULL)
                SyncFreeNameAccumAndNames(root->namesToFetch);
            if (root->sliceCoding != NULL)
                ccn_charbuf_destroy(&root->sliceCoding);
            if (root->heldRAInterest != NULL)
                ccn_charbuf_destroy(&root->heldRAInterest);
            if (root->sliceHash != NULL)
                ccn_charbuf_destroy(&root->sliceHash);
            if (rp != NULL) {
                if (rp->stats != NULL) free(rp->stats);
                struct SyncHashInfoList *list = rp->remoteSeen;
                while (list != NULL) {
                    struct SyncHashInfoList *lag = list;
                    list = list->next;
                    free(lag);
                }
                list = rp->localMade;
                while (list != NULL) {
                    struct SyncHashInfoList *lag = list;
                    list = list->next;
                    free(lag);
                }
                
                struct SyncRootDeltas *deltas = rp->deltasHead;
                while (deltas != NULL) {
                    struct SyncRootDeltas *next = deltas->next;
                    ccn_charbuf_destroy(&deltas->coding);
                    ccn_charbuf_destroy(&deltas->name);
                    ccn_charbuf_destroy(&deltas->cob);
                    free(deltas);
                    deltas = next;
                }
                if (rp->remoteDeltas != NULL) {
                    SyncFreeNameAccumAndNames(rp->remoteDeltas);
                }
                free(rp);
            }
            free(root);
            priv->nRoots--;
            break;
        }
        lag = this;
        this = next;
    }
    return NULL;
}

extern struct SyncRootStruct *
SyncRootDecodeAndAdd(struct SyncBaseStruct *base,
                     struct ccn_buf_decoder *d) {
    struct SyncRootStruct *root = NULL;
    if (ccn_buf_match_dtag(d, CCN_DTAG_SyncConfigSlice)) {
        int oops = 0;
        ccn_buf_advance(d);
        uintmax_t vers = SyncParseUnsigned(d, CCN_DTAG_SyncVersion);
        if (vers == SLICE_VERSION) {
            struct ccn_charbuf *topo = SyncExtractName(d);
            struct ccn_charbuf *prefix = SyncExtractName(d);
            struct SyncNameAccum *filter = SyncAllocNameAccum(4);
            if (ccn_buf_match_dtag(d, CCN_DTAG_SyncConfigSliceList)) {
                ccn_buf_advance(d);
                while (ccn_buf_match_dtag(d, CCN_DTAG_SyncConfigSliceOp)) {
                    uintmax_t op = SyncParseUnsigned(d, CCN_DTAG_SyncConfigSliceOp);
                    struct ccn_charbuf *clause = SyncExtractName(d);
                    if (op != 0 || clause == NULL) {
                        oops++;
                        break;
                    }
                    SyncNameAccumAppend(filter, clause, op);
                }
                ccn_buf_check_close(d);
                if (SyncCheckDecodeErr(d)) oops++;
            }
            ccn_buf_check_close(d);
            if (SyncCheckDecodeErr(d)) oops++;
            if (oops == 0) {
                // TBD: extract the scope from the slice
                root = SyncAddRoot(base, base->priv->syncScope, topo, prefix, filter);
            }
            // regardless of success, the temporary storage must be returned
            if (topo != NULL) ccn_charbuf_destroy(&topo);
            if (prefix != NULL) ccn_charbuf_destroy(&prefix);
            if (filter != NULL) SyncFreeNameAccumAndNames(filter);
        }
    }
    return root;
}

static int
appendName(struct ccn_charbuf *cb, struct ccn_charbuf *name) {
    // we use this to append a name when the name might be NULL
    // the slice encoding depends on position
    int res = 0;
    if (name == NULL) {
        name = ccn_charbuf_create();
        ccn_name_init(name);
        res |= ccn_charbuf_append_charbuf(cb, name);
        ccn_charbuf_destroy(&name);
    } else
        res |= ccn_charbuf_append_charbuf(cb, name);
    return res;
}

extern int
SyncRootAppendSlice(struct ccn_charbuf *cb, struct SyncRootStruct *root) {
    int res = 0;
    res |= ccnb_element_begin(cb, CCN_DTAG_SyncConfigSlice);
    res |= SyncAppendTaggedNumber(cb, CCN_DTAG_SyncVersion, SLICE_VERSION);
    // TBD: encode the scope
    res |= appendName(cb, root->topoPrefix);
    res |= appendName(cb, root->namingPrefix);
    res |= ccnb_element_begin(cb, CCN_DTAG_SyncConfigSliceList);
    struct SyncNameAccum *filter = root->filter;
    if (res >= 0 && filter != NULL) {
        int i = 0;
        for (i = 0; i < filter->len; i++) {
            struct ccn_charbuf *clause = filter->ents[i].name;
            res |= SyncAppendTaggedNumber(cb, CCN_DTAG_SyncConfigSliceOp, 0);
            res |= ccn_charbuf_append_charbuf(cb, clause);
            if (res < 0) break;
        }
    }
    res |= ccnb_element_end(cb);
    res |= ccnb_element_end(cb);
    return res;
}

extern struct SyncHashCacheEntry *
SyncRootTopEntry(struct SyncRootStruct *root) {
    if (root->currentHash->length > 0) {
        struct ccn_charbuf *hash = root->currentHash;
        struct SyncHashCacheEntry *ent = SyncHashLookup(root->ch,
                                                        hash->buf,
                                                        hash->length);
        return ent;
    }
    return NULL;
}

extern enum SyncRootLookupCode
SyncRootLookupName(struct SyncRootStruct *root,
                   const struct ccn_charbuf *name) {
    int skip = 0;
    if (name == NULL)
        // and why were we called?
        return SyncRootLookupCode_error;
    if (root->namingPrefix != NULL) {
        skip = SyncPrefixMatch(root->namingPrefix, name, 0);
        if (skip < 0) return SyncRootLookupCode_error;
        if (skip == 0) return SyncRootLookupCode_none;
    }
    skip = SyncComponentCount(root->namingPrefix);
    enum SyncRootLookupCode res = SyncRootLookupCode_covered;
    struct SyncNameAccum *filter = root->filter;
    if (filter != NULL && filter->len > 0) {
        // if present, there are restrictions, relative to the prefix
        int i = 0;
        res = SyncRootLookupCode_none;
        for (i = 0; i < filter->len; i++) {
            struct ccn_charbuf *pat = filter->ents[i].name;
            int match = SyncPatternMatch(pat, name, skip);
            if (match < 0) {
                res = SyncRootLookupCode_error;
                break;
            }
            if (match > 0) {
                res = SyncRootLookupCode_covered;
                break;
            }
        }
        if (res == SyncRootLookupCode_none && root->base->debug > 16) {
            struct ccn_charbuf *uri = ccn_charbuf_create();
            ccn_uri_append(uri, name->buf, name->length, 0);
            char *str = ccn_charbuf_as_string(uri);
            sync_msg(root->base, "SyncRootLookupName, rejected %s", str);
            ccn_charbuf_destroy(&uri);
        }
    }
    return res;
}


