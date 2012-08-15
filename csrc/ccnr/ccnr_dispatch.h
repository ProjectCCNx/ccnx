/**
 * @file ccnr_dispatch.h
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
 
#ifndef CCNR_DISPATCH_DEFINED
#define CCNR_DISPATCH_DEFINED

#include "ccnr_private.h"
void r_dispatch_run(struct ccnr_handle *h);
void r_dispatch_process_internal_client_buffer(struct ccnr_handle *h);
struct content_entry *process_incoming_content(struct ccnr_handle *h, struct fdholder *fdholder,
                              unsigned char *msg, size_t size, off_t *offsetp);
void r_dispatch_process_input(struct ccnr_handle *h, int fd);
#endif

