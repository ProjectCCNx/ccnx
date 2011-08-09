/**
 * @file ccn/btree.h
 * BTree
 */
/* (Will be) Part of the CCNx C Library.
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
 
#ifndef CCN_BTREE_DEFINED
#define CCN_BTREE_DEFINED

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
 * Each node has a nodeid that serves as its filename.  These start as 0 and
 * are assigned consecutively. The node may correspond to a file in a file
 * system, or to some other abstraction as appropriate.
 *
 * Open should prepare for I/O to a node.  It may use the iodata slot to
 * keep track of its state, and should set iodata to a non-NULL value.
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
 * set iodata to NULL.  It should not change the other parts of the node.
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
    void *data;
};

struct ccn_btree_node {
    unsigned nodeid;            /**< Identity of node */
    unsigned clean;             /**< Number of stable buffered bytes at front */
    struct ccn_charbuf *buf;    /**< The internal buffer */
    void *iodata;               /**< Private use by ccn_btree_io methods */
    unsigned corrupt;           /**< structure is not to be trusted */
};

struct ccn_btree {
    unsigned magic;
    unsigned nextnodeid;
    struct ccn_btree_io *io;
    struct hashtb *resident;
    int errors;
};

/**
 *  Structure of an entry inside of a node.
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
 */
struct ccn_btree_entry_trailer {
    unsigned char koff0[4];     /**< offset of piece 0 of the key */
    unsigned char ksiz0[2];     /**< size of piece 0 of the key */
    unsigned char koff1[4];     /**< offset of piece 1 */
    unsigned char ksiz1[2];     /**< size of piece 1 */
    unsigned char index[2];     /**< index of this entry within the node */
    unsigned char level[1];     /**< leaf nodes are at level 0 */
    unsigned char entsz[1];     /**< size in CCN_BT_SIZE_UNITS of entry */
};
#define CCN_BT_SIZE_UNITS 8

/**
 *  Logical structure of the payload within an entry of an internal
 *  (non-leaf) node.
 */
struct ccn_btree_internal_payload {
    unsigned char magic[1];     /**< CCN_BT_INTERNAL_MAGIC */
    unsigned char pad[3];       /**< must be zero */
    unsigned char child[4];     /**< points to a child */
};
#define CCN_BT_INTERNAL_MAGIC 0xCC

/* More extensive descriptions are provided in the code. */

/* Number of entries within the node */
int ccn_btree_node_nent(struct ccn_btree_node *node);

/* Node level (leaves are at level 0) */
int ccn_btree_node_level(struct ccn_btree_node *node);

/* Fetch the indexed key and place it into dst */
int ccn_btree_key_fetch(struct ccn_charbuf *dst,
                        struct ccn_btree_node *node,
                        int index);

/* Append the indexed key to dst */
int ccn_btree_key_append(struct ccn_charbuf *dst,
                         struct ccn_btree_node *node,
                         int index);

/* Compare given key with the key in the indexed entry of the node */
int ccn_btree_compare(const unsigned char *key, size_t size,
                      struct ccn_btree_node *node,
                      int index);


/* Search within the node for the key, or something near it */
int ccn_btree_searchnode(const unsigned char *key,
                         size_t size,
                         struct ccn_btree_node *node,
                         int i, int j);

/* Handle creation and destruction */
struct ccn_btree *ccn_btree_create(void);
int ccn_btree_destroy(struct ccn_btree **);

/* For btree node storage in files */
struct ccn_btree_io *ccn_btree_io_from_directory(const char *path);

/* Access a node */
struct ccn_btree_node *ccn_btree_getnode(struct ccn_btree *bt, unsigned nodeid);

#endif
