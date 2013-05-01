/**
 * @file ccn/hashtb.h
 * 
 * Hash table.
 *
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2008, 2009, 2013 Palo Alto Research Center, Inc.
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

#ifndef CCN_HASHTB_DEFINED
#define CCN_HASHTB_DEFINED

#include <stddef.h>

struct hashtb; /* details are private to the implementation */
struct hashtb_enumerator; /* more about this below */
typedef void (*hashtb_finalize_proc)(struct hashtb_enumerator *);
struct hashtb_param {
    hashtb_finalize_proc finalize; /* default is NULL */
    void *finalize_data;           /* default is NULL */
    int orders;                    /* default is 0 */
}; 

/*
 * hashtb_hash: Calculate a hash for the given key.
 */
size_t
hashtb_hash(const unsigned char *key, size_t key_size);

/*
 * hashtb_create: Create a new hash table.
 * The param may be NULL to use the defaults, otherwise
 * a copy of *param is made.
 */
struct hashtb *
hashtb_create(size_t item_size, const struct hashtb_param *param);

/*
 * hashtb_get_param: Get the parameters used when creating ht.
 * Return value is the finalize_data; param may be NULL if no
 * other parameters are needed.
 */
void *
hashtb_get_param(struct hashtb *ht, struct hashtb_param *param);

/*
 * hashtb_destroy: Destroy a hash table and all of its elements.
 */
void
hashtb_destroy(struct hashtb **ht);

/*
 * hashtb_n: Get current number of elements.
 */
int
hashtb_n(struct hashtb *ht);

/*
 * hashtb_lookup: Find an item
 * Keys are arbitrary data of specified length.
 * Returns NULL if not found, or a pointer to the item's data.
 */
void *
hashtb_lookup(struct hashtb *ht, const void *key, size_t keysize);

/* The client owns the memory for an enumerator, normally in a local. */ 
struct hashtb_enumerator {
    struct hashtb *ht;
    const void *key;        /* Key concatenated with extension data */
    size_t keysize;
    size_t extsize;
    void *data;
    size_t datasize;
    void *priv[3];
};

/*
 * hashtb_start: initializes enumerator to first table entry
 * Order of enumeration is arbitrary.
 * Must do this before using the enumerator for anything else,
 * and must call hashtb_end when done.
 * Returns second argument.
 */
struct hashtb_enumerator *
hashtb_start(struct hashtb *, struct hashtb_enumerator *);
void hashtb_end(struct hashtb_enumerator *);

void hashtb_next(struct hashtb_enumerator *);

/*
 * hashtb_seek: Find or add an item
 * For a newly added item, the keysize bytes of key along
 * with the extsize following bytes get copied into the
 * hash table's data.  If the key is really a null-terminated
 * string, consider using extsize = 1 to copy the terminator
 * into the keystore.  This feature may also be used to copy
 * larger chunks of unvarying data that are meant to be kept with key.
 *
 * returns 0 if entry existed before, 1 if newly added,
 *        -1 for a fatal error (ENOMEM or key==NULL).
 */
int
hashtb_seek(struct hashtb_enumerator *hte,
            const void *key, size_t keysize, size_t extsize);
#define HT_OLD_ENTRY 0
#define HT_NEW_ENTRY 1

/*
 * hashtb_delete: Delete an item
 * The item will be unlinked from the table, and will
 * be freed when safe to do so (i.e., when there are no other
 * active enumerators).  The finalize proc (if any) will be
 * called before the item is freed, and it is responsible for
 * cleaning up any external pointers to the item.
 * When the delete returns, the enumerator will be positioned
 * at the next item.
 */
void hashtb_delete(struct hashtb_enumerator *);

/*
 * hashtb_rehash: Hint about number of buckets to use
 * Normally the implementation grows the number of buckets as needed.
 * This optional call might help if the caller knows something about
 * the expected number of elements in advance, or if the size of the
 * table has shrunken dramatically and is not expected to grow soon.
 * Does nothing if there are any active enumerators.
 */
void hashtb_rehash(struct hashtb *ht, unsigned n_buckets);

#endif
