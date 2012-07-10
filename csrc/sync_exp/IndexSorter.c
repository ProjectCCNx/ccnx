/**
 *  IndexSorter.c
 *  
 * @file IndexSorter.c
 * 
 * Part of the CCNx C Library.
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

#include <stdlib.h>
#include <string.h>
#include "IndexSorter.h"

extern IndexSorter_Base
IndexSorter_New(IndexSorter_Index lim, IndexSorter_Index empty) {
    IndexSorter_Base base = calloc(1, sizeof(struct IndexSorter_Struct));
    if (lim < 4) lim = 4;
    base->indexes = calloc(lim, sizeof(IndexSorter_Index));
    base->lim = lim;
    base->empty = empty;
    return base;
}

extern void
IndexSorter_Add(IndexSorter_Base base, IndexSorter_Index x) {
    if (base->sorter == NULL) return;
    IndexSorter_Index len = base->len;
    IndexSorter_Index *vec = base->indexes;
    if (len >= base->lim) {
        // need to expand 
        IndexSorter_Index nLim = len + len/2 + 4;
        IndexSorter_Index *old = vec;
        vec = calloc(nLim, sizeof(IndexSorter_Index));
        base->indexes = vec;
        if (len > 0) memcpy(vec, old, len*sizeof(IndexSorter_Index));
        free(old);
        base->lim = nLim;
    }
    IndexSorter_Index son = len;
    if (base->sorter != NULL) {
        while (son > 0) {
            IndexSorter_Index dad = (son-1) / 2;
            IndexSorter_Index dx = vec[dad];
            if (base->sorter(base, dx, x) <= 0) break;
            vec[son] = dx;
            son = dad;
        }
    }
    vec[son] = x;
    base->len = len+1;
}

extern IndexSorter_Index
IndexSorter_Rem(IndexSorter_Base base) {
    IndexSorter_Index len = base->len;
    IndexSorter_Index ret = base->empty;
    if (len > 0) {
        IndexSorter_Index *vec = base->indexes;
        if (base->sorter != NULL) {
            // sorter present
            len = len - 1;
            ret = vec[0];
            IndexSorter_Index dad = 0;
            IndexSorter_Index dx = vec[len];
            for (;;) {
                IndexSorter_Index son = dad+dad+1;
                if (son >= len) break;
                IndexSorter_Index sx = vec[son];
                IndexSorter_Index nson = son+1;
                if (nson < len) {
                    IndexSorter_Index sy = vec[nson];
                    if (base->sorter(base, sx, sy) > 0) {
                        sx = sy;
                        son = nson;
                    }
                }
                if (base->sorter(base, dx, sx) <= 0) break;
                vec[dad] = sx;
                dad = son;
            }
            vec[dad] = dx;
        } else {
            // no sorter, so just pop it
            len = len - 1;
            ret = vec[len];
        }
        base->len = len;
    }
    return ret;
}

extern IndexSorter_Index
IndexSorter_Best(IndexSorter_Base base) {
    if (base->len > 0) return base->indexes[0];
    return base->empty;
}

extern void
IndexSorter_Reset(IndexSorter_Base base) {
    base->len = 0;
}

extern void
IndexSorter_Free(IndexSorter_Base *basePtr) {
    if (basePtr != NULL) {
        IndexSorter_Base base = *basePtr;
        if (base != NULL) {
            void *indexes = base->indexes;
            if (indexes != NULL) free(indexes);
            free(base);
            *basePtr = NULL;
        }
    }
}

