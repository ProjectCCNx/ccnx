/**
 * @file sockaddrutil.h
 * @brief sockaddr utilities
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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
 
#ifndef CCN_SOCKADDRUTIL_DEFINED
#define CCN_SOCKADDRUTIL_DEFINED

#include <sys/socket.h>
#include <ccn/charbuf.h>

/* Append numeric printable representation, return port. */
int ccn_charbuf_append_sockaddr(struct ccn_charbuf *, const struct sockaddr *);

#endif
