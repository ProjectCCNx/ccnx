/*
 * ccn/matrix.h
 * 
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 *
 * Implements a two-dimension table containing integer values.
 * Although this interface is abstract, the implementation is (or will be)
 * tuned to the needs of ccnd.
 *
 * Any value not stored will fetch as zero
 *
 * $Id$
 */

#ifndef CCN_MATRIX_DEFINED
#define CCN_MATRIX_DEFINED

#include <stdint.h>

struct ccn_matrix;

struct ccn_matrix_bounds {
    uint_least64_t row_min;
    uint_least64_t row_max;
    unsigned col_min;
    unsigned col_max;
};

struct ccn_matrix *ccn_matrix_create(void);
void ccn_matrix_destroy(struct ccn_matrix **);

intptr_t ccn_matrix_fetch(struct ccn_matrix *m,
                          uint_least64_t row, unsigned col);
void     ccn_matrix_store(struct ccn_matrix *m,
                          uint_least64_t row, unsigned col, intptr_t value);

/*
 * ccn_matrix_getbounds:
 * Fills result with a (not necessarily tight) bounding box for the
 * non-zero elements of m.  Returns -1 in case of error, or a non-negative
 * value for success.
 */
int ccn_matrix_getbounds(struct ccn_matrix *m, struct ccn_matrix_bounds *result);

/*
 * ccn_matrix_trim:
 * Zeros any entries outside the bounds
 */
int ccn_matrix_trim(struct ccn_matrix *m, const struct ccn_matrix_bounds *bounds);
/*
 * ccn_matrix_trim:
 * Zeros entries inside the bounds
 */
int ccn_matrix_clear(struct ccn_matrix *m, const struct ccn_matrix_bounds *bounds);

#endif

