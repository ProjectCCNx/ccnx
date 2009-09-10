/**
 * @file ccn_matrix.c
 * @brief Support for a sparse matrix (2-D table) of nonnegative integers.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009 Palo Alto Research Center, Inc.
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
 
/** Initially just use a hash table */

#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <ccn/matrix.h>
#include <ccn/hashtb.h>

struct ccn_matrix {
    struct hashtb_enumerator e;
};

struct ccn_matrix_key {
    uint_least64_t row;
    unsigned       col;
};

struct ccn_matrix *
ccn_matrix_create(void)
{
    struct ccn_matrix *m;
    size_t size = sizeof(intptr_t);
    if (size < sizeof(uint_least64_t))
        size = sizeof(uint_least64_t); /* for alignment */
    m = calloc(1, sizeof(*m));
    if (m != NULL)
        hashtb_start(hashtb_create(sizeof(uint_least64_t), NULL), &m->e);
    return(m);
}

void
ccn_matrix_destroy(struct ccn_matrix **mp)
{
    struct ccn_matrix *m = *mp;
    if (m != NULL) {
        struct hashtb *ht = m->e.ht;
        hashtb_end(&m->e);
        hashtb_destroy(&ht);
        free(m);
        *mp = NULL;
    }
}


intptr_t
ccn_matrix_fetch(struct ccn_matrix *m, uint_least64_t row, unsigned col)
{
    intptr_t *valp;
    struct ccn_matrix_key key;
    memset(&key, 0, sizeof(key)); /* make sure any padding is cleared */
    key.row = row;
    key.col = col;
    valp = hashtb_lookup(m->e.ht, &key, sizeof(key));
    return(valp == NULL ? 0 : *valp);
}

void
ccn_matrix_store(struct ccn_matrix *m, uint_least64_t row, unsigned col,
                 intptr_t value)
{
    intptr_t *valp;
    struct ccn_matrix_key key;
    memset(&key, 0, sizeof(key));
    key.row = row;
    key.col = col;
    if (hashtb_seek(&(m->e), &key, sizeof(key), 0) == -1) return;
    valp = m->e.data;
    *valp = value;
}

/*
 * ccn_matrix_getbounds:
 * Fills result with a (not necessarily tight) bounding box for the
 * non-zero elements of m.  Returns -1 in case of error, or a non-negative
 * value for success.
 */
int
ccn_matrix_getbounds(struct ccn_matrix *m, struct ccn_matrix_bounds *result)
{
    struct hashtb_enumerator *e = &(m->e);
    struct hashtb *ht = e->ht;
    intptr_t *valp;
    const struct ccn_matrix_key *key;
    int first = 1;
    hashtb_end(e);
    memset(result, 0, sizeof(*result));
    hashtb_start(ht, e);
    while (e->data != NULL) {
        valp = e->data;
        if (*valp == 0)
            hashtb_delete(e);
        else {
            key = e->key;
            if (first || key->row >= result->row_max)
                result->row_max = key->row + 1;
            if (first || key->row < result->row_min)
                result->row_min = key->row;
            if (first || key->col >= result->col_max)
                result->col_max = key->col + 1;
            if (first || key->col < result->col_min)
                result->col_min = key->col;
            first = 0;
            hashtb_next(e);
        }
    }
    return(hashtb_n(ht));
}

/*
 * ccn_matrix_trim:
 * Zeros any entries outside the bounds
 */
int
ccn_matrix_trim(struct ccn_matrix *m, const struct ccn_matrix_bounds *bounds)
{
    return(-1);
}

/*
 * ccn_matrix_trim:
 * Zeros entries inside the bounds
 */
int
ccn_matrix_clear(struct ccn_matrix *m, const struct ccn_matrix_bounds *bounds)
{
    return(-1);
}
