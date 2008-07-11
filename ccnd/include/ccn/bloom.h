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

struct ccn_bloom *ccn_bloom_create(int estimated_members, const unsigned char seed[4]);
struct ccn_bloom *ccn_bloom_from_wire(const void *data, size_t size);

void ccn_bloom_destroy(struct ccn_bloom **);

int ccn_bloom_insert(struct ccn_bloom *b, const void *digest, size_t size);

int ccn_bloom_match(struct ccn_bloom *b, const void *digest, size_t size);

int ccn_bloom_n(struct ccn_bloom *b);

int ccn_bloom_wiresize(struct ccn_bloom *b);

int ccn_bloom_store_wire(struct ccn_bloom *b, unsigned char *dest, size_t destsize);

/*
 * This structure reflects the on-wire representation of the Bloom filter.
 * XXX - needs updating for separation of BloomSeed
 */
struct ccn_bloom_wire {
    unsigned char lg_bits;  /* 13 maximum (8 kilobits), 3 minimum (one byte) */
    unsigned char n_hash;   /* number of hash functions to employ */
    unsigned char method;   /* allow for various hashing algorithms */
    unsigned char reserved;
    unsigned char seed[4];  /* can seed hashes differently */
    unsigned char bloom[1024]; /* 8 kilobits maximum */
};

const struct ccn_bloom_wire *ccn_bloom_validate_wire(const void *buf, size_t size);
int ccn_bloom_match_wire(const struct ccn_bloom_wire *f, const void *digest, size_t size);

#endif
