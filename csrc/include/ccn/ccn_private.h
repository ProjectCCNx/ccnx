/**
 * @file ccn/ccn_private.h
 *
 * Additional operations that are irrevalent for most clients.
 *
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009 Palo Alto Research Center, Inc.
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

#ifndef CCN_PRIVATE_DEFINED
#define CCN_PRIVATE_DEFINED

#include <sys/types.h>
#include <stdint.h>

struct ccn;
struct ccn_charbuf;
struct sockaddr_un;

/*
 * Dispatch a message as if it had arrived on the socket
 */
void ccn_dispatch_message(struct ccn *h, unsigned char *msg, size_t size);

/*
 * Do any time-based operations
 * Returns number of microseconds before next call needed
 */
int ccn_process_scheduled_operations(struct ccn *h);

/*
 * Grab buffered output
 * Caller should destroy returned buffer.
 */
struct ccn_charbuf *ccn_grab_buffered_output(struct ccn *h);

void ccn_setup_sockaddr_un(const char *, struct sockaddr_un *);

#endif
