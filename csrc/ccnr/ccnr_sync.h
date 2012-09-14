/**
 * @file ccnr_sync.h
 * 
 * Part of ccnr - CCNx Repository Daemon.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

#ifndef CCNR_SYNC_DEFINED
#define CCNR_SYNC_DEFINED

#include "ccnr_private.h"

/** Report message from sync code back through ccnr message infrastructure
 *
 */
void r_sync_msg(struct sync_plumbing *sdd, const char *fmt, ...);

/**
 * A call to r_sync_fence sets a "fence" marker that is remembered for any
 * clean shut down of a repo/sync pair.
 */
int r_sync_fence(struct sync_plumbing *sdd, uint64_t seq_num);

/** Notify repo of starting point for new names to be passed to sync.
 * Use item = 0 as the initial value.
 * Following a call to r_sync_notify_after, the repository will call
 *    SyncNotifyContent(struct SyncBaseStruct *,
 *                      int enumeration,
 *                      ccnr_accession item,
 *                      struct ccn_charbuf *name);
 * periodically while there are no un-notified objects.
 *     enumeration is 0 for "time-based" notifications, or the value passed
 *          in when the enumeration was started.   This may not end up an int.
 *     if the call is for an explicit enumeration, and there are no more
 *     objects, name and content_comps will be NULL.
 * If SyncNotifyContent returns -1 then the active enumeration, or the
 * r_sync_notify_after() will be cancelled.
 */
void
r_sync_notify_after(struct ccnr_handle *ccnr, ccnr_hwm item);

/** Request that a SyncNotifyContent call is made for each content object
 *  matching the interest.
 *  returns -1 for error, or an enumeration number which will also be passed
 *      in the SyncNotifyContent
 */
int
r_sync_enumerate(struct sync_plumbing *sdd, struct ccn_charbuf *interest);

/** Look up a content object that is stored locally in the repository
 * based on the supplied interest.
 * appends the content object to the content_ccnb.
 * returns 0 for success, -1 for error.
 */
int
r_sync_lookup(struct sync_plumbing *sdd, struct ccn_charbuf *interest,
              struct ccn_charbuf *content_ccnb);

/** Look up a content object that is stored locally in the repository
 * based on the supplied interest.  Takes a ccnr handle instead of sync data.
 * appends the content object to the content_ccnb.
 * returns 0 for success, -1 for error.
 */
int
r_lookup(struct ccnr_handle *ccnr, struct ccn_charbuf *interest,
              struct ccn_charbuf *content_ccnb);

/**
 * Called when a content object is received by sync and needs to be
 * committed to stable storage by the repo.
 */
enum ccn_upcall_res
r_sync_upcall_store(struct sync_plumbing *sdd, enum ccn_upcall_kind kind,
                    struct ccn_upcall_info *info);

/**
 * Called when a content object has been constructed locally by sync
 * and needs to be committed to stable storage by the repo.
 * returns 0 for success, -1 for error.
 */

int
r_sync_local_store(struct sync_plumbing *sdd, struct ccn_charbuf *content_cb);

/**
 * A wrapper for the sync_notify method that takes a content entry.
 */
int
r_sync_notify_content(struct ccnr_handle *ccnr, int e, struct content_entry *content);

#endif
