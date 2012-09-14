/**
 * @file sync/SyncPrivate.h
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

#ifndef CCN_SyncPrivate
#define CCN_SyncPrivate

#include "SyncBase.h"
#include "SyncRoot.h"
#include "SyncUtil.h"

struct SyncHashCacheEntry;              // from SyncHashCache.h

struct SyncMethodsList {
    struct SyncMethodsList *next;
    struct sync_plumbing_sync_methods *sync_methods;
};

struct SyncPrivate {
    struct SyncMethodsList *saveMethods;
    struct SyncRootStruct *rootHead;
    int nRoots;
    int useRepoStore;
    int stableEnabled;
    struct SyncNameAccum *topoAccum;
    struct SyncNameAccum *prefixAccum;
    struct SyncNameAccumList *filters;
    struct ccn_charbuf *localHostPrefix;
    struct ccn_charbuf *sliceCmdPrefix;
    struct SyncHashCacheEntry *storingHead;
    struct SyncHashCacheEntry *storingTail;
    struct ccn_indexbuf *comps;     /*< used by SyncNotifyContent */
    int nStoring;
    int64_t lastCacheClean;
    int64_t lastFenceTime;
    uint64_t lastFenceVal;
    int sliceEnum;
    int sliceBusy;
    int fauxErrorTrigger;
    int syncActionsPrivate;
    int heartbeatMicros;        /*< microseconds between action heartbeats */
    int rootAdviseFresh;        /*< seconds for root advise response freshness */
    int rootAdviseLifetime;     /*< seconds for root advise interest lifetime */
    int fetchLifetime;          /*< seconds for node fetch interest lifetime */
    int maxFetchBusy;           /*< max # of fetches per root busy */
    int comparesBusy;           /*< # of roots doing compares */
    int maxComparesBusy;        /*< max # of roots doing compares */
    int deltasLimit;            /*< # of bytes permitted for RootAdvise delta mode */
    int syncScope;              /*< default sync scope */
};

struct SyncRootStats {
    uint64_t updatesDone;           /*< number of sync tree root updates done */
    uint64_t lastUpdateMicros;      /*< last elapsed update time (microsecs) */
    uint64_t comparesDone;          /*< number of sync tree compares completed */
    uint64_t lastCompareMicros;     /*< last elapsed compare time (microsecs) */
    uint64_t nodesCreated;          /*< number of new nodes created */
    uint64_t nodesShared;           /*< number of nodes shared */
    
    uint64_t rootAdviseSent;        /*< number of RootAdvise interests sent */
    uint64_t nodeFetchSent;         /*< number of NodeFetch interests sent */
    uint64_t contentFetchSent;      /*< number of content fetch interests sent */
    
    uint64_t rootAdviseSeen;        /*< number of RootAdvise interests received */
    uint64_t nodeFetchSeen;         /*< number of NodeFetch interests received */
    
    uint64_t rootAdviseReceived;    /*< number of RootAdvise responses received */
    uint64_t nodeFetchReceived;     /*< number of NodeFetch responses received */
    uint64_t contentFetchReceived;  /*< number of content objects received */
    
    uint64_t rootAdviseBytes;       /*< number of bytes for RootAdvise responses */
    uint64_t nodeFetchBytes;        /*< number of bytes for NodeFetch responses */
    uint64_t contentFetchBytes;     /*< number of bytes for content objects */

    uint64_t rootAdviseTimeout;     /*< number of RootAdvise response timeouts */
    uint64_t nodeFetchTimeout;      /*< number of NodeFetch response timeouts */
    uint64_t contentFetchTimeout;   /*< number of content object response timeouts */
    
    uint64_t rootAdviseFailed;      /*< number of RootAdvise response failures */
    uint64_t nodeFetchFailed;       /*< number of NodeFetch response failures */
    uint64_t contentFetchFailed;    /*< number of content object response failures */
    
};

struct SyncRootDeltas {
    struct SyncRootDeltas *next;        /*< link to next update */
    struct SyncHashCacheEntry *ceStart; /*< entry for start hash (may be NULL) */
    struct SyncHashCacheEntry *ceStop;  /*< entry for end hash */
    int64_t whenMade;                   /*< when created */
    int64_t whenSent;                   /*< when last sent */
    int deltasCount;                    /*< number of names in coding */
    int closed;                         /*< 1 if coding is complete */
    struct ccn_charbuf *coding;         /*< coding for updates */
    struct ccn_charbuf *name;           /*< name used for reply */
    struct ccn_charbuf *cob;            /*< signed response buffer */
};

struct SyncRootPrivate {
    struct SyncRootStats *stats;
    struct SyncHashCacheEntry *ceCurrent; /*< entry for current root hash (may be NULL) */
    struct SyncHashInfoList *remoteSeen;
    struct SyncHashInfoList *localMade;
    struct SyncRootDeltas *deltasHead;  /*< pointer to eldest update */
    struct SyncRootDeltas *deltasTail;  /*< pointer to youngest update */
    int nDeltas;                        /*< number of deltas in the list */
    struct SyncNameAccum *remoteDeltas; /*< delta names from remote sources */
    int syncScope;                      /*< scope to be used for sync */
    int sliceBusy;
    int64_t lastAdvise;
    int64_t lastUpdate;
    int64_t lastStable;
    int64_t lastHashChange;
    uint64_t max_seq_num_seen;          /*< max seq_num seen during adding */
    uint64_t max_seq_num_build;         /*< max seq_num seen during building */
    uint64_t max_seq_num_stable;        /*< set when root becomes stable (& stored) */
    int adviseNeed;
    struct SyncHashCacheEntry *lastLocalSent;
    size_t currentSize;
    size_t prevAddLen;
};

#endif
