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

extern const int ccn_btree_stub;

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
    ccn_btree_io_writefn bwrite;
    ccn_btree_io_closefn btclose;
    ccn_btree_io_destroyfn btdestroy;
    void *data;
};

struct ccn_btree_node {
    unsigned nodeid;            /**< Identity of node */
    unsigned clean;             /**< Number of stable buffered bytes at front */
    struct ccn_charbuf *buf;    /**< The internal buffer */
    void *iodata;               /**< Private use by ccn_btree_io methods */
};

struct ccn_btree {
    unsigned magic;
    
    struct ccn_btree_io *io;
};

struct ccn_btree_io *ccn_btree_io_from_directory(const char *path);

#endif
