/*
 * sockcreate.h
 * Copyright (C) 2009 Palo Alto Research Center, Inc. All rights reserved.
 */

#ifndef CCN_SOCKCREATE_DEFINED
#define CCN_SOCKCREATE_DEFINED

/**
 * Holds a pair of socket file descriptors.
 *
 * Some platfoms/modes of operations require separate sockets for sending
 * and receiving, so we accomodate that with this pairing.  It is fine for
 * the two file descriptors to be the same.
 */
struct ccn_sockets {
    int recving;    /**< file descriptor to use for input (recv) */
    int sending;    /**< file descriptor to use for output (send) */
    // flags?
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

/**
 * Utility for setting up a socket (or pair of sockets) from a text-based
 * description.
 * 
 * @param descr hold the information needed to create the socket(s).
 * @param logger should be used for reporting errors, printf-style.
 * @param logdat must be passed as first argument to logger().
 * @param socks should be filled in with the pair of socket file descriptors.
 * @returns 0 for success, -1 for error.
 */
int ccn_setup_socket(const struct ccn_sockdescr *descr,
                     void (*logger)(void *, const char *, ...),
                     void *logdat,
                     struct ccn_sockets *socks);

#endif
