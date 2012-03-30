/**
 * @file sync/SyncActions.h
 *  
 * Part of CCNx Sync.
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

#ifndef CCN_SyncActions
#define CCN_SyncActions

#include <ccn/charbuf.h>
#include "SyncBase.h"
#include "SyncRoot.h"
#include "SyncUtil.h"

struct SyncTreeWorkerHead;  // from SyncTreeWorker.h
struct SyncCompareData;     // from SyncActions.c

enum SyncRegisterActionKind {
    SRI_Kind_None,
    SRI_Kind_AdviseInt,  /**< root advise handler */
    SRI_Kind_FetchInt,   /**< node fetch handler */
    SRI_Kind_RootAdvise, /**< root advise request */
    SRI_Kind_NodeFetch,  /**< node fetch request */
    SRI_Kind_RootStats,  /**< root stats request */
    SRI_Kind_Content     /**< general content */
};

enum SyncActionState {
    SyncActionState_init,
    SyncActionState_sent,
    SyncActionState_loose,
    SyncActionState_error,
    SyncActionState_done
};

struct SyncActionData {
    struct SyncActionData *next;
    struct SyncRootStruct *root;
    struct SyncHashCacheEntry *ce;
    struct SyncCompareData *comp;
    void *client_handle;
    struct ccn_charbuf *prefix;
    struct ccn_charbuf *hash;
    int64_t startTime;
    enum SyncRegisterActionKind kind;
    enum SyncActionState state;
    int skipToHash;
};

/**
 * starts a slice enumeration for the root
 * @returns < 0 for error, 0 if some enumeration is busy, 1 for success
 */
int
SyncStartSliceEnum(struct SyncRootStruct *root);

/**
 * starts a periodic wakeup that maintains state across all roots
 * @returns < 0 for error, >= 0 for success
 */
int
SyncStartHeartbeat(struct SyncBaseStruct *base);

/**
 * starts a remote fetch of the given node, based on the hash
 * comp may be NULL if this is not for a compare
 * @returns < 0 for error, >= 0 for success
 */
int
SyncStartNodeFetch(struct SyncRootStruct *root,
                   struct SyncHashCacheEntry *ce,
                   struct SyncCompareData *comp);

/**
 * starts a remote fetch of the given name
 * comp may be NULL if this is not for a compare
 * @returns < 0 for error, >= 0 for success
 */
int
SyncStartContentFetch(struct SyncRootStruct *root,
                      struct ccn_charbuf *name,
                      struct SyncCompareData *comp);

/**
 * Adds the given name to any applicable roots.
 * Use item == 0 to ignore accession number.
 * @returns < 0 for failure, number of additions to roots for success.
 */
int
SyncAddName(struct SyncBaseStruct *base, struct ccn_charbuf *name, ccnr_accession item);

/**
 * Creates a new slice from a full name.
 * The name must start with base->priv->sliceCmdPrefix.
 * @returns < 0 if an error occurred, otherwise the new root number.
 */
int
SyncHandleSlice(struct SyncBaseStruct *base, struct ccn_charbuf *name);

/**
 * registers interests associated with the given root
 * these include %C1.S.RA (Root Advise) and %C1.S.NF (Node Fetch) interests
 * additional interests may be registered as the protocol evolves
 * @returns < 0 for error, >= 0 for success
 */
int
SyncRegisterInterests(struct SyncRootStruct *root);

/**
 * send a root advise request for the given root
 * only one at a time may be outstanding
 * @returns < 0 for error, >= 0 for OK
 */
int
SyncSendRootAdviseInterest(struct SyncRootStruct *root);

/**
 * updates the root with the current root->namesToAdd
 * @returns < 0 for error, >= 0 for OK
 */
int
SyncUpdateRoot(struct SyncRootStruct *root);

/**
 * initiates a compare action with the given remote hash
 * @returns < 0 for error, >= 0 for OK
 */
int
SyncStartCompareAction(struct SyncRootStruct *root, struct ccn_charbuf *hashR);

#endif
