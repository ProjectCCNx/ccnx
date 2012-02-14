/**
 * @file sync/SyncMacros.h
 *  
 * Part of CCNx Sync.
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


#ifndef CCN_SyncMacros
#define CCN_SyncMacros

#define SYNC_VERSION 20110614
#define SLICE_VERSION 20110614

#define DEFAULT_HASH_BYTES 32
#define MAX_HASH_BYTES (DEFAULT_HASH_BYTES + sizeof(uintmax_t))
#define MAX_NAME_BYTES (24*1024*1024 - 1)
#define MAX_NREFS 256

#define NEW_ANY(N, T) ((T *) calloc(N, sizeof(T)))
#define NEW_STRUCT(N, S) ((struct S *) calloc(N, sizeof(struct S)))

#define SET_SYNC_ERR(base, code) SetSyncErrInner(base, code, __FILE__ , __LINE__)

#endif
