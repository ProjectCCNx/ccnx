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
#include <stdint.h>
#include <ccnr/ccnr_private.h>

// Incomplete types for opaque structures.
struct ccn_schedule;
struct ccn;
struct SyncPrivate;

// A SyncBase is the common data for a Sync Agent.  Each Sync Agent supports a
// list of collections. 

struct SyncBaseStruct {
    struct SyncErrStruct *errList;  // private data for Sync
    struct SyncPrivate *priv;       // opaque data for Repo (from Repo)
    void *client_handle;            // the ccnr/ccns handle to use (from Repo or Sync client)
    struct ccn *ccn;                // the ccn handle to use (from Repo)
    struct ccn_schedule *sched;     // the scheduler to use (from Repo)
    int debug;                      // higher gives more output, 0 gives none
    unsigned lastRootId;            // last root id assigned (0 is not used)
    ccnr_hwm highWater;             // high water mark for accession numbers
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

// add a new error record
// this routine should be called from the SET_SYNC_ERR macro
void SyncSetErrInner(struct SyncBaseStruct *base,
                     enum SyncErrCode code,
                     char * file, int line);

// clear all the existing error records
void SyncClearErr(struct SyncBaseStruct *base);

// Basic object support

// allocate and initialize a new sync base
struct SyncBaseStruct *
SyncNewBase(struct ccnr_handle *ccnr,
            struct ccn *ccn,
            struct ccn_schedule *sched);

// initialize a sync base
void
SyncInit(struct SyncBaseStruct *bp);

// free up the resources for the sync base
// called by Repo when shutting down
// (no callbacks possible at this point)
void
SyncFreeBase(struct SyncBaseStruct **bp);

// Enumeration support
// called by Repo
// name = NULL indicates end of enumeration
// returns -1 to terminate, 0 to continue
int
SyncNotifyContent(struct SyncBaseStruct *base,
                  int enumeration,
                  ccnr_accession item,
                  struct ccn_charbuf *name);

// shutdown a sync base
void
SyncShutdown(struct SyncBaseStruct *bp);

#endif
