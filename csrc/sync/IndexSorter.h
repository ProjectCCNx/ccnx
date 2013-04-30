/**
 * @file IndexSorter.h
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

#ifndef CCN_IndexSorter
#define CCN_IndexSorter

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

/*
 IndexSorter is a simple priority queue that uses indexes and a sorting function
 to efficiently sort a sequence of objects.  We note that the types of the keys
 and values are only known to the sorting function, and storage for the keys and
 values is provided externally to the IndexSorter.
 
 As a special case, if base->sorter == NULL, this behaves as a stack.
 */

typedef uintmax_t IndexSorter_Index;
// The indexes need not be consecutive or even comparable,
// but one index value should be reserved to denote the empty condition.

typedef struct IndexSorter_Struct *IndexSorter_Base;

typedef int
IndexSorter_sorter(IndexSorter_Base base,
                   IndexSorter_Index x, IndexSorter_Index y);
// returns
//    < 0 if key(x) sorts before key(y)
//    = 0 if the keys are the same,
//    > 0 if key(x) sorts after key(y)

struct IndexSorter_Struct {
    IndexSorter_Index len;            // # of indexes currently valid
    IndexSorter_Index lim;            // the current storage limit for indexes
    IndexSorter_Index empty;        // the empty index
    IndexSorter_sorter *sorter;        // the sorting function
    void *client;                    // client data for the sorting function
    IndexSorter_Index *indexes;        // the storage for the indexes
};

IndexSorter_Base
IndexSorter_New(IndexSorter_Index lim, IndexSorter_Index empty);
// create a new IndexSorter
// intention is to have the caller provide client and sorter fields
// after the base has been returned

void
IndexSorter_Add(IndexSorter_Base base, IndexSorter_Index x);
// add a new index

IndexSorter_Index
IndexSorter_Rem(IndexSorter_Base base);
// remove the "best" index (least key)
// returns base->empty if the IndexSorter is empty

IndexSorter_Index
IndexSorter_Best(IndexSorter_Base base);
// returns the "best" index (least key) with no modification
// returns base->empty if the IndexSorter is empty

void
IndexSorter_Reset(IndexSorter_Base base);
// resets the sorter to have no indexes (base->len = 0)

void
IndexSorter_Free(IndexSorter_Base *basePtr);
// frees the storage used for the IndexSorter


#endif
