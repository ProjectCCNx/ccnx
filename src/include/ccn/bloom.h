/*
 * ccn/bloom.h
 * 
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 *
 * Bloom filters
 *
 * $Id$
 */

#ifndef CCN_BLOOM_DEFINED
#define CCN_BLOOM_DEFINED
#include <stddef.h>

struct ccn_bloom;

/*
 * Create an empty Bloom filter, sized appropriately for the estimated
 * number of members.
 */
struct ccn_bloom *ccn_bloom_create(int estimated_members,
                                   const unsigned char seed[4]);

/*
 * Create an updatable Bloom filter from a wire representation.
 * Result does not share storage with the input.
 */
struct ccn_bloom *ccn_bloom_from_wire(const void *data, size_t size);

/*
 * Deallocation.
 */
void ccn_bloom_destroy(struct ccn_bloom **);

/*
 * Add an element.
 * Returns the number of bits changed in the filter.
 */
int ccn_bloom_insert(struct ccn_bloom *b, const void *key, size_t size);

/*
 * Test for membership. False positives are possible.
 */
int ccn_bloom_match(struct ccn_bloom *b, const void *key, size_t size);

/*
 * Fetch the number of elements in the filter.  If b was created
 * from a wire representation, this will be approximate.
 */
int ccn_bloom_n(struct ccn_bloom *b);

/*
 * Return the number of bytes needed for the on-wire representation.
 */
int ccn_bloom_wiresize(struct ccn_bloom *b);

/*
 * Store the on-wire representation.
 */
int ccn_bloom_store_wire(struct ccn_bloom *b,
                         unsigned char *dest, size_t destsize);

/*
 * This structure reflects the on-wire representation of the Bloom filter.
 */
struct ccn_bloom_wire {
    unsigned char lg_bits;  /* 13 maximum (8 kilobits), 3 minimum (one byte) */
    unsigned char n_hash;   /* number of hash functions to employ */
    unsigned char method;   /* allow for various hashing algorithms */
    unsigned char reserved; /* must be 0 for now */
    unsigned char seed[4];  /* can seed hashes differently */
    unsigned char bloom[1024]; /* 8 kilobits maximum */
};

/*
 * ccn_bloom_validate_wire: Check for a valid on-wire representation
 * If not valid, returns NULL.
 * If valid, returns buf cast to the new pointer type.
 */
const struct ccn_bloom_wire *
    ccn_bloom_validate_wire(const void *buf, size_t size);
/*
 * ccn_bloom_match_wire: Test membership using on-wire representation
 * Caller is expected to have validated f.
 * Returns true to indicate a match.
 */
int ccn_bloom_match_wire(const struct ccn_bloom_wire *f,
                         const void *key, size_t size);

#endif

