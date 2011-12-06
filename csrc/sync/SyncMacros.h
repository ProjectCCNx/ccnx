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

#define SYNC_DEBUG_FLAG 256
#define DEFAULT_HASH_BYTES 32
#define MAX_HASH_BYTES (DEFAULT_HASH_BYTES + sizeof(uintmax_t))
#define MAX_NAME_BYTES (24*1024*1024 - 1)
#define MAX_NREFS 256

// temporary definition of Sync-related DTAG values
#define CCN_DTAG_SyncNode ((enum ccn_dtag) 115)
#define CCN_DTAG_SyncNodeKind ((enum ccn_dtag) 116)
#define CCN_DTAG_SyncNodeElement ((enum ccn_dtag) 117)
#define CCN_DTAG_SyncVersion ((enum ccn_dtag) 118)
#define CCN_DTAG_SyncNodeElements ((enum ccn_dtag) 119)
#define CCN_DTAG_SyncContentHash ((enum ccn_dtag) 120)
#define CCN_DTAG_SyncLeafCount ((enum ccn_dtag) 121)
#define CCN_DTAG_SyncTreeDepth ((enum ccn_dtag) 122)
#define CCN_DTAG_SyncByteCount ((enum ccn_dtag) 123)
#define CCN_DTAG_SyncConfigSlice ((enum ccn_dtag) 124)
#define CCN_DTAG_SyncConfigSliceList ((enum ccn_dtag) 125)
#define CCN_DTAG_SyncConfigSliceOp ((enum ccn_dtag) 126)

#define NEW_ANY(N, T) ((T *) calloc(N, sizeof(T)))
#define NEW_STRUCT(N, S) ((struct S *) calloc(N, sizeof(struct S)))

#define SET_SYNC_ERR(base, code) SetSyncErrInner(base, code, __FILE__ , __LINE__)

#endif
