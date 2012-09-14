/**
 * @file sync/SyncBase.h
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


#ifndef CCN_SyncBase
#define CCN_SyncBase

#include <sys/types.h>
#include <ccn/loglevels.h>
#include <stdint.h>

#include "sync_plumbing.h"

// Incomplete types for opaque structures.
struct ccn_schedule;
struct ccn;
struct SyncPrivate;

// A SyncBase is the common data for a Sync Agent.  Each Sync Agent supports a
// list of collections. 

struct SyncBaseStruct {
    struct sync_plumbing *sd;   // interface between client and sync
    struct SyncErrStruct *errList;  // private data for Sync
    struct SyncPrivate *priv;       // opaque data for Repo (from Repo)
    int debug;                      // higher gives more output, 0 gives none
    unsigned lastRootId;            // last root id assigned (0 is not used)
};

// Error support

enum SyncErrCode {
    SyncErrCode_none = 0,       // no error
    SyncErrCode_bug = 1,        // internal bug
    SyncErrCode_caller = 2      // caller error (bad args, wrong state, ...)
};

struct SyncErrStruct {
    struct SyncErrStruct *next;
    enum SyncErrCode code;
    char * file;
    int line;
};


// Logging support (veneer over sync_plumbing logging)
void sync_msg(struct SyncBaseStruct *base, const char *fmt, ...);

// add a new error record
// this routine should be called from the SET_SYNC_ERR macro
void SyncSetErrInner(struct SyncBaseStruct *base,
                     enum SyncErrCode code,
                     char * file, int line);

// clear all the existing error records
void SyncClearErr(struct SyncBaseStruct *base);

// Basic object support

// allocate a new sync base
// and fill in the sync methods in sd
struct SyncBaseStruct *
SyncNewBase(struct sync_plumbing *sd);


#endif
