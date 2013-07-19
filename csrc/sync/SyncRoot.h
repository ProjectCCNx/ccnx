/**
 * @file sync/SyncRoot.h
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

#ifndef CCN_SyncRoot
#define CCN_SyncRoot

#include <ccn/ccn.h>

struct SyncHashCacheHead;  // defined in SyncHashCache.h
struct SyncNameAccum;      // defined in SyncUtil.h
struct SyncBaseStruct;     // defined in SyncBase.h
struct SyncRootPrivate;    // private to SyncRoot.c

/**
 * A SyncRootStruct object holds the necessary data for a root sync tree.
 */
struct SyncRootStruct {
    unsigned rootId;                      /**< root Id for reporting */
    struct SyncBaseStruct *base;          /**< Sync Agent base */
    struct SyncRootStruct *next;          /**< next root in our list */
    struct SyncRootPrivate *priv;         /**< private to SyncRoot */
    struct SyncHashCacheHead *ch;         /**< cache head */
    struct ccn_charbuf *topoPrefix;       /**< Sync Protocol topo prefix */
    struct ccn_charbuf *namingPrefix;     /**< Sync Protocol naming prefix */
    struct SyncNameAccum *filter;         /**< filter clauses */
    struct ccn_charbuf *currentHash;      /**< current top-level cache hash */
    struct SyncNameAccum *namesToAdd;     /**< names needing addition to root */
    struct SyncNameAccum *namesToFetch;   /**< names needing contents fetch */
    void *actions;                        /**< data for pending interests */
    void *compare;                        /**< data for doing sync tree comparison */
    void *update;                         /**< data for doing sync tree updates */
    struct ccn_charbuf *sliceCoding;      /**< ccnb encoding for the description */
    struct ccn_charbuf *sliceHash;        /**< the raw hash of the sliceCoding */
    struct ccn_charbuf *heldRAInterest;   /**< received ra interest */ 
};

/**
 * namesToAdd has the names where content is known to be present.  These names
 * should come from SyncNotifyContent.
 * The name storage belongs to the root.
 *
 * namesToFetch has the names where content should be fetched.  Once content is
 * fetched and stored to the repo the names should be appended to namesToAdd.
 * The name storage belongs to the root.
 */

///////////////////////////////////////////////////////
// Routines for working with sync tree roots
///////////////////////////////////////////////////////

/**
 * Creates a new root structure and adds it to the base.
 * The syncScope will be used for sync control interests (-1 for unscoped).
 * The topoPrefix and namingPrefix will be copied and canonicalized.
 * The filter (and the names in it) will also be copied and canonicalized.
 * Canonicalized data is owned by the base.
 * @returns the new root object
 */
struct SyncRootStruct *
SyncAddRoot(struct SyncBaseStruct *base,
            int syncScope,
            const struct ccn_charbuf *topoPrefix,
            const struct ccn_charbuf *namingPrefix,
            struct SyncNameAccum *filter);

/**
 * Removes the root from the base, and frees up associated storage.
 * Requires that there are no active comparisons.
 * Deactivates all pending interests.
 * @returns NULL if the root was removed, the root itself if not removed.
 */
struct SyncRootStruct *
SyncRemRoot(struct SyncRootStruct *root);

/**
 * Parse a content object representing a config slice,
 * and if successful add it to the base.
 * @returns the new root if successful, NULL otherwise.
 */
struct SyncRootStruct *
SyncRootDecodeAndAdd(struct SyncBaseStruct *base,
                     struct ccn_buf_decoder *d);

/**
 * Appends the ccnb encoding for a config slice to the provided cb.
 * @returns -1 for failure, 0 for success.
 */
int
SyncRootAppendSlice(struct ccn_charbuf *cd, struct SyncRootStruct *root);

enum SyncRootLookupCode {
    SyncRootLookupCode_none,        /**< not covered by this root */
    SyncRootLookupCode_covered,     /**< covered by this root */
    SyncRootLookupCode_error        /**< error in the name or the state */
};
    
/**
 * @returns the top entry, if the root hash has been established for this root,
 * otherwise returns NULL.
 */
struct SyncHashCacheEntry *
SyncRootTopEntry(struct SyncRootStruct *root);

/**
 * Tests to see if the name is covered by this root.
 * Useful for testing full names given by the Repo.
 * The topoPrefix does not participate, but the filter does.
 * @returns a code indicating the result
 */
enum SyncRootLookupCode
SyncRootLookupName(struct SyncRootStruct *root,
                    const struct ccn_charbuf *name);

#endif
