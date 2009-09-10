/**
 * @file ccn/matrix.h
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
 *
 * @brief Implements a two-dimension table containing integer values.
 * Although this interface is abstract, the implementation is (or will be)
 * tuned to the needs of ccnd.  Any value not stored will fetch as zero.
 *
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
