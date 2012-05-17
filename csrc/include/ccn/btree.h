/**
 * @file ccn/btree.h
 * BTree
 */
/* Part of the CCNx C Library.
 *
 * Copyright (C) 2011-12 Palo Alto Research Center, Inc.
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
 
#ifndef CCN_BTREE_DEFINED
#define CCN_BTREE_DEFINED

#include <stdio.h>
#include <sys/types.h>
#include <ccn/charbuf.h>
#include <ccn/hashtb.h>

struct ccn_btree_io;
struct ccn_btree_node;

/**
 * Methods for external I/O of btree nodes.
 *
 * These are supplied by the client, and provide an abstraction
 * to hold the persistent representation of the btree.
 *
 * Each node has a nodeid that serves as its filename.  These start as 1 and
 * are assigned consecutively. The node may correspond to a file in a file
 * system, or to some other abstraction as appropriate.
 *
 * Open should prepare for I/O to a node.  It may use the iodata slot to
 * keep track of its state, and should set iodata to a non-NULL value.
 * It should update the count of openfds as appropriate.
 *
 * Read gets bytes from the file and places it into the buffer at the
 * corresponding position.  The parameter is a limit for the max buffer size.
 * Bytes prior to the clean mark do not need to be read.
 * The buffer should be extended, if necessary, to hold the data.
 * Read is not responsible for updating the clean mark.
 * 
 * Write puts bytes from the buffer into the file, and truncates the file
 * according to the buffer length.  Bytes prior to the clean mork do not
 * need to be written, since they should be the same in the buffer and the
 * file.  Write is not responsible for updating the clean mark.
 *
 * Close is called at the obvious time.  It should free any node io state and
 * set iodata to NULL, updating openfds as appropriate.  It should not change
 * the other parts of the node.
 *
 * Negative return values indicate errors.
 */
typedef int (*ccn_btree_io_openfn)
    (struct ccn_btree_io *, struct ccn_btree_node *);
typedef int (*ccn_btree_io_readfn)
    (struct ccn_btree_io *, struct ccn_btree_node *, unsigned);
typedef int (*ccn_btree_io_writefn)
    (struct ccn_btree_io *, struct ccn_btree_node *);
typedef int (*ccn_btree_io_closefn)
    (struct ccn_btree_io *, struct ccn_btree_node *);
typedef int (*ccn_btree_io_destroyfn)
    (struct ccn_btree_io **);

/* This serves as the external name of a btree node. */
typedef unsigned ccn_btnodeid;

/**
 * Holds the methods and the associated common data.
 */
struct ccn_btree_io {
    char clue[16]; /* unused except for debugging/logging */
    ccn_btree_io_openfn btopen;
    ccn_btree_io_readfn btread;
    ccn_btree_io_writefn btwrite;
    ccn_btree_io_closefn btclose;
    ccn_btree_io_destroyfn btdestroy;
    ccn_btnodeid maxnodeid;    /**< Largest assigned nodeid */
    int openfds;               /**< Number of open files */
    void *data;
};
/**
 * State associated with a btree node
 *
 * These usually live in the resident hashtb of a ccn_btree, but might be
 * elsewhere (such as stack-allocated) in some cases.
 */
struct ccn_btree_node {
    ccn_btnodeid nodeid;        /**< Identity of node */
    struct ccn_charbuf *buf;    /**< The internal buffer */
    void *iodata;               /**< Private use by ccn_btree_io methods */
    ccn_btnodeid parent;        /**< Parent node id; 0 if unknown */
    unsigned clean;             /**< Number of stable buffered bytes at front */
    unsigned freelow;           /**< Index of first unused byte of free space */
    unsigned corrupt;           /**< Structure is not to be trusted */
    unsigned activity;          /**< Meters use of the node */
};

/** Increment to node->activity when node is referenced but not changed */
#define CCN_BT_ACTIVITY_REFERENCE_BUMP 1
/** Increment to node->activity when node is read from disk */
#define CCN_BT_ACTIVITY_READ_BUMP 8
/** Increment to node->activity when node is modified */
#define CCN_BT_ACTIVITY_UPDATE_BUMP 16

/** Limit to the number of btree nodes kept open when idle */
#define CCN_BT_OPEN_NODES_IDLE 5
/** Limit to the number of file descriptors the btree should use at a time */
#define CCN_BT_OPEN_NODES_LIMIT 13


/**
 * State associated with a btree as a whole
 */
struct ccn_btree {
    unsigned magic;             /**< for making sure we point to a btree */
    ccn_btnodeid nextnodeid;    /**< for allocating new btree nodes */
    struct ccn_btree_io *io;    /**< storage layer */
    struct hashtb *resident;    /**< of ccn_btree_node, by nodeid */
    ccn_btnodeid nextspill;     /**< undersize node that needs spilling */
    ccn_btnodeid nextsplit;     /**< oversize node that needs splitting */
    ccn_btnodeid missedsplit;   /**< should stay zero */
    int errors;                 /**< counter for detected errors */
    int cleanreq;               /**< if nonzero, cleaning might be needed */
    /* tunables */
    int full;                   /**< split internal nodes bigger than this */
    int full0;                  /**< split leaf nodes bigger than this */
    int nodebytes;              /**< limit size of node */
    int nodepool;               /**< limit resident size */
};

/**
 *  Structure of a node.
 *  
 *  These are as they appear on external storage, so we stick to 
 *  single-byte types to keep it portable between machines.
 *  Multi-byte numeric fields are always in big-endian format.
 *
 *  Within a node, the entries are fixed size.
 *  The entries are packed together at the end of the node's storage,
 *  so that by examining the last entry the location of the other entries
 *  can be determined directly.  The entsz field includes the whole entry,
 *  which consists of a payload followed by a trailer.
 *
 *  The keys are stored in the first portion of the node.  They may be
 *  in multiple pieces, and the pieces may overlap arbitrarily.  This offers
 *  a very simple form of compression, since the keys within a node are
 *  very likely to have a lot in common with each other.
 *
 *  A few bytes at the very beginning serve as a header.
 *
 * This is the overall structure of a node:
 *
 *  +---+-----------------------+--------------+----+----+-- --+----+
 *  |hdr|..string......space....| (free space) | E0 | E1 | ... | En |
 *  +---+-----------------------+--------------+----+----+-- --+----+
 *
 * It is designed so that new entries can be added without having to
 * rewrite all of the string space.  Thus the header should not contain
 * things that we expect to change often.
 */
struct ccn_btree_node_header {
    unsigned char magic[4];     /**< File magic */
    unsigned char version[1];   /**< Format version */
    unsigned char nodetype[1];  /**< Indicates root node, backup root, etc. */
    unsigned char level[1];     /**< Level within the tree */
    unsigned char extsz[1];     /**< Header extension size (CCN_BT_SIZE_UNITS)*/
};

/**
 *  Structure of a node entry trailer.
 *
 * This is how the last few bytes of each entry within a node are arranged.
 *
 */
struct ccn_btree_entry_trailer {
    unsigned char koff0[4];     /**< offset of piece 0 of the key */
    unsigned char ksiz0[2];     /**< size of piece 0 of the key */
    unsigned char koff1[4];     /**< offset of piece 1 */
    unsigned char ksiz1[2];     /**< size of piece 1 */
    unsigned char entdx[2];     /**< index of this entry within the node */
    unsigned char level[1];     /**< leaf nodes are at level 0 */
    unsigned char entsz[1];     /**< entry size in CCN_BT_SIZE_UNITS */
};
#define CCN_BT_SIZE_UNITS 8
/** Maximum key size, dictated by size of above size fields */
#define CCN_BT_MAX_KEY_SIZE 65535

/**
 *  Structure of the entry payload within an internal (non-leaf) node.
 */
struct ccn_btree_internal_payload {
    unsigned char magic[1];     /**< CCN_BT_INTERNAL_MAGIC */
    unsigned char pad[3];       /**< must be zero */
    unsigned char child[4];     /**< nodeid of a child */
};
#define CCN_BT_INTERNAL_MAGIC 0xCC
/**
 *  Logical structure of the entry within an internal (non-leaf) node.
 */
struct ccn_btree_internal_entry {
    struct ccn_btree_internal_payload ie;
    struct ccn_btree_entry_trailer trailer;
};

/* More extensive function descriptions are provided in the code. */

/* Number of entries within the node */
int ccn_btree_node_nent(struct ccn_btree_node *node);

/* Node level (leaves are at level 0) */
int ccn_btree_node_level(struct ccn_btree_node *node);

/* Node entry size */
int ccn_btree_node_getentrysize(struct ccn_btree_node *node);

/* Node payload size */
int ccn_btree_node_payloadsize(struct ccn_btree_node *node);

/* Get address of the indexed entry within node */
void *ccn_btree_node_getentry(size_t payload_bytes,
                              struct ccn_btree_node *node, int i);

/* Fetch the indexed key and place it into dst */
int ccn_btree_key_fetch(struct ccn_charbuf *dst,
                        struct ccn_btree_node *node, int i);

/* Append the indexed key to dst */
int ccn_btree_key_append(struct ccn_charbuf *dst,
                         struct ccn_btree_node *node, int i);

/* Compare given key with the key in the indexed entry of the node */
int ccn_btree_compare(const unsigned char *key, size_t size,
                      struct ccn_btree_node *node, int i);

#define CCN_BT_ENCRES(ndx, success) (2 * (ndx) + ((success) || 0))
#define CCN_BT_SRCH_FOUND(res) ((res) & 1)
#define CCN_BT_SRCH_INDEX(res) ((res) >> 1)
/* Search within the node for the key, or something near it */
int ccn_btree_searchnode(const unsigned char *key, size_t size,
                         struct ccn_btree_node *node);

/* Insert a new entry at slot i of node */
int ccn_btree_insert_entry(struct ccn_btree_node *node, int i,
                           const unsigned char *key, size_t keysize,
                           void *payload, size_t payload_bytes);

/* Delete the entry at slot i of node */
int ccn_btree_delete_entry(struct ccn_btree_node *node, int i);

/* Initialize a btree node */
int ccn_btree_init_node(struct ccn_btree_node *node,
                        int level, unsigned char nodetype, unsigned char extsz);

/* Test for an oversize node */
int ccn_btree_oversize(struct ccn_btree *btree, struct ccn_btree_node *node);

/* Test for unbalance */
int ccn_btree_unbalance(struct ccn_btree *btree, struct ccn_btree_node *node);

/* Check a node for internal consistency */
int ccn_btree_chknode(struct ccn_btree_node *node);

/*
 * Overall btree operations
 */

/* Handle creation and destruction */
struct ccn_btree *ccn_btree_create(void);
int ccn_btree_destroy(struct ccn_btree **);

/* Record an error */
void ccn_btree_note_error(struct ccn_btree *bt, int info);

/* Access a node, creating or reading it if necessary */
struct ccn_btree_node *ccn_btree_getnode(struct ccn_btree *bt,
                                         ccn_btnodeid nodeid,
                                         ccn_btnodeid parentid);

/* Get a node handle if it is already resident */
struct ccn_btree_node *ccn_btree_rnode(struct ccn_btree *bt,
                                       ccn_btnodeid nodeid);

/* Clean a node and release io resources, retaining cached node in memory */
int ccn_btree_close_node(struct ccn_btree *btree, struct ccn_btree_node *node);

/* Do a lookup, starting from the default root */
int ccn_btree_lookup(struct ccn_btree *btree,
                     const unsigned char *key, size_t size,
                     struct ccn_btree_node **leafp);

/* Do a lookup, starting from the provided root and stopping at stoplevel */
int ccn_btree_lookup_internal(struct ccn_btree *btree,
                     struct ccn_btree_node *root, int stoplevel,
                     const unsigned char *key, size_t size,
                     struct ccn_btree_node **ansp);

/* Search for nodeid in parent */ 
int ccn_btree_index_in_parent(struct ccn_btree_node *parent,
                              ccn_btnodeid nodeid);

/* Find the leaf that comes after the given node */
int ccn_btree_next_leaf(struct ccn_btree *btree,
                        struct ccn_btree_node *node,
                        struct ccn_btree_node **ansp);

/* Find the leaf that comes before the given node */
int ccn_btree_prev_leaf(struct ccn_btree *btree,
                        struct ccn_btree_node *node,
                        struct ccn_btree_node **ansp);

/* Split a node into two */
int ccn_btree_split(struct ccn_btree *btree, struct ccn_btree_node *node);

/* Spill a node over into sibling */
int ccn_btree_spill(struct ccn_btree *btree, struct ccn_btree_node *node);

/* Prepare to update a node */
int ccn_btree_prepare_for_update(struct ccn_btree *bt,
                                 struct ccn_btree_node *node);

/* Check the whole btree carefully */
int ccn_btree_check(struct ccn_btree *btree, FILE *outfp);

/*
 * Storage layer - client can provide other options
 */

/* For btree node storage in files */
struct ccn_btree_io *ccn_btree_io_from_directory(const char *path,
                                                 struct ccn_charbuf *msgs);

/* Low-level field access */
unsigned ccn_btree_fetchval(const unsigned char *p, int size);
void ccn_btree_storeval(unsigned char *p, int size, unsigned v);

#endif
