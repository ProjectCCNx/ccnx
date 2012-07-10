/**
 * @file sync/SyncHashCache.h
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

#ifndef CCN_SyncHashCache
#define CCN_SyncHashCache

struct SyncRootStruct; // defined in SyncRoot

enum SyncHashState {
    SyncHashState_null = 0,      /**< empty, not much known */
    SyncHashState_local = 1,     /**< a local node exists */
    SyncHashState_remote = 2,    /**< a remote hash has been seen */
    SyncHashState_fetching = 4,  /**< remote node is being fetched */
    SyncHashState_covered = 8,   /**< remote hash known covered by the local root */
    SyncHashState_storing = 16,  /**< local node is queued to be stored */
    SyncHashState_stored = 32,   /**< local node has been stored */
    SyncHashState_marked = 64    /**< cache entry has been marked */
};

struct SyncHashCacheHead {
    struct SyncRootStruct *root;        /**< the parent root */
    uintmax_t probes;                   /**< number of cache probes */
    uintmax_t misses;                   /**< number of cache misses */
    uintmax_t lastIndex;                /**< assigned by order of creation */
    size_t len;                         /**< number of entries */
    uint32_t mod;                       /**< the mod to use */
    struct SyncHashCacheEntry **ents;   /**< the vector of hash chains */
};

struct SyncHashCacheEntry {
    struct SyncHashCacheHead *head;     /**< the parent head */
    struct SyncHashCacheEntry *next;    /**< the next entry in the hash chain */
    struct SyncHashCacheEntry *storing; /**< the next entry in the storing chain */
    enum SyncHashState state;           /**< state bits */
    uintmax_t index;                    /**< assigned by order of creation */
    uint32_t busy;                      /**< the tree worker usage count */
    uint32_t small;                     /**< the small hash */
    struct ccn_charbuf *hash;           /**< hash used to reach this entry */
    struct SyncNodeComposite *ncL;      /**< the local node in memory */
    struct SyncNodeComposite *ncR;      /**< some remote node in memory */
    int64_t lastUsed;                   /**< time when entry last used in compare */
    int64_t lastLocalFetch;             /**< time when local entry last fetched */
    int64_t lastRemoteFetch;            /**< time when remote entry last fetched */
};

/**
 * lookup a full hash in a hash table (raw contents, no tag)
 * @returns entry if it exists
 */
struct SyncHashCacheEntry *
SyncHashLookup(struct SyncHashCacheHead *head,
               const unsigned char *xp, ssize_t xs);

/**
 * based on the raw hash, ensure that a remote cache entry exists
 * ent->state |= set
 */
struct SyncHashCacheEntry *
SyncHashEnter(struct SyncHashCacheHead *head,
              const unsigned char *xp, ssize_t xs,
              enum SyncHashState set);

/**
 * remove the entry (if present)
 */
void
SyncHashRemoveEntry(struct SyncHashCacheHead *head,
                    struct SyncHashCacheEntry *ce);

/**
 * clear all marks
 */
void
SyncHashClearMarks(struct SyncHashCacheHead *head);

/**
 * create a new hash table with the given modulus (mod == 0 uses a default)
 */
struct SyncHashCacheHead *
SyncHashCacheCreate(struct SyncRootStruct *root, uint32_t mod);

/**
 * frees the cache resources
 * caller must ensure no further use of the cache
 * @returns NULL
 */
struct SyncHashCacheHead *
SyncHashCacheFree(struct SyncHashCacheHead *head);


/**
 * fetches the cache entry
 * to be eligible, ce != NULL && ce->ncL != NULL
 * && (ce->state & SyncHashState_stored) == 1
 * @returns < 0 for failure, 0 if not eligible, and > 0 for success
 */
int
SyncCacheEntryFetch(struct SyncHashCacheEntry *ce);

/**
 * stores the cahe entry to the repo
 * to be eligible, ce != NULL && ce->ncL == NULL
 * && (ce->state & SyncHashState_stored) == 0
 * @returns < 0 for failure, 0 if not eligible, and > 0 for success
 */
int
SyncCacheEntryStore(struct SyncHashCacheEntry *ce);

#endif
