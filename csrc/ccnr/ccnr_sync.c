/**
 * @file ccnr_sync.c
 * 
 * Part of ccnr -  CCNx Repository Daemon.
 *
 */

/*
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

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/types.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/indexbuf.h>

#include "ccnr_private.h"

#include "ccnr_sync.h"

void
r_sync_notify_after(struct ccnr_handle *ccnr,
                    off_t repo_offset)
{
    ccnr->notify_after = repo_offset;
}

int
r_sync_enumerate(struct ccnr_handle *ccnr,
                 struct ccn_charbuf *interest)
{
    int ans = -1;
    return(ans);
}


int
r_sync_lookup(struct ccnr_handle *ccnr,
              struct ccn_charbuf *interest,
              struct ccn_charbuf *content_ccnb)
{
    int ans = -1;
    return(ans);
}

/**
 * Called when a content object is received by sync and needs to be
 * committed to stable storage by the repo.
 */
enum ccn_upcall_res
r_sync_upcall_store(struct ccnr_handle *ccnr,
                    enum ccn_upcall_kind kind,
                    struct ccn_upcall_info *info)
{
    enum ccn_upcall_res ans = CCN_UPCALL_RESULT_ERR;
    return(ans);
}

/**
 * Called when a content object has been constructed locally by sync
 * and needs to be committed to stable storage by the repo.
 * returns 0 for success, -1 for error.
 */

int
r_sync_local_store(struct ccnr_handle *ccnr,
				   struct ccn_charbuf *content)
{
    int ans = -1;
    return(ans);
}
