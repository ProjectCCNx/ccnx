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
 * construct a new base with methods at the SyncActions level
 */
struct SyncBaseStruct *
SyncNewBaseForActions(struct sync_plumbing *sd);

#endif
