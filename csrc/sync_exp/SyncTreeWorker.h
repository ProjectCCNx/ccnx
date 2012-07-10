/**
 * @file sync/SyncTreeWorker.h
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

#ifndef CCN_SyncTreeWorker
#define CCN_SyncTreeWorker

#include "SyncHashCache.h"

struct SyncNameAccum;

/**
 * SyncTreeWorker maintains the state for walking a local sync tree root,
 * This is important becase the state cannot be simply kept in a call stack.
 */

enum SyncTreeWorkerState {
    SyncTreeWorkerState_init,
    SyncTreeWorkerState_needFetch,
    SyncTreeWorkerState_fetching,
    SyncTreeWorkerState_error
};

struct SyncTreeWorkerHead {
    struct SyncHashCacheHead *cache;
    enum SyncTreeWorkerState state;
    intmax_t visits;
    int level;
    int lim;
    struct SyncTreeWorkerEntry *stack;
};

struct SyncTreeWorkerEntry {
    ssize_t pos;
    ssize_t count;
    struct SyncHashCacheEntry *cacheEntry;
};


/**
 * create a new tree worker, based on the given cache
 * ent != NULL: initialize from the given node
 * ent == NULL: create an empty worker, to be externally initialized
 * @returns the new worker
 */
struct SyncTreeWorkerHead *
SyncTreeWorkerCreate(struct SyncHashCacheHead *cache,
                     struct SyncHashCacheEntry *ent);

/**
 * initialize an existing worker from the cache entry
 * resulting level will be 1
 */
void
SyncTreeWorkerInit(struct SyncTreeWorkerHead *head,
                   struct SyncHashCacheEntry *ent);

/**
 * requires that the node be present
 * @returns the SyncNodeElem from the current position
 * @returns NULL if not valid
 */
struct SyncNodeElem *
SyncTreeWorkerGetElem(struct SyncTreeWorkerHead *head);

/**
 * @returns the entry at the top of the stack
 * @returns NULL if no valid current entry
 */
struct SyncTreeWorkerEntry *
SyncTreeWorkerTop(struct SyncTreeWorkerHead *head);

/**
 * pushes into the node at the current position
 * @returns the cache entry for the child (if any)
 * pushing where there is no node has no effect and returns NULL
 */
struct SyncTreeWorkerEntry *
SyncTreeWorkerPush(struct SyncTreeWorkerHead *head);

/**
 * pops the stack and returns the top entry
 * popping an empty stack has no effect and returns NULL
 */
struct SyncTreeWorkerEntry *
SyncTreeWorkerPop(struct SyncTreeWorkerHead *head);

/**
 * Reset the worker to the given level (or the current level if that is less).
 * Resets the position at the new level to 0.
 */
void
SyncTreeWorkerReset(struct SyncTreeWorkerHead *head, int level);

/**
 * Free the storage for the worker.
 * @returns NULL.
 */
struct SyncTreeWorkerHead *
SyncTreeWorkerFree(struct SyncTreeWorkerHead *head);

/**
 * Lookup the name in the tree, starting at the current point,
 * with backtrack while the level is greater than the given minimum.
 * The lookup can be restarted when a missing node is encountered.
 * When SCR_missing is returned, SyncTreeWorkerTop(head) is missing.
 */
enum SyncCompareResult
SyncTreeLookupName(struct SyncTreeWorkerHead *head,
                   struct ccn_charbuf *name,
                   int minLevel);

/**
 * Generate the names in the tree, starting at the current point,
 * with backtrack while the level is greater than the given minimum.
 * When SCR_missing is returned, SyncTreeWorkerTop(head) is missing.
 */
enum SyncCompareResult
SyncTreeGenerateNames(struct SyncTreeWorkerHead *head,
                      struct SyncNameAccum *accum,
                      int minLevel);

/**
 * Mark all reachable cache entries using the current tree worker head,
 * with backtrack while the level is greater than the given minimum.
 * When SCR_missing is returned, SyncTreeWorkerTop(head) is missing.
 * @returns the number of marked nodes.
 */
int
SyncTreeMarkReachable(struct SyncTreeWorkerHead *head, int minLevel);


#endif
