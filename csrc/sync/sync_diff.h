/*
 * @file sync/sync_protocol.h
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

#ifndef CCN_SyncDiff
#define CCN_SyncDiff

#include <ccn/charbuf.h>
#include <ccn/schedule.h>
#include "SyncHashCache.h"
#include "SyncRoot.h"
#include "SyncTreeWorker.h"

enum sync_diff_side {
    sync_diff_X,
    sync_diff_Y
};

struct sync_diff_data;

/**
 * sync_diff_add_closure is called from the differencing operation to note
 * a name difference for sdd.  If name == NULL then there are no more names
 * to be produced, and there should be no future references to sdd.
 * The data is private to the closure, and the client owns all storage
 * in the closure, except for sync_diff_data.
 */
struct sync_diff_add_closure {
    int (* add)(struct sync_diff_add_closure *ac,
                struct ccn_charbuf *name);
    struct sync_diff_data *diff_data;
    void *data;
};

struct sync_diff_fetch_data {
    struct sync_diff_fetch_data *next;
    struct ccn_closure *action;
    struct sync_diff_data *diff_data;
    struct SyncHashCacheEntry *hash_cache_entry;
    enum sync_diff_side side;
    int64_t startTime;
};

/**
 * sync_diff_get_closure is called from the differencing operation when a
 * node is required for a cache entry.  The cache entry (ce)
 * is where the hash is kept.  If the client cares, the side (X or Y) is also
 * supplied.
 * The data is private to the closure.
 */
struct sync_diff_get_closure {
    int (* get)(struct sync_diff_get_closure *gc,
                struct sync_diff_fetch_data *fd);
    struct sync_diff_data *diff_data;
    void *data;
};

enum sync_diff_state {
    sync_diff_state_init,
    sync_diff_state_preload,
    sync_diff_state_busy,
    sync_diff_state_error,
    sync_diff_state_done
};

struct sync_diff_data {
    
    /* items supplied by the client, not altered by sync_diff_stop */
    struct SyncRootStruct *root;
    struct ccn_charbuf *hashX;
    struct ccn_charbuf *hashY;
    struct sync_diff_add_closure *add_closure;
    struct sync_diff_get_closure *get_closure;
    void *client_data;
    
    /* items set as things progress, not reset by sync_diff_stop */
    enum sync_diff_state state;     /**< summary state of comparison */
    int64_t lastFetchOK;            /**< time marker for last successul node fetch */
    int64_t startTime;              /**< time marker for sync_diff_start */
    int64_t lastEnter;              /**< time marker for last compare step entry */
    int64_t lastMark;               /**< time marker for stall determination */
    int64_t maxHold;                /**< max time thread was held by compare */    
    int namesAdded;                 /**< names added during this comparison */
    int nodeFetchBusy;              /**< number of busy remote node fetches */
    int nodeFetchFailed;            /**< number of failed remote node fetches */
    
    /* Internal resources: supplied by sync_diff_start, reset by sync_diff_stop */
    struct SyncTreeWorkerHead *twX; /**< "local" tree walker state */
    struct SyncTreeWorkerHead *twY; /**< "remote" tree walker state */
    struct ccn_charbuf *cbX;        /**< "local" tree scratch */
    struct ccn_charbuf *cbY;        /**< "remote" tree scratch */
    struct sync_diff_fetch_data *fetchQ; /**< cache entries being fetched */
    struct ccn_scheduled_event *ev; /**< progress event */
};

/**
 * sync_done_closure is called from the update operation when the new tree root
 * is ready for installation (in ud->ceStop).
 * The data is private to the closure.
 */
struct sync_done_closure {
    int (* done)(struct sync_done_closure *dc);
    struct sync_update_data *update_data;
    void *data;
};

enum sync_update_state {
    sync_update_state_init,
    sync_update_state_busy,
    sync_update_state_error,
    sync_update_state_done
};

struct sync_update_data {

    /* items supplied by the client, not altered by sync_diff_stop */
    struct SyncRootStruct *root;
    struct SyncHashCacheEntry *ceStart; /*< entry for start hash (may be NULL) */
    struct sync_done_closure *done_closure;
    void *client_data;
    
    /* items set as things progress, not reset by sync_update_stop */
    enum sync_update_state state;
    struct SyncHashCacheEntry *ceStop;  /*< entry for end hash */
    int64_t startTime;
    int64_t entryTime;
    int64_t maxHold;
    int namesAdded;
    int nameLenAccum;
    
    /* Internal resources: supplied by sync_update_start, reset by sync_update_stop */
    int ax;
    struct SyncNameAccum *adding;   /**< sorted names from start_sync_update */
    struct SyncNameAccum *names;    /**< temp storage used while updating */
    struct SyncNodeAccum *nodes;    /**< temp storage used while updating */
    struct SyncTreeWorkerHead *tw;
    struct ccn_charbuf *cb;
    struct ccn_scheduled_event *ev; /**< progress event */
};

/**
 * sync_diff_start starts a differencing operation between two sync trees,
 * specified by sdd->hashX and sdd->hashY for the collection in sdd->root.
 * If sdd->hashX is not empty then there must be a valid cache entry for it.
 * If sdd->hashY is not empty then there must be a valid cache entry for it.
 * The root (in sdd->root) must be valid, and have a valid base and scheduler.
 * The client always owns the storage for sdd.
 *
 * The closure sdd->get is called when a sync tree node is needed to continue
 * the comparison, giving the name of the node (which has the hash as the
 * last component).  When the node has been fetched, the client should call
 * sync_diff_note_node, which will continue the comparison.
 * 
 * The closure sdd->add is called once for each name that is covered by hashY
 * but not by hashX.  When there are no more additions then it is called once
 * with name == NULL.  The sdd->add closure is called with a name that may not
 * be valid after the call completes, so the client must copy the name
 * if it needs to persist.
 *
 * Hint: If sdd->hashX is the empty hash (NULL or length == 0),
 *     then the differencing operation is simply an enumeration operation.
 *
 * @returns < 0 for failure, 0 if no difference running, > 0 for success.
 */
int
sync_diff_start(struct sync_diff_data *sdd);

/**
 * sync_diff_note_node is used to establish the result of the client closure
 * sdd->get, and restarts the comparison.
 * When the client closure sdd->get is called it should initiate the fetch or
 * construction of a sync tree node.  Depending on characteristics of the
 * client the sync tree node should be stored into either ce->ncL or ce->ncR,
 * and sync_diff_note_node should be called.  The flags in ce will be updated
 * by sync_diff_note_node.
 * @returns < 0 for failure, no fetch queued for ce.
 * @returns 0 for no action, ce == NULL || ce has no node.
 * @returns > 0 for success, fetch was queued and ce has a node.
 */
int
sync_diff_note_node(struct sync_diff_data *sdd,
                    struct SyncHashCacheEntry *ce);

/**
 * sync_diff_stop will stop the differencing operation if it has not completed.
 * A call to sdd->add will NOT take place from within this call.
 * Internal resources are released.
 * @returns < 0 for failure, 0 if already stopped, > 0 for success.
 */
int
sync_diff_stop(struct sync_diff_data *sdd);

/**
 * sync_update_start is called to start an update of ud->ceStart,
 * based on the names added via acc, to result in a tree with root hash
 * stored in ud->ceStop.
 * @returns < 0 for failure, 0 if update already running, > 0 for success.
 */
int
sync_update_start(struct sync_update_data *ud, struct SyncNameAccum *acc);

/**
 * sync_update_stop can be called to stop the update operation.
 * Internal resources are released.
 * @returns < 0 for failure, 0 if no update running, > 0 for success.
 */
int
sync_update_stop(struct sync_update_data *ud);

#endif
