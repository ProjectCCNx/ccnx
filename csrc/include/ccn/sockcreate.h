/**
 * @file ccn/sockcreate.h
 *
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009-2011 Palo Alto Research Center, Inc.
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

#ifndef CCN_SOCKCREATE_DEFINED
#define CCN_SOCKCREATE_DEFINED
#include <sys/types.h>
#include <sys/socket.h>

/**
 * Holds a pair of socket file descriptors.
 *
 * Some platforms/modes of operations require separate sockets for sending
 * and receiving, so we accommodate that with this pairing.  It is fine for
 * the two file descriptors to be the same.
 */
struct ccn_sockets {
    int recving;    /**< file descriptor to use for input (recv) */
    int sending;    /**< file descriptor to use for output (send) */
};

/**
 * Text-friendly description of a socket (IPv4 or IPv6).
 */

struct ccn_sockdescr {
    int ipproto; /**< as per http://www.iana.org/assignments/protocol-numbers -
                    should match IPPROTO_* in system headers */
    const char *address;        /**< acceptable to getaddrinfo */
    const char *port;           /**< service name or number */
    const char *source_address; /**< may be needed for multicast */
    int mcast_ttl;              /**< may be needed for multicast */
};

int ccn_setup_socket(const struct ccn_sockdescr *descr,
                     void (*logger)(void *, const char *, ...),
                     void *logdat,
                     int (*getbound)(void *, struct sockaddr *, socklen_t),
                     void *getbounddat,
                     struct ccn_sockets *socks);

#endif
