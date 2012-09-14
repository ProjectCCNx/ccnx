/**
 * @file sync/SyncTreeWorker.c
 *  
 * Part of CCNx Sync.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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

#include "SyncMacros.h"
#include "SyncNode.h"
#include "SyncTreeWorker.h"
#include "SyncHashCache.h"
#include "SyncUtil.h"
#include <stdlib.h>
#include <string.h>
#include <strings.h>

extern void
SyncTreeWorkerInit(struct SyncTreeWorkerHead *head,
                   struct SyncHashCacheEntry *ent) {
    SyncTreeWorkerReset(head, 0);
    if (ent != NULL) {
        struct SyncTreeWorkerEntry *sp = &head->stack[0];
        sp->pos = 0;
        sp->cacheEntry = ent;
        ent->busy++;
        head->level = 1;
    }
}

extern struct SyncTreeWorkerHead *
SyncTreeWorkerCreate(struct SyncHashCacheHead *cache,
                     struct SyncHashCacheEntry *ent) {
    struct SyncTreeWorkerHead * head = NEW_STRUCT(1, SyncTreeWorkerHead);
    int lim = 4;
    struct SyncTreeWorkerEntry * stack = NEW_STRUCT(lim, SyncTreeWorkerEntry);
    head->stack = stack;
    head->lim = lim;
    head->cache = cache;
    SyncTreeWorkerInit(head, ent);
    return head;
}

extern struct SyncTreeWorkerEntry *
SyncTreeWorkerTop(struct SyncTreeWorkerHead *head) {
    if (head->level <= 0) return NULL;
    struct SyncTreeWorkerEntry *stack = head->stack;
    struct SyncTreeWorkerEntry *ent = &stack[head->level - 1];
    return ent;
}

extern struct SyncNodeElem *
SyncTreeWorkerGetElem(struct SyncTreeWorkerHead *head) {
    struct SyncTreeWorkerEntry *ent = SyncTreeWorkerTop(head);
    if (ent == NULL) return NULL;
    struct SyncHashCacheEntry *ce = ent->cacheEntry;
    if (ce == NULL) return NULL;
    struct SyncNodeComposite *nc = ce->ncL;
    if (nc == NULL) nc = ce->ncR;
    if (nc == NULL) return NULL;
    int pos = ent->pos;
    if (pos < 0 || pos >= nc->refLen) return NULL;
    struct SyncNodeElem *ref = &nc->refs[pos];
    return ref;
}

extern struct SyncTreeWorkerEntry *
SyncTreeWorkerPush(struct SyncTreeWorkerHead *head) {
    struct SyncNodeElem *ref = SyncTreeWorkerGetElem(head);
    if (ref == NULL || (ref->kind & SyncElemKind_leaf))
        return NULL;
    struct SyncTreeWorkerEntry *ent = SyncTreeWorkerTop(head);
    struct SyncHashCacheEntry *ce = ent->cacheEntry;
    if (ce == NULL) return NULL;
    struct SyncNodeComposite *nc = ce->ncL;
    if (nc == NULL) nc = ce->ncR;
    if (nc == NULL) return NULL;
    struct ccn_buf_decoder cbd;
    struct ccn_buf_decoder *cb = SyncInitDecoderFromOffset(&cbd, nc,
                                                           ref->start,
                                                           ref->stop);
    const unsigned char *xp = NULL;
    ssize_t xs = 0;
    SyncGetHashPtr(cb, &xp, &xs);
    ce = SyncHashLookup(head->cache, xp, xs);
    if (ce == NULL)
        // no entry?  this is not so good
        return NULL;
    struct SyncTreeWorkerEntry *stack = head->stack;
    int level = head->level;
    int oLim = head->lim;
    if (level > oLim) {
        // something bad has happened
        return NULL;
    }
    if (level == oLim) {
        // first, expand the stack to get enough room
        int nLim = oLim + oLim / 2 + 4;
        struct SyncTreeWorkerEntry *nStack = NEW_STRUCT(nLim,
                                                        SyncTreeWorkerEntry);
        memcpy(nStack, stack, level*sizeof(struct SyncTreeWorkerEntry));
        free(stack);
        stack = nStack;
        head->stack = nStack;
        head->lim = nLim;
    }
    // now we can push the node
    head->level = level+1;
    ent = &stack[level];
    ent->pos = 0;
    ent->count = 0;
    ent->cacheEntry = ce;
    ce->busy++;
    head->visits++;
    return ent;
}

extern struct SyncTreeWorkerEntry *
SyncTreeWorkerPop(struct SyncTreeWorkerHead *head) {
    int level = head->level;
    if (level <= 0) return NULL;
    level--;
    struct SyncTreeWorkerEntry *stack = head->stack;
    struct SyncTreeWorkerEntry *ep = &stack[level];
    struct SyncHashCacheEntry *ce = ep->cacheEntry;
    if (ce != NULL && ce->busy > 0) ce->busy--;
    head->level = level;
    if (level <= 0) return NULL;
    return &head->stack[level - 1];
}

extern void
SyncTreeWorkerReset(struct SyncTreeWorkerHead *head, int level) {
    if (head == NULL) return;
    while (head->level > level) {
        SyncTreeWorkerPop(head);
    }
    if (level > 0) {
        head->stack[head->level - 1].pos = 0;
    }
    head->state = SyncTreeWorkerState_init;
}

extern struct SyncTreeWorkerHead *
SyncTreeWorkerFree(struct SyncTreeWorkerHead *head) {
    if (head != NULL) {
        SyncTreeWorkerReset(head, 0);
        free(head->stack);
        free(head);
    }
    return NULL;
}

extern enum SyncCompareResult
SyncTreeLookupName(struct SyncTreeWorkerHead *head,
                   struct ccn_charbuf *name,
                   int minLevel) {
    
    enum SyncCompareResult cr = SCR_inside;
    while (head->level > minLevel) {
        struct SyncTreeWorkerEntry *ent = SyncTreeWorkerTop(head);
        struct SyncHashCacheEntry *ce = ent->cacheEntry;
        if (ce == NULL) return SCR_error;
        struct SyncNodeComposite *nc = ce->ncL;
        if (nc == NULL) nc = ce->ncR;
        if (nc == NULL) return SCR_missing;
        int lim = nc->refLen;
        if (ent->pos >= lim) {
            // done with the current level, go back to the previous level
            ent = SyncTreeWorkerPop(head);
            if (ent == NULL) break;
            ent->pos++;
        } else {
            
            if (ent->pos == 0) {
                // need to check the min and max of the current node
                enum SyncCompareResult cr = SyncNodeCompareMinMax(nc, name);
                if (cr == SCR_after) {
                    // not in this node at all, so pop out
                    ent->pos = lim;
                } else if (cr != SCR_inside) return cr;
            }
            if (ent->pos < lim) {
                struct SyncNodeElem *ep = &nc->refs[ent->pos];
                if (ep->kind & SyncElemKind_leaf) {
                    // a leaf, so the element name is inline
                    cr = SyncNodeCompareLeaf(nc, ep, name);
                    if (cr != SCR_after) return cr;
                    ent->pos++;
                } else {
                    // a node, so try this recursively
                    ent = SyncTreeWorkerPush(head);
                    if (ent == NULL) {
                        return SCR_error;
                    }
                }
            }
        }
        
    }
    return SCR_after;
}

extern enum SyncCompareResult
SyncTreeGenerateNames(struct SyncTreeWorkerHead *head,
                      struct SyncNameAccum *accum,
                      int minLevel) {
    
    while (head->level > minLevel) {
        struct SyncTreeWorkerEntry *ent = SyncTreeWorkerTop(head);
        struct SyncHashCacheEntry *ce = ent->cacheEntry;
        if (ce == NULL) return SCR_error;
        struct SyncNodeComposite *nc = ce->ncL;
        if (nc == NULL) nc = ce->ncR;
        if (nc == NULL) return SCR_missing;
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
                struct ccn_buf_decoder bd;
                struct ccn_buf_decoder *d = SyncInitDecoderFromOffset(&bd,
                                                                      nc,
                                                                      ep->start,
                                                                      ep->stop);
                struct ccn_charbuf *cb = ccn_charbuf_create();
                int res = SyncAppendElementInner(cb, d);
                if (res < 0) {
                    // that did not work well
                    ccn_charbuf_destroy(&cb);
                    return SCR_error;
                }
                SyncNameAccumAppend(accum, cb, 0);
                ent->pos++;
            } else {
                ent = SyncTreeWorkerPush(head);
                if (ent == NULL) {
                    return SCR_error;
                }                
            }
        }
        
    }
    return SCR_after;
}

extern int
SyncTreeMarkReachable(struct SyncTreeWorkerHead *head, int minLevel) {
    int count = 0;
    while (head->level > minLevel) {
        struct SyncTreeWorkerEntry *ent = SyncTreeWorkerTop(head);
        if (ent == NULL) break;
        struct SyncHashCacheEntry *ce = ent->cacheEntry;
        if (ce == NULL) break;
        ce->state |= SyncHashState_marked;
        count++;
        struct SyncNodeComposite *nc = ce->ncL;
        if (nc == NULL) nc = ce->ncR;
        if (nc == NULL) break;
        int lim = nc->refLen;
        if (ent->pos >= lim) {
            // done with the current level, go back to the previous level
            ent = SyncTreeWorkerPop(head);
            if (ent == NULL) break;
            ent->pos++;
        } else {
            struct SyncNodeElem *ep = &nc->refs[ent->pos];
            if (ep->kind & SyncElemKind_leaf) {
                // a leaf, so skip it
                ent->pos++;
            } else {
                // a node, so push into it
                ent = SyncTreeWorkerPush(head);
                if (ent == NULL) break;
            }
        }
    }
    return count;
}


