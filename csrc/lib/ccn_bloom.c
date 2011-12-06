/**
 * @file ccn_bloom.c
 * @brief Support for Bloom filters.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <ccn/bloom.h>

struct ccn_bloom {
    int n;
    struct ccn_bloom_wire *wire;
};

/**
 * Create an empty Bloom filter constructor
 * @param estimated_members is an estimate of the number of elements that
 *        will be inserted into the filter
 * @param seed is used to seed the hash functions
 * @returns a new, empty Bloom filter constructor
 */
struct ccn_bloom *
ccn_bloom_create(int estimated_members, const unsigned char seed[4])
{
    struct ccn_bloom *ans = NULL;
    struct ccn_bloom_wire *f;
    int n = estimated_members;
    int i;
    ans = calloc(1, sizeof(*ans));
    if (ans == NULL) return(ans);
    f = calloc(1, sizeof(*f));
    if (f != NULL) {
        f->method = 'A';
        f->lg_bits = 13;
        /* try for about m = 12*n (m = bits in Bloom filter) */
        while (f->lg_bits > 3 && (1 << f->lg_bits) > n * 12)
            f->lg_bits--;
        /* optimum number of hash functions is ln(2)*(m/n); use ln(2) ~= 9/13 */
        f->n_hash = (9 << f->lg_bits) / (13 * n + 1);
        if (f->n_hash < 2)
            f->n_hash = 2;
        if (f->n_hash > 32)
            f->n_hash = 32;
        for (i = 0; i < sizeof(f->seed); i++)
            f->seed[i] = seed[i];
        ans->wire = f;
    }
    else
        ccn_bloom_destroy(&ans);
    return(ans);
}

const struct ccn_bloom_wire *
ccn_bloom_validate_wire(const void *buf, size_t size)
{
    const struct ccn_bloom_wire *f = (const struct ccn_bloom_wire *)buf;
    if (size < 9)
        return (NULL);
    if (f->lg_bits > 13 || f->lg_bits < 3)
        return (NULL);
    if (f->n_hash < 1 || f->n_hash > 32)
        return (NULL);
    if (size != (sizeof(*f) - sizeof(f->bloom)) + (1 << (f->lg_bits - 3)))
        return (NULL);
    if (!(f->reserved == 0 && f->method == 'A'))
        return (NULL);
    return(f);
}

struct ccn_bloom *
ccn_bloom_from_wire(const void *data, size_t size)
{
    struct ccn_bloom *ans = NULL;
    const struct ccn_bloom_wire *f = ccn_bloom_validate_wire(data, size);
    if (f != NULL) {
        ans = calloc(1, sizeof(*ans));
        if (ans == NULL) return(ans);
        ans->n = 1 << f->lg_bits; /* estimate */
        ans->wire = calloc(1, size);
        if (ans->wire == NULL)
            ccn_bloom_destroy(&ans);
        else
            memcpy(ans->wire, data, size);
    }
    return(ans);
}

void
ccn_bloom_destroy(struct ccn_bloom **bp)
{
    if (*bp != NULL) {
        if ((*bp)->wire != NULL)
            free((*bp)->wire);
        free(*bp);
        *bp = NULL;
    }
}

static int
bloom_seed(const struct ccn_bloom_wire *f)
{
    unsigned u;
    const unsigned char *s = f->seed;
    u = ((s[0]) << 24) |
        ((s[1]) << 16) |
        ((s[2]) << 8) |
        (s[3]);
    return(u & 0x7FFFFFFF);
}

static int
bloom_nexthash(int s, int u)
{
    const int k = 13; /* use this many bits of feedback shift output */
    int b = s & ((1 << k) - 1);
    /* fsr primitive polynomial (modulo 2) x**31 + x**13 + 1 */
    s = ((s >> k) ^ (b << (31 - k)) ^ (b << (13 - k))) + u;
    return(s & 0x7FFFFFFF);
}

/*
 * ccn_bloom_insert:
 * Returns the number of bits changed in the filter, so a zero return
 * means a collison has happened.
 */
int
ccn_bloom_insert(struct ccn_bloom *b, const void *key, size_t size)
{
    
    int d = 0;
    struct ccn_bloom_wire *f = b->wire;
    int h, i, k, m, n, s;
    const unsigned char *hb = (const unsigned char *)key;
    n = f->n_hash;
    m = (8*sizeof(f->bloom) - 1) & ((1 << f->lg_bits) - 1);
    s = bloom_seed(f);
    for (k = 0; k < size; k++)
        s = bloom_nexthash(s, hb[k] + 1);
    for (i = 0; i < n; i++) {
        s = bloom_nexthash(s, 0);
        h = s & m;
        if (0 == (f->bloom[h >> 3] & (1 << (h & 7)))) {
            f->bloom[h >> 3] |= (1 << (h & 7));
            d++;
        }
        f->bloom[h >> 3] |= (1 << (h & 7));
    }
    b->n += 1;
    return(d);
}

int
ccn_bloom_match_wire(const struct ccn_bloom_wire *f, const void *key, size_t size)
{
    int h, i, k, m, n, s;
    const unsigned char *hb = (const unsigned char *)key;
    n = f->n_hash;
    m = (8*sizeof(f->bloom) - 1) & ((1 << f->lg_bits) - 1);
    s = bloom_seed(f);
    for (k = 0; k < size; k++)
        s = bloom_nexthash(s, hb[k] + 1);
    for (i = 0; i < n; i++) {
        s = bloom_nexthash(s, 0);
        if (k >= size)
            k = 0;
        h = s & m;
        if (0 == (f->bloom[h >> 3] & (1 << (h & 7))))
            return(0);
    }
    return(1);
}

int
ccn_bloom_match(struct ccn_bloom *b, const void *key, size_t size)
{
    return(ccn_bloom_match_wire(b->wire, key, size));
}

int
ccn_bloom_n(struct ccn_bloom *b)
{
    return(b->n);
}

int
ccn_bloom_wiresize(struct ccn_bloom *b)
{
    // XXX - in principle, this could fold the filter if it is excessively large
    const struct ccn_bloom_wire *f = (b->wire);
    if (f == NULL)
        return(-1);
    return((sizeof(*f) - sizeof(f->bloom)) + (1 << (f->lg_bits - 3)));
}

int
ccn_bloom_store_wire(struct ccn_bloom *b, unsigned char *dest, size_t destsize)
{
    // XXX - in principle, this could fold the filter if it is excessively large
    int wiresize = ccn_bloom_wiresize(b);
    if (wiresize < 0 || destsize != wiresize)
        return(-1);
    memcpy(dest, b->wire, destsize);
    return(0);
}

