/**
 * @file sync/SyncNode.h
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

/**
 * SyncNode is the basic support for node objects in Sync.
 */

#ifndef CCN_SyncNode
#define CCN_SyncNode

#include <stdio.h>
#include <ccn/ccn.h>
#include "SyncMacros.h"

struct SyncBaseStruct;  // defined in SyncBase.h
struct SyncRootStruct;  // defined in SyncRoot.h

typedef enum {
    SyncNodeKind_zero = 0,  /**< no bits set */
    SyncNodeKind_mark = 1   /**< mark bit (TBD) */
} SyncNodeKind;

typedef enum {
    SyncElemKind_node = 0,  /**< node */
    SyncElemKind_leaf = 1   /**< leaf */
} SyncElemKind;

struct SyncNodeElem {
    SyncElemKind kind;  /**< leaf/composite flag */
    ssize_t start;      /**< start of element encoding */
    ssize_t stop;       /**< stop of element encoding */
};

/**
 * A SyncLongHashStruct is used to accumulate a combined hash code
 * The pos field is the lowest index of a valid byte (bytes are accumulated
 * from high to low index).
 */
struct SyncLongHashStruct {
    int pos;
    unsigned char bytes[MAX_HASH_BYTES];
};

/**
 * A SyncNodeComposite object holds the necessary data for a sync tree node.
 * It is the instantiated version, and there are routines for converting to
 * and from the ccnb encoded version, which has a very different format than
 * the type presented here.
 *
 * This type may be used while building a new node from components, and it may
 * be used for a node representation parsed from an external ccnb encoding.
 * 
 */
struct SyncNodeComposite {
    struct SyncBaseStruct *base;
    SyncNodeKind kind;    /**< kind bits */
    int rc;               /**< reference count */
    int err;              /**< any error saved here */
    unsigned leafCount;   /**< leaf count (includes this node) */
    unsigned treeDepth;   /**< max tree depth (includes this node) */
    unsigned byteCount;   /**< byte count sum for child nodes (this node NOT included) */
    
    int refLen;           /**< number of references */
    int refLim;           /**< space allocated for references */
    struct SyncNodeElem *refs;    /**< pointer to references array */
    struct ccn_charbuf *cb;       /**< pointer to ccnb encoding */
    struct SyncLongHashStruct longHash;  /**< space for accumulated hash */
    struct ccn_charbuf *hash;     /**< combined hash (no tag, requires SyncEndComposite) */
    struct ccn_charbuf *minName;  /**< minimum name */
    struct ccn_charbuf *maxName;  /**< maximum name */
    struct ccn_charbuf *content;  /**< the signed content node (may be NULL) */
};

/**
 * Sets the error field when there is a processing error.
 */
int
SyncSetCompErr(struct SyncNodeComposite *nc, int val);

/**
 * Tests the error field for an error returns 0 for no error != 0 for an error).
 */
int
SyncCheckCompErr(struct SyncNodeComposite *nc);

/**
 * Makes a decoder from an offset range using the node charbuf.
 */
struct ccn_buf_decoder *
SyncInitDecoderFromOffset(struct ccn_buf_decoder *d,
                          struct SyncNodeComposite *nc,
                          ssize_t start, ssize_t stop);

/**
 * Makes a decoder from an element.
 */
struct ccn_buf_decoder *
SyncInitDecoderFromElem(struct ccn_buf_decoder *d,
                        struct SyncNodeComposite *nc,
                        struct SyncNodeElem *ep);


/**
 * Increments the reference count
 */
void
SyncNodeIncRC(struct SyncNodeComposite *nc);

/**
 * Decrements the reference count
 * @returns nc if the resulting count is > 0.
 * @returns NULL if the resulting count == 0 (and frees the node).
 */
struct SyncNodeComposite *
SyncNodeDecRC(struct SyncNodeComposite *nc);


////////////////////////////////////////
// Routines for comparison support
////////////////////////////////////////

enum SyncCompareResult {
    SCR_before,
    SCR_min,
    SCR_inside,
    SCR_max,
    SCR_after,
    SCR_missing,
    SCR_error
};

/**
 * Compares a name against the min and max names in the node.
 */
enum SyncCompareResult
SyncNodeCompareMinMax(struct SyncNodeComposite *nc, struct ccn_charbuf *name);

/**
 * Compares a name against the leaf in the element.
 */
enum SyncCompareResult
SyncNodeCompareLeaf(struct SyncNodeComposite *nc,
                    struct SyncNodeElem *ep,
                    struct ccn_charbuf *name);

////////////////////////////////////////
// Routines for building CompositeNodes
////////////////////////////////////////

/**
 * resets a composite node to its initial state
 * except that it retains any allocated storage
 */
void
SyncResetComposite(struct SyncNodeComposite *nc);

/**
 * allocates a new, empty, composite object
 */
struct SyncNodeComposite *
SyncAllocComposite(struct SyncBaseStruct *base);

/**
 * extends the references section of a composite object with a new offset pair
 * useful if NOT using SyncNodeAddName and SyncNodeAddNode
 */
void
SyncExtendComposite(struct SyncNodeComposite *nc,
                    SyncElemKind kind,
                    ssize_t start, ssize_t stop);

/**
 * maintains the minName and maxName bounds
 * useful if NOT using SyncNodeAddName and SyncNodeAddNode
 */
void
SyncNodeMaintainMinMax(struct SyncNodeComposite *nc,
                       const struct ccn_charbuf *name);

/**
 * extends the references section of a composite object with a new name,
 * updating the composite fields (including the name bounds)
 * the names MUST be added in sorted order!
 */
void
SyncNodeAddName(struct SyncNodeComposite *nc,
                const struct ccn_charbuf *name);

/**
 * extends the references section of a composite object with a new node,
 * updating the composite fields (including the name bounds)
 * the nodes MUST be added in sorted order!
 */
void
SyncNodeAddNode(struct SyncNodeComposite *nc,
                struct SyncNodeComposite *node);

/**
 * appends the ccnb encoding for the long hash of nc to cb
 */
int
SyncNodeAppendLongHash(struct ccn_charbuf *cb, struct SyncNodeComposite *nc);

/**
 * endComposite finishes up the encoding, appending the composite fields
 * the hash field will be valid after this call
 */
void
SyncEndComposite(struct SyncNodeComposite *nc);

/**
 * freeComposite returns the storage for the composite object
 */
void
SyncFreeComposite(struct SyncNodeComposite *nc);

/**
 * writes the encoding to a file
 * (primarily useful for test and debug code)
 */
void
SyncWriteComposite(struct SyncNodeComposite *nc, FILE *f);

/**
 * parses an encoded node and fills in the supplied node
 * implicitly resets the node at the start of the parse
 * @returns nc->err
 */
int
SyncParseComposite(struct SyncNodeComposite *nc, struct ccn_buf_decoder *d);

struct SyncNodeComposite *
SyncNodeFromBytes(struct SyncRootStruct *root, const unsigned char *cp, size_t cs);

struct SyncNodeComposite *
SyncNodeFromParsedObject(struct SyncRootStruct *root,
                         const unsigned char *msg,
                         struct ccn_parsed_ContentObject *pco);

struct SyncNodeComposite *
SyncNodeFromInfo(struct SyncRootStruct *root,
                 struct ccn_upcall_info *info);


#endif
