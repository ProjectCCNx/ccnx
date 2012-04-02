/**
 * @file ccnr_msg.h
 * 
 * Part of ccnr - CCNx Repository Daemon.
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
 
#ifndef CCNR_MSG_DEFINED
#define CCNR_MSG_DEFINED

#include "ccnr_private.h"

/**
 * Levels for deciding whether or not to log.
 */
#define CCNL_NONE       0   /**< No logging at all */
#define CCNL_SEVERE     3   /**< Severe errors */
#define CCNL_ERROR      5   /**< Configuration errors */
#define CCNL_WARNING    7   /**< Something might be wrong */
#define CCNL_INFO       9   /**< Low-volume informational */
#define CCNL_FINE      11   /**< Debugging */
#define CCNL_FINER     13   /**< More debugging */
#define CCNL_FINEST    15   /**< MORE DEBUGGING YET */

int ccnr_msg_level_from_string(const char *s);

void ccnr_debug_ccnb(struct ccnr_handle *h,
                     int lineno,
                     const char *msg,
                     struct fdholder *fdholder,
                     const unsigned char *ccnb,
                     size_t ccnb_size);
void ccnr_msg(struct ccnr_handle *h, const char *fmt, ...);

#endif
