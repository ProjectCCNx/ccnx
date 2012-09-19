/**
 * @file sync/SyncHashCache.c
 *  
 * Part of CCNx Sync.
 */
/*
 * Copyright (C) 2011-2012 Palo Alto Research Center, Inc.
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

#include "SyncBase.h"
#include "SyncHashCache.h"
#include "SyncNode.h"
#include "SyncRoot.h"
#include "SyncUtil.h"
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <ccn/ccn.h>
#include <ccn/loglevels.h>

static struct SyncHashCacheEntry *
localFreeEntry(struct SyncHashCacheEntry *ce) {
    ce->next = NULL;
    if (ce->ncL != NULL) SyncNodeDecRC(ce->ncL);
    if (ce->ncR != NULL) SyncNodeDecRC(ce->ncR);
    if (ce->hash != NULL) ccn_charbuf_destroy(&ce->hash);
    free(ce);
    return NULL;
}

extern struct SyncHashCacheEntry *
SyncHashLookup(struct SyncHashCacheHead *head,
                    const unsigned char *xp, ssize_t xs) {
    if (xp == NULL || xs <= 0) return NULL;
    uint32_t h = SyncSmallHash(xp, xs);
    uint32_t hx = h % head->mod;
    struct SyncHashCacheEntry *ent = head->ents[hx];
    head->probes = head->probes + 1;
    while (ent != NULL) {
        if (h == ent->small) {
            // probably equal, but we have to check
            ssize_t cmp = SyncCmpHashesRaw(xp, xs, ent->hash->buf, ent->hash->length);
            if (cmp == 0) return ent;
        }
        ent = ent->next;
    }
    head->misses = head->misses + 1;
    return NULL;
}

extern struct SyncHashCacheEntry *
SyncHashEnter(struct SyncHashCacheHead *head,
              const unsigned char *xp, ssize_t xs,
              enum SyncHashState set) {
    if (xp == NULL || xs <= 0) return NULL;
    uint32_t h = SyncSmallHash(xp, xs);
    uint32_t hx = h % head->mod;
    struct SyncHashCacheEntry *old = head->ents[hx];
    struct SyncHashCacheEntry *ent = old;
    head->probes = head->probes + 1;
    while (ent != NULL) {
        if (h == ent->small) {
            // probably equal, but we have to check
            ssize_t cmp = SyncCmpHashesRaw(xp, xs, ent->hash->buf, ent->hash->length);
            if (cmp == 0) {
                ent->state |= set;
                return ent;
            }
        }
        ent = ent->next;
    }
    head->misses = head->misses + 1;
    if (ent == NULL) {
        uintmax_t index = head->lastIndex + 1;
        head->lastIndex = index;
        head->probes = head->probes + 1;
        head->misses = head->misses + 1;
        ent = NEW_STRUCT(1, SyncHashCacheEntry);
        ent->lastUsed = SyncCurrentTime();
        ent->head = head;
        ent->next = old;
        ent->small = h;
        ent->hash = ccn_charbuf_create();
        ent->index = index;
        ccn_charbuf_append(ent->hash, xp, xs);
        head->ents[hx] = ent;
        head->len++;
    }
    ent->state |= set;
    return ent;
}

extern void
SyncHashRemoveEntry(struct SyncHashCacheHead *head,
                    struct SyncHashCacheEntry *ce) {
    if (ce != NULL) {
        uint32_t h = ce->small;
        uint32_t hx = h % head->mod;
        struct SyncHashCacheEntry *ent = head->ents[hx];
        struct SyncHashCacheEntry *lag = NULL;
        while (ent != NULL) {
            struct SyncHashCacheEntry *next = ent->next;
            if (ent == ce) {
                // unchain from main chain
                if (lag == NULL) head->ents[hx] = next;
                else lag->next = next;
                break;
            }
            lag = ent;
            ent = next;
        }
        if (ent == ce)
            ce = localFreeEntry(ce);
    }
}

extern void
SyncHashClearMarks(struct SyncHashCacheHead *head) {
    int hx = 0;
    for (hx = 0; hx < head->mod; hx++) {
        struct SyncHashCacheEntry *ent = head->ents[hx];
        while (ent != NULL) {
            enum SyncHashState state = ent->state;
            enum SyncHashState bit = state & SyncHashState_marked;
            ent->state = state - bit;
            ent = ent->next;
        }
    }
}

extern struct SyncHashCacheHead *
SyncHashCacheCreate(struct SyncRootStruct *root, uint32_t mod) {
    struct SyncHashCacheHead *head = NEW_STRUCT(1, SyncHashCacheHead);
    if (mod < 4) mod = 4;
    head->mod = mod;
    head->ents = NEW_ANY(mod, struct SyncHashCacheEntry *);
    head->root = root;
    return head;
}

extern struct SyncHashCacheHead *
SyncHashCacheFree(struct SyncHashCacheHead *head) {
    if (head != NULL) {
        size_t i = 0;
        size_t lim = head->mod;
        while (i < lim) {
            struct SyncHashCacheEntry *ent = head->ents[i];
            head->ents[i] = NULL;
            while (ent != NULL) {
                // TBD: error if busy?
                struct SyncHashCacheEntry *next = ent->next;
                ent = localFreeEntry(ent);
                ent = next;
            }
            i++;
        }
        free(head->ents);
        free(head);
    }
    return NULL;
}

extern int
SyncCacheEntryStore(struct SyncHashCacheEntry *ce) {
    // causes the cache entry to be saved to the repo
    int res = 0;
    if (ce == NULL) {
        // not an entry
        res = -1;
    } else if (ce->ncL == NULL || ce->ncL->cb == NULL
        || (ce->state & SyncHashState_stored)
        || (ce->state & SyncHashState_storing) == 0 ) {
        // not eligible
        res = 0;
    } else {
        struct SyncRootStruct *root = ce->head->root;
        struct SyncBaseStruct *base = root->base;
        struct ccn_charbuf *name = SyncNameForLocalNode(root, ce->hash);
        struct ccn_charbuf *content = ce->ncL->cb;
        
        // TBD: do we want to omit version and segment?
        res |= ccn_create_version(base->sd->ccn, name, CCN_V_NOW, 0, 0);
        res |= ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, 0);
        
        res = SyncLocalRepoStore(base, name, content, CCN_SP_FINAL_BLOCK);
        if (res > 0) {
            // clear the bits
            ce->state |= SyncHashState_stored;
            ce->state = ce->state - SyncHashState_storing;
        }
        ccn_charbuf_destroy(&name);
    }
    return res;
}

extern int
SyncCacheEntryFetch(struct SyncHashCacheEntry *ce) {
    // causes the cache entry to fetched from the repo
    char *here = "Sync.SyncCacheEntryFetch";
    int res = 0;
    if (ce == NULL) {
        // not an entry
        res = -1;
    } else if (ce->ncL != NULL) {
        // it's already here
        res = 0;
    } else if ((ce->state & SyncHashState_stored) == 0) {
        // it's never been stored, fail quietly
        res = -1;
    } else {
        // at this point we try to fetch it from the local repo
        // a failure should complain
        struct SyncRootStruct *root = ce->head->root;
        struct SyncBaseStruct *base = root->base;
        struct ccn_charbuf *name = SyncNameForLocalNode(root, ce->hash);
        struct ccn_charbuf *content = ccn_charbuf_create();
        char *why = "no fetch";
        struct ccn_parsed_ContentObject pcos;
        
        res = SyncLocalRepoFetch(base, name, content, &pcos);
        if (res >= 0) {
            // parse the object
            const unsigned char *xp = NULL;
            size_t xs = 0;
            // get the encoded node
            res = ccn_content_get_value(content->buf, content->length,
                                        &pcos, &xp, &xs);
            if (res < 0)
                why = "ccn_content_get_value failed";
            else {
                struct ccn_buf_decoder ds;
                struct ccn_buf_decoder *d = ccn_buf_decoder_start(&ds, xp, xs);
                struct SyncNodeComposite *nc = SyncAllocComposite(root->base);
                res |= SyncParseComposite(nc, d);
                if (res < 0) {
                    // failed, so back out of the allocations
                    why = "bad parse";
                    SyncFreeComposite(nc);
                    nc = NULL;
                } else {
                    res = 1;
                    SyncNodeIncRC(nc);
                    ce->ncL = nc;
                    ce->state |= SyncHashState_stored;
                }
            }
        }
        if (res < 0)
            if (root->base->debug >= CCNL_ERROR)
                SyncNoteUri(root, here, why, name);
        ccn_charbuf_destroy(&name);
        ccn_charbuf_destroy(&content);
    }
    return res;
}


