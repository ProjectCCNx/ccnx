/**
 * @file sync.h
 * 
 * Sync library interface.
 * Defines a library interface to the Sync protocol facilities implemented
 * by the Repository
 *
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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

#ifndef CCNS_DEFINED
#define CCNS_DEFINED

#include <stddef.h>
#include <ccn/charbuf.h>

#define SLICE_VERSION 20110614

struct ccns_slice;
struct ccns_handle;

/**
 * ccns_name_closure is a closure used to notify the client
 * as each new name is added to the collection by calling the callback
 * procedure.  The data field refers to client data.
 * The ccns field is filled in by ccns_open.  The count field is for client use.
 * The storage for the closure belongs to the client at all times.
 */

struct ccns_name_closure;

typedef int (*ccns_callback)(struct ccns_name_closure *nc,
                             struct ccn_charbuf *lhash,
                             struct ccn_charbuf *rhash,
                             struct ccn_charbuf *pname);

struct ccns_name_closure {
    ccns_callback callback;
    struct ccns_handle *ccns;
    void *data;
    uint64_t count;
};

/**
 * Allocate a ccns_slice structure
 * @returns a pointer to a new ccns_slice structure
 */
struct ccns_slice *ccns_slice_create(void);

/**
 * Deallocate a ccns_slice structure
 * @param sp is a pointer to a pointer to a ccns_slice structure.  The pointer will
 *  be set to NULL on return.
 */
void ccns_slice_destroy(struct ccns_slice **sp);

/*
 * Set the topo and prefix fields of a slice
 * @param slice is the slice to be modified
 * @param t is a charbuf containing the topo prefix (used to route Sync commands)
 * @param p is a charbuf containing the prefix
 * @returns 0 on success, -1 otherwise.
 */
int ccns_slice_set_topo_prefix(struct ccns_slice *slice, struct ccn_charbuf *t,
                               struct ccn_charbuf *p);

/**
 * Add a (filter) clause to a ccns_slice structure
 * @param s is the slice to be modified
 * @param f is a filter clause ccnb-encoded as a Name
 * @returns 0 on success, -1 otherwise.
 */
int ccns_slice_add_clause(struct ccns_slice *s, struct ccn_charbuf *f);

/**
 * Construct the name of a Sync configuration slice.
 * @param nm is a ccn_charbuf into which will be stored the slice name
 * @param s is the slice structure for which the name is required.
 * @returns 0 on success, -1 otherwise.
 */
int ccns_slice_name(struct ccn_charbuf *nm, struct ccns_slice *s);

/**
 * Read a slice given the name.
 * @param h is the ccn_handle on which to read.
 * @param name is the charbuf containing the name of the sync slice to be read.
 * @param slice is a pointer to a ccns_slice object which will be filled in
 *  on successful return.
 * @returns 0 on success, -1 otherwise.
 * XXX: should name be permitted to have trailing segment?
 */
int ccns_read_slice(struct ccn *h, struct ccn_charbuf *name,
                    struct ccns_slice *slice);

/**
 * Write a ccns_slice object to a repository.
 * @param h is the ccn_handle on which to write.
 * @param slice is a pointer to a ccns_slice object to be written.
 * @param name if non-NULL, is a pointer to a charbuf which will be filled
 *  in with the name of the slice that was written.
 * @returns 0 on success, -1 otherwise.
 */
int ccns_write_slice(struct ccn *h, struct ccns_slice *slice,
                     struct ccn_charbuf *name);

/**
 * Delete a ccns_slice object from a repository.
 * @param h is the ccn_handle on which to write.
 * @param name is a pointer to a charbuf naming the slice to be deleted.
 * @returns 0 on success, -1 otherwise.
 */
int ccns_delete_slice(struct ccn *h, struct ccn_charbuf *name);

/**
 * Start notification of addition of names to a sync slice.
 * @param h is the ccn_handle on which to communicate.
 * @param slice is the slice to be opened.
 * @param nc is the closure which will be called for each new name,
 *  and returns 0 to continue enumeration, -1 to stop further enumeration.
 *  NOTE: It is not safe to call ccns_close from within the callback.
 * @param rhash
 *      If NULL, indicates that the enumeration should start from the empty set.
 *      If non-NULL but empty, indicates that the enumeration should start from
 *      the current root.
 *      If non-NULL, and not empty, indicates that the enumeration should start
 *      from the specified root hash
 * @param pname if non-NULL represents the starting name for enumeration within
 *  the sync tree represented by the root hash rhash.
 * @returns a pointer to a new sync handle, which will be freed at close.
 */
struct ccns_handle *ccns_open(struct ccn *h,
                              struct ccns_slice *slice,
                              struct ccns_name_closure *nc,
                              struct ccn_charbuf *rhash,
                              struct ccn_charbuf *pname);

/**
 * Stop notification of changes of names in a sync slice and free the handle.
 * @param sh is a pointer (to a pointer) to the sync handle returned
 *  by ccns_open, which will be freed and set to NULL.
 * @param rhash if non-NULL will be filled in with the current root hash.
 * @param pname if non-NULL will be filled in with the starting name
 *  for enumeration within the sync tree represented by the root hash rhash.
 */
void ccns_close(struct ccns_handle **sh,
                struct ccn_charbuf *rhash,
                struct ccn_charbuf *pname);

#endif

