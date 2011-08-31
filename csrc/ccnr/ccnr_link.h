/**
 * @file ccnr_link.h
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
 
#ifndef CCNR_LINK_DEFINED
#define CCNR_LINK_DEFINED

#include "ccnr_private.h"

void r_link_do_deferred_write(struct ccnr_handle *h,int fd);
void r_link_stuff_and_send(struct ccnr_handle *h,
                           struct fdholder *fdholder,
                           const unsigned char *data1,
                           size_t size1,
                           const unsigned char *data2,
                           size_t size2,
                           off_t *offsetp);
void r_link_send_content(struct ccnr_handle *h,
                         struct fdholder *fdholder,
                         struct content_entry *content);
#endif
