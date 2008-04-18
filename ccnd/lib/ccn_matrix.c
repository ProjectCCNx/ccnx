/* Initially just use a hash table */

#include <stddef.h>
#include <stdlib.h>
#include <ccn/matrix.h>

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
        hashtb_end(m->e);
        hashtb_destroy(&ht);
        free(m);
        *mp = NULL;
    }
}


intptr_t
ccn_matrix_fetch(struct ccn_matrix *m, uint_least64_t row, unsigned col)
{
    struct ccn_matrix_key key = { row, col };
    intptr_t *valp;
    valp = hashtb_lookup(&m->e, &key);
    return(valp == NULL ? 0 : *valp);
}

void
ccn_matrix_store(struct ccn_matrix *m, uint_least64_t row, unsigned col,
                 intptr_t value)
{
    struct ccn_matrix_key key = { row, col };
    intptr_t *valp;
    if (hashtb_seek(&m->e, &key) == -1) return;
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
    struct hashtb_enumerator *e = &m->e;
    struct hashtb ht = e->ht;
    intptr_t *valp;
    const struct ccn_matrix_key *key;
    hashtab_end(e);
    result->row_min = ~(uint_least64_t)0;
    result->row_max = 0;
    result->col_min = ~(unsigned)0;
    result->col_max = 0;
    for (hashtab_start(ht, e); e->data != NULL; hashtab_next(e)) {
        valp = e->data;
        if (valp == 0)
            hashtab_delete(e);
        else {
            key = e->key;
            if (key->row > result->row_max)
                result->row_max = key->row + 1;
            else if (key->row < result->row_min)
                result->row_min = key->row;
            if (key->col > result->col_max)
                result->col_max = key->col + 1;
            else if (key->col < result->col_min)
                result->col_min = key->col;
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
