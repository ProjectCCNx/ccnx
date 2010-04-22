/**
 * @file ccn_sockcreate.c
 * @brief Setting up a socket from a text-based description.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009-2010 Palo Alto Research Center, Inc.
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
#include <stdlib.h>
#include <stdarg.h>
#include <errno.h>
#include <string.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <net/if.h>
#include <netdb.h>
#include <unistd.h>

#include <ccn/sockcreate.h>

#if defined(NEED_GETADDRINFO_COMPAT)
    #include "getaddrinfo.h"
    #include "dummyin6.h"
#endif

#define LOGGIT if (logger) (*logger)
#define GOT_HERE LOGGIT(logdat, "at ccn_sockcreate.c:%d", __LINE__);


static int
set_multicast_socket_options(int socket_r, int socket_w,
                             struct addrinfo *ai,
                             struct addrinfo *localif_for_mcast_addrinfo,
                             int multicastttl,
                             int ifindex,
                             void (*logger)(void *, const char *, ...),
                             void *logdat)
{
    struct addrinfo hints;
    struct ip_mreq mreq;
#ifdef IPV6_JOIN_GROUP
    struct ipv6_mreq mreq6;
#endif
    unsigned char csockopt;
    unsigned int isockopt;
    int res;

    memset((void *)&hints, 0, sizeof(hints));
    memset((void *)&mreq, 0, sizeof(mreq));
#ifdef IPV6_JOIN_GROUP
    memset((void *)&mreq6, 0, sizeof(mreq6));
#endif

    if (ai->ai_family == PF_INET && IN_MULTICAST(ntohl(((struct sockaddr_in *)(ai->ai_addr))->sin_addr.s_addr))) {
        LOGGIT(logdat, "IPv4 multicast");
#ifdef IP_ADD_MEMBERSHIP
        memcpy((void *)&mreq.imr_multiaddr, &((struct sockaddr_in *)ai->ai_addr)->sin_addr, sizeof(mreq.imr_multiaddr));
        if (localif_for_mcast_addrinfo != NULL) {
            memcpy((void *)&mreq.imr_interface.s_addr, &((struct sockaddr_in *)localif_for_mcast_addrinfo->ai_addr)->sin_addr, sizeof(mreq.imr_interface.s_addr));
        }
        res = setsockopt(socket_r, IPPROTO_IP, IP_ADD_MEMBERSHIP, &mreq, sizeof(mreq));
        if (res == -1) {
            LOGGIT(logdat, "setsockopt(..., IP_ADD_MEMBERSHIP, ...): %s", strerror(errno));
            return(-1);
        }
#endif
#ifdef IP_MULTICAST_LOOP
        csockopt = 0;
        res = setsockopt(socket_w, IPPROTO_IP, IP_MULTICAST_LOOP, &csockopt, sizeof(csockopt));
        if (res == -1) {
            LOGGIT(logdat, "setsockopt(..., IP_MULTICAST_LOOP, ...): %s", strerror(errno));
            return(-1);
        }
#endif
#ifdef IP_MULTICAST_TTL
        if (multicastttl > 0) {
            csockopt = multicastttl;
            res = setsockopt(socket_w, IPPROTO_IP, IP_MULTICAST_TTL, &csockopt, sizeof(csockopt));
            if (res == -1) {
                LOGGIT(logdat, "setsockopt(..., IP_MULTICAST_TTL, ...): %s", strerror(errno));
                return(-1);
            }
        }
#endif
#ifdef IP_MULTICAST_IF
        if (localif_for_mcast_addrinfo != NULL) {
            struct in_addr ifaddr = { 0 };
            ifaddr = ((struct sockaddr_in *)localif_for_mcast_addrinfo->ai_addr)->sin_addr;
            res = setsockopt(socket_w, IPPROTO_IP, IP_MULTICAST_IF, &ifaddr, sizeof(ifaddr));
            if (res == -1) {
                LOGGIT(logdat, "setsockopt(..., IP_MULTICAST_IF, ...): %s", strerror(errno));
                return(-1);
            }
        }
#endif
    } else if (ai->ai_family == PF_INET6 && IN6_IS_ADDR_MULTICAST((&((struct sockaddr_in6 *)ai->ai_addr)->sin6_addr))) {
        LOGGIT(logdat, "IPv6 multicast");
#ifdef IPV6_JOIN_GROUP
        memcpy((void *)&mreq6.ipv6mr_multiaddr, &((struct sockaddr_in6 *)ai->ai_addr)->sin6_addr, sizeof(mreq6.ipv6mr_multiaddr));
        if (ifindex > 0) {
            mreq6.ipv6mr_interface = ifindex;
        }
        res = setsockopt(socket_r, IPPROTO_IPV6, IPV6_JOIN_GROUP, &mreq6, sizeof(mreq6));
        if (res == -1) {
            LOGGIT(logdat, "setsockopt(..., IPV6_JOIN_GROUP, ...): %s", strerror(errno));
            return(-1);
        }
#endif
#ifdef IPV6_MULTICAST_LOOP
        isockopt = 0;
        res = setsockopt(socket_w, IPPROTO_IPV6, IPV6_MULTICAST_LOOP, &isockopt, sizeof(isockopt));
        if (res == -1) {
            LOGGIT(logdat, "setsockopt(..., IPV6_MULTICAST_LOOP, ...): %s", strerror(errno));
            return(-1);
        }
#endif
#ifdef IPV6_MULTICAST_HOPS
        if (multicastttl > 0) {
            isockopt = multicastttl;
            res = setsockopt(socket_w, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, &isockopt, sizeof(isockopt));
            if (res == -1) {
                LOGGIT(logdat, "setsockopt(..., IPV6_MULTICAST_LOOP, ...): %s", strerror(errno));
                return(-1);
            }
        }
#endif
#ifdef IP6_MULTICAST_IF
        if (ifindex > 0) {
            isockopt = ifindex;
            res = setsockopt(socket_w, IPPROTO_IPV6, IP6_MULTICAST_IF, &isockopt, sizeof(isockopt));
            if (res == -1) {
                LOGGIT(logdat, "setsockopt(..., IP6_MULTICAST_IF, ...): %s", strerror(errno));
                return(-1);
            }
        }
#endif
    }
    return(0);
}

/**
 * Utility for setting up a socket (or pair of sockets) from a text-based
 * description.
 *
 * Currently this is only used for UDP multicast.
 * 
 * @param descr hold the information needed to create the socket(s).
 * @param logger should be used for reporting errors, printf-style.
 * @param logdat must be passed as first argument to logger().
 * @param getbound is callback for getting already-bound sender socket,
 *          should return -1 if none.
 * @param getbounddat is passed as first argument to getbound
 * @param socks will be filled in with the pair of socket file descriptors.
 * @returns 0 for success, -1 for error.
 */
int
ccn_setup_socket(const struct ccn_sockdescr *descr,
                 void (*logger)(void *, const char *, ...),
                 void *logdat,
                 int (*getbound)(void *, struct sockaddr *, socklen_t),
                 void *getbounddat,
                 struct ccn_sockets *socks)
{
    int result = -1;
    char *cp = NULL;
    struct addrinfo hints = {0};
    int res;
    struct addrinfo *mcast_source_addrinfo = NULL;
    struct addrinfo *addrinfo = NULL;
    struct addrinfo *laddrinfo = NULL;
    unsigned int if_index = 0;
    const int one = 1;
    int sock = -1;
    int close_protect = -1;
    
    GOT_HERE;
    socks->sending = socks->recving = -1;
    if (descr->ipproto > 0)
        hints.ai_protocol = descr->ipproto;
    if (descr->ipproto == IPPROTO_UDP)
        hints.ai_socktype = SOCK_DGRAM;
    else if (descr->ipproto == IPPROTO_TCP)
        hints.ai_socktype = SOCK_STREAM;
    hints.ai_flags =  AI_NUMERICHOST;
    if (descr->port == NULL ||
        strspn(descr->port, "0123456789") != strlen(descr->port)) {
        LOGGIT(logdat, "must specify numeric port");
        goto Finish;
    }
    GOT_HERE;
    if (descr->source_address != NULL) {
	res = getaddrinfo(descr->source_address, descr->port,
                          &hints, &mcast_source_addrinfo);
	if (res != 0 || mcast_source_addrinfo == NULL) {
	    LOGGIT(logdat, "getaddrinfo(\"%s\", ...): %s",
                   descr->source_address, gai_strerror(res));
            goto Finish;
	}
        hints.ai_family = mcast_source_addrinfo->ai_family;
    }
    GOT_HERE;
    if (descr->mcast_ttl >= 0) {
        if (descr->mcast_ttl < 1 || descr->mcast_ttl > 255) {
            // XXX - It could make sense to use ttl 0 if we're talking on a loopback interface and we leave IP_MULTICAST_LOOP on.
            LOGGIT(logdat, "mcast_ttl(%d) out of range", descr->mcast_ttl);
            goto Finish;
        }
    }
    GOT_HERE;
    if (descr->address == NULL) {
        LOGGIT(logdat, "must specify remote address");
        goto Finish;
    }
#ifdef IPV6_JOIN_GROUP
    cp = strchr(descr->address, '%');
    GOT_HERE;
    if (cp != NULL) {
        cp++;
        errno = 0;
        if_index = atoi(cp);
        if (if_index == 0) {
            if_index = if_nametoindex(cp);
            if (if_index == 0 && errno != 0) {
                LOGGIT(logdat, "Invalid interface name %s", cp);
                goto Finish;
            }
        }
    }
#endif
    GOT_HERE;
    res = getaddrinfo(descr->address, descr->port,
                      &hints, &addrinfo);
    if (res != 0 || addrinfo == NULL) {
        LOGGIT(logdat, "getaddrinfo(\"%s\", ...): %s",
               descr->address, gai_strerror(res));
        goto Finish;
    }
    sock = socket(addrinfo->ai_family, addrinfo->ai_socktype, addrinfo->ai_protocol);
    GOT_HERE;
    if (sock == -1) {
        LOGGIT(logdat, "socket: %s", strerror(errno));
        goto Finish;
    }
    GOT_HERE;
    socks->recving = socks->sending = sock;
    if (mcast_source_addrinfo == NULL) {
        /* Try binding the port now to see if we need 2 sockets. */
        hints.ai_family = addrinfo->ai_family;
        hints.ai_socktype = addrinfo->ai_socktype;
        hints.ai_flags = AI_PASSIVE;
        GOT_HERE;
        res = getaddrinfo(NULL, descr->port, &hints, &laddrinfo);
        if (res != 0)
            goto Finish;
        GOT_HERE;
        res = bind(socks->sending, laddrinfo->ai_addr, laddrinfo->ai_addrlen);
        if (res == -1 && getbound) {
            mcast_source_addrinfo = laddrinfo;
            laddrinfo = NULL;
        }
    }
    if (mcast_source_addrinfo != NULL) {
        /*
         * We have a specific interface to bind to for sending.
         * mcast_source_addrinfo is the unicast address of this interface.
         * Since we need to bind the recving side to the multicast address,
         * we need two sockets in this case.
         *
         * Our caller may choose to provide the sending side.
         */
        socks->sending = -1;
        if (getbound) {
            GOT_HERE;
            socks->sending = getbound(getbounddat,
                                      mcast_source_addrinfo->ai_addr,
                                      mcast_source_addrinfo->ai_addrlen);
            if (socks->sending >= 0) {
                GOT_HERE;
                close_protect = socks->sending;
            }
        }
        if (socks->sending == -1)
            socks->sending = socket(addrinfo->ai_family, addrinfo->ai_socktype, addrinfo->ai_protocol);
        if (socks->sending == -1) {
            LOGGIT(logdat, "socket: %s", strerror(errno));
            goto Finish;
        }
        res = setsockopt(socks->recving, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        if (res == -1) {
            LOGGIT(logdat, "setsockopt(recving, ..., SO_REUSEADDR, ...): %s", strerror(errno));
            goto Finish;
        }
        /* bind the recving socket to the multicast address */
	res = bind(socks->recving, addrinfo->ai_addr, addrinfo->ai_addrlen);
        if (res == -1) {
            LOGGIT(logdat, "bind(recving, ...): %s", strerror(errno));
            goto Finish;
        }
    }
    GOT_HERE;
    res = set_multicast_socket_options(socks->recving, socks->sending,
                                       addrinfo,
                                       mcast_source_addrinfo,
                                       descr->mcast_ttl,
                                       if_index,
                                       logger,
                                       logdat);
    if (res < 0)
        goto Finish;
    if (mcast_source_addrinfo != NULL) {
        GOT_HERE;
        if (socks->sending != close_protect) {
            res = bind(socks->sending,
                       mcast_source_addrinfo->ai_addr,
                       mcast_source_addrinfo->ai_addrlen);
            if (res == -1) {
                LOGGIT(logdat, "bind(sending, ...): %s", strerror(errno));
                goto Finish;
            }
        }
    }
    GOT_HERE;
    result = 0;
    
Finish:
    if (addrinfo != NULL)
        freeaddrinfo(addrinfo);
    if (laddrinfo != NULL)
        freeaddrinfo(laddrinfo);
    if (mcast_source_addrinfo != NULL)
        freeaddrinfo(mcast_source_addrinfo);
    if (result != 0) {
        close(socks->recving);
        if (socks->sending != socks->recving && socks->sending != close_protect)
            close(socks->sending);
        socks->sending = socks->recving = -1;
    }
    return(result);
}
