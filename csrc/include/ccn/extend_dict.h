/**
 * @file ccn/extend_dict.h
 *
 * Dictionary extension routines
 *
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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

#ifndef CCN_EXTEND_DICT_DEFINED
#define CCN_EXTEND_DICT_DEFINED

#include <ccn/coding.h>

/*
 * Deallocate a dictionary freeing each of the strings and the structure itself
 */

void ccn_destroy_dict(struct ccn_dict **dp);

/*
 * Create a dictionary that is a copy of the one passed in, extended with the
 * index and name pairs loaded from the file passed in.
 */
int ccn_extend_dict(const char *dict_file, struct ccn_dict *d,
                    struct ccn_dict **rdp);

#endif
