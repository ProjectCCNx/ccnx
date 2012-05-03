/**
 * @file ccnr_net.h
 * 
 * Part of ccnr - CCNx Repository Daemon.
 *
 */

/*
 * Copyright (C) 2011-2012 Palo Alto Research Center, Inc.
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
#ifndef CCNR_NET_DEFINED
#define CCNR_NET_DEFINED
 
#include "ccnr_private.h"

void r_net_setsockopt_v6only(struct ccnr_handle *h,int fd);
char *r_net_get_local_sockname(void);
int r_net_listen_on(struct ccnr_handle *h,const char *addrs);
int r_net_listen_on_address(struct ccnr_handle *h,const char *addr);

#endif
