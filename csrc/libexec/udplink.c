/**
 * @file udplink.c
 * @brief A CCNx link adaptor for UDP.
 *
 * @note Normally ccnd handles UDP directly, so this module is not used.
 *
 * A CCNx program.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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
#include <stdio.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdarg.h>
#include <errno.h>
#include <poll.h>
#include <string.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <net/if.h>
#include <netdb.h>

#if defined(NEED_GETADDRINFO_COMPAT)
    #include "getaddrinfo.h"
    #include "dummyin6.h"
#endif
#ifndef AI_ADDRCONFIG
#define AI_ADDRCONFIG 0 /*IEEE Std 1003.1-2001/Cor 1-2002, item XSH/TC1/D6/20*/
#endif

#include <ccn/ccn.h>
#include <ccn/ccnd.h>

void udplink_fatal(int line, char *format, ...);

#ifdef __CYGWIN__
/* if_nametoindex() is unsupported on cygwin */
unsigned if_nametoindex(const char *ifname) { 
    udplink_fatal(__LINE__, "interface name unsupported on cygwin");
    return 0; 
}
#endif

static struct options {
    const char *localsockname;
    const char *remotehostname;
    struct addrinfo *localif_for_mcast_addrinfo;
    char remoteport[8];
    char localport[8];
    unsigned int remoteifindex;
    int multicastttl;
    int logging;
} options = {NULL, NULL, NULL, "", "", 0, 0, 0};

/*
 * logging levels:
 *  0 - print very little
 *  1 - informational and sparse warnings
 *  2 - one line per packet
 *  3 - packet dumps
 */

void
usage(char *name) {
    fprintf(stderr, "Usage: %s [-d(ebug)] [-c ccnsocket] -h remotehost -r remoteport [-l localport] [-m multicastlocaladdress] [-t multicastttl]\n", name);
}

void
udplink_fatal(int line, char *format, ...)
{
    struct timeval t;
    va_list ap;
    va_start(ap, format);
    gettimeofday(&t, NULL);
    fprintf(stderr, "%d.%06d udplink[%d] line %d: ", (int)t.tv_sec, (unsigned)t.tv_usec, getpid(), line);
    vfprintf(stderr, format, ap);
    va_end(ap);
    exit(1);
}

void
udplink_note(char *format, ...)
{
    struct timeval t;
    va_list ap;
    va_start(ap, format);
    gettimeofday(&t, NULL);
    fprintf(stderr, "%d.%06d udplink[%d]: ", (int)t.tv_sec, (unsigned)t.tv_usec, getpid());
    vfprintf(stderr, format, ap);
    va_end(ap);
}

void
udplink_print_data(char *source, unsigned char *data, int start, int length, int logging)
{
    int i;
    
    udplink_note("%d bytes from %s", length, source);
    if (logging > 2) {
        fprintf(stderr, ":");
        for (i = 0; i < length; i++) {
            if ((i % 20) == 0) fprintf(stderr, "\n%4d: ", i);
            if (((i + 10) % 20) == 0) fprintf(stderr, "| ");
            fprintf(stderr, "%02x ", data[i + start]);
        }
    }
    fprintf(stderr, "\n");
}

ssize_t
send_remote_unencapsulated(int s, struct addrinfo *r, unsigned char *buf, size_t start, size_t length) {
    ssize_t result;

    if (memcmp(&buf[start], CCN_EMPTY_PDU, CCN_EMPTY_PDU_LENGTH - 1) != 0) {
        return (-2);
    }
    result = sendto(s, buf + CCN_EMPTY_PDU_LENGTH - 1 + start, length - CCN_EMPTY_PDU_LENGTH,
                    0, r->ai_addr, r->ai_addrlen);
    return (result);
}

void process_options(int argc, char * const argv[], struct options *opt) {
    int c;
    char *cp = NULL;
    char *rportstr = NULL;
    char *lportstr = NULL;
    char *mcastoutstr = NULL;
    char *ttlstr = NULL;
    struct addrinfo hints = {0};
    int result;
    int n;

    while ((c = getopt(argc, argv, "dc:h:r:l:m:t:")) != -1) {
        switch (c) {
        case 'd':
            opt->logging++;
            break;
        case 'c':
            opt->localsockname = optarg;
            break;
        case 'h':
            opt->remotehostname = optarg;
            break;
        case 'r':
            rportstr = optarg;
            break;
        case 'l':
            lportstr = optarg;
            break;
        case 'm':
	    mcastoutstr = optarg;
            break;
        case 't':
            ttlstr = optarg;
            break;
        }
    }
    
    /* the remote end of the connection must be specified */
    if (opt->remotehostname == NULL || rportstr == NULL) {
        usage(argv[0]);
        exit(1);
    }

    if (strspn(rportstr, "0123456789") != strlen(rportstr)) {
        usage(argv[0]);
        exit(1);
    }
    
    n = atoi(rportstr);
    if (n <= 0 || n >= 65536) {
        usage(argv[0]);
        exit(1);
    }
    sprintf(opt->remoteport, "%d", n);

    if (lportstr != NULL) {
        if (strspn(lportstr, "0123456789") != strlen(lportstr)) {
            usage(argv[0]);
            exit(1);
        }
        n = atoi(lportstr);
        if (n <= 0 || n >= 65536) {
            usage(argv[0]);
            exit(1);
        }
    }
    sprintf(opt->localport, "%d", n);

    if (mcastoutstr != NULL) {
	hints.ai_family = PF_INET;
	hints.ai_socktype = SOCK_DGRAM;
	hints.ai_flags =  AI_NUMERICHOST;
#ifdef AI_NUMERICSERV
	hints.ai_flags |= AI_NUMERICSERV;
#endif
	udplink_note("interface %s requested (port %s)\n", mcastoutstr, opt->localport);
	result = getaddrinfo(mcastoutstr, opt->localport, &hints, &opt->localif_for_mcast_addrinfo);
	if (result != 0 || opt->localif_for_mcast_addrinfo == NULL) {
	    udplink_fatal(__LINE__, "getaddrinfo(\"%s\", ...): %s\n", mcastoutstr, gai_strerror(result));
	}
    }

    if (ttlstr != NULL) {
        if (strspn(ttlstr, "0123456789") != strlen(ttlstr)) {
            usage(argv[0]);
            exit(1);
        }
        opt->multicastttl = atoi(ttlstr);
        if (opt->multicastttl < 1 || opt->multicastttl > 255) {
            usage(argv[0]);
            exit(1);
        }
    }

    cp = strchr(opt->remotehostname, '%');
    if (cp != NULL) {
        cp++;
        errno = 0;
        opt->remoteifindex = atoi(cp);
        if (opt->remoteifindex == 0) {
            opt->remoteifindex = if_nametoindex(cp);
            if (opt->remoteifindex == 0 && errno != 0) {
                udplink_fatal(__LINE__, "Invalid interface name %s\n", cp);
            }
        }
    }
}

void
set_multicast_sockopt(int socket_r, int socket_w, struct addrinfo *ai, struct options *opt)
{
    struct addrinfo hints;
    struct ip_mreq mreq;
#ifdef IPV6_JOIN_GROUP
    struct ipv6_mreq mreq6;
#endif
    unsigned char csockopt;
    unsigned int isockopt;
    int result;

    memset((void *)&hints, 0, sizeof(hints));
    memset((void *)&mreq, 0, sizeof(mreq));
#ifdef IPV6_JOIN_GROUP
    memset((void *)&mreq6, 0, sizeof(mreq6));
#endif

    if (ai->ai_family == PF_INET && IN_MULTICAST(ntohl(((struct sockaddr_in *)(ai->ai_addr))->sin_addr.s_addr))) {
        if (opt->logging > 0) udplink_note("IPv4 multicast\n");
#ifdef IP_ADD_MEMBERSHIP
        memcpy((void *)&mreq.imr_multiaddr, &((struct sockaddr_in *)ai->ai_addr)->sin_addr, sizeof(mreq.imr_multiaddr));
        if (opt->localif_for_mcast_addrinfo != NULL) {
            memcpy((void *)&mreq.imr_interface.s_addr, &((struct sockaddr_in *)opt->localif_for_mcast_addrinfo->ai_addr)->sin_addr, sizeof(mreq.imr_interface.s_addr));
        }
        result = setsockopt(socket_r, IPPROTO_IP, IP_ADD_MEMBERSHIP, &mreq, sizeof(mreq));
        if (result == -1) udplink_fatal(__LINE__, "setsockopt(..., IP_ADD_MEMBERSHIP, ...): %s\n", strerror(errno));
#endif
#ifdef IP_MULTICAST_LOOP
        csockopt = 0;
        result = setsockopt(socket_w, IPPROTO_IP, IP_MULTICAST_LOOP, &csockopt, sizeof(csockopt));
        if (result == -1) udplink_fatal(__LINE__, "setsockopt(..., IP_MULTICAST_LOOP, ...): %s\n", strerror(errno));
#endif
#ifdef IP_MULTICAST_TTL
        if (opt->multicastttl > 0) {
            csockopt = opt->multicastttl;
            result = setsockopt(socket_w, IPPROTO_IP, IP_MULTICAST_TTL, &csockopt, sizeof(csockopt));
            if (result == -1) {
                udplink_fatal(__LINE__, "setsockopt(..., IP_MULTICAST_TTL, ...): %s\n", strerror(errno));
            }
        }
#endif
    } else if (ai->ai_family == PF_INET6 && IN6_IS_ADDR_MULTICAST((&((struct sockaddr_in6 *)ai->ai_addr)->sin6_addr))) {
        if (opt->logging > 0) udplink_note("IPv6 multicast\n");
#ifdef IPV6_JOIN_GROUP
        memcpy((void *)&mreq6.ipv6mr_multiaddr, &((struct sockaddr_in6 *)ai->ai_addr)->sin6_addr, sizeof(mreq6.ipv6mr_multiaddr));
        if (opt->remoteifindex > 0) {
            mreq6.ipv6mr_interface = opt->remoteifindex;
        }
        result = setsockopt(socket_r, IPPROTO_IPV6, IPV6_JOIN_GROUP, &mreq6, sizeof(mreq6));
        if (result == -1) {
            udplink_fatal(__LINE__, "setsockopt(..., IPV6_JOIN_GROUP, ...): %s\n", strerror(errno));
        }
#endif
#ifdef IPV6_MULTICAST_LOOP
        isockopt = 0;
        result = setsockopt(socket_w, IPPROTO_IPV6, IPV6_MULTICAST_LOOP, &isockopt, sizeof(isockopt));
        if (result == -1) {
            udplink_fatal(__LINE__, "setsockopt(..., IPV6_MULTICAST_LOOP, ...): %s\n", strerror(errno));
        }
#endif
#ifdef IPV6_MULTICAST_HOPS
        if (opt->multicastttl > 0) {
            isockopt = opt->multicastttl;
            result = setsockopt(socket_w, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, &isockopt, sizeof(isockopt));
            if (result == -1) {
                udplink_fatal(__LINE__, "setsockopt(..., IPV6_MULTICAST_LOOP, ...): %s\n", strerror(errno));
            }
        }
#endif
    }
}

void
changeloglevel(int s) {
    switch (s) {
    case SIGUSR1:
        options.logging = 0;
        udplink_note("logging disabled\n");
        break;
    case SIGUSR2:
        if (options.logging < 10) options.logging++;
        udplink_note("log level %d\n", options.logging);
        break;
    }
    return;
}

int
main (int argc, char * const argv[]) {
    int result;
    int localsock_rw = 0;
    int remotesock_w = 0;
    int remotesock_r = 0;
    char canonical_remote[NI_MAXHOST] = "";
    struct addrinfo *raddrinfo = NULL;
    struct addrinfo *laddrinfo = NULL;
    struct addrinfo hints = {0};
    struct pollfd fds[2];
    struct ccn *ccn;
    struct ccn_skeleton_decoder ldecoder = {0};
    struct ccn_skeleton_decoder *ld = &ldecoder;
    struct ccn_skeleton_decoder rdecoder = {0};
    struct ccn_skeleton_decoder *rd = &rdecoder;
    unsigned char rbuf[CCN_MAX_MESSAGE_BYTES];
    struct ccn_charbuf *charbuf;
    ssize_t msgstart = 0;
    ssize_t dres;
    struct sigaction sigact_changeloglevel;
    unsigned char *deferredbuf = NULL;
    size_t deferredlen = 0;
    int dropped_count = 0;
    size_t dropped_bytes = 0;
    const int one = 1;

    process_options(argc, argv, &options);

    /* connect up signals for log level controls */
    memset(&sigact_changeloglevel, 0, sizeof(sigact_changeloglevel));
    sigact_changeloglevel.sa_handler = changeloglevel;
    sigaction(SIGUSR1, &sigact_changeloglevel, NULL);
    sigaction(SIGUSR2, &sigact_changeloglevel, NULL);

    /* connect to the local ccn socket */
    ccn = ccn_create();
    localsock_rw = ccn_connect(ccn, options.localsockname);
    if (localsock_rw == -1) {
        udplink_fatal(__LINE__, "ccn_connect: %s\n", strerror(errno));
    }

    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    hints.ai_flags = AI_ADDRCONFIG;
#ifdef AI_NUMERICSERV
    hints.ai_flags |= AI_NUMERICSERV;
#endif

    /* data we need for later */
    result = getaddrinfo(options.remotehostname, options.remoteport, &hints, &raddrinfo);
    if (result != 0 || raddrinfo == NULL) {
        udplink_fatal(__LINE__, "getaddrinfo(\"%s\", \"%s\", ...): %s\n", options.remotehostname, options.remoteport, gai_strerror(result));
    }

    getnameinfo(raddrinfo->ai_addr, raddrinfo->ai_addrlen, canonical_remote, sizeof(canonical_remote), NULL, 0, 0);


    hints.ai_family = raddrinfo->ai_family;
    hints.ai_flags = AI_PASSIVE;
#ifdef AI_NUMERICSERV
    hints.ai_flags |= AI_NUMERICSERV;
#endif

    result = getaddrinfo(NULL, options.localport, &hints, &laddrinfo);
    if (result != 0 || laddrinfo == NULL) {
        udplink_fatal(__LINE__, "getaddrinfo(NULL, %s, ...): %s\n", options.localport, gai_strerror(result));
    }

    /* set up the remote side */

    /* Linux systems work if you do things in this order:
     *
     * socket(PF_INET, SOCK_DGRAM, IPPROTO_IP) = 4	WRITER
     * socket(PF_INET, SOCK_DGRAM, IPPROTO_IP) = 5	READER
     * setsockopt(5, SOL_SOCKET, SO_REUSEADDR, [1], 4) = 0   READER
     * bind(5, {sa_family=AF_INET, sin_port=htons(19649), sin_addr=inet_addr("224.18.14.21")}, 16) = 0    READER
     * setsockopt(4, SOL_IP, IP_MULTICAST_TTL, [5], 4) = 0	WRITER
     * setsockopt(5, SOL_IP, IP_ADD_MEMBERSHIP, "\340\22\16\25\r\2t[", 8) = 0    READER
     * fcntl64(5, F_SETFD, 0x802) = 0
     * bind(4, {sa_family=AF_INET, sin_port=htons(19649), sin_addr=inet_addr("13.2.116.91")}, 16) = 0	WRITER
     */

    remotesock_w = socket(raddrinfo->ai_family, raddrinfo->ai_socktype, 0);
    if (remotesock_w == -1) {
        udplink_fatal(__LINE__, "socket: %s\n", strerror(errno));
    }
    remotesock_r = remotesock_w;

    if (options.localif_for_mcast_addrinfo != NULL) {
        /* We have a specific interface to bind to.  localif_for_mcast_addrinfo
           is actually the unicast ipv4 address of this interface. */

        remotesock_r = socket(raddrinfo->ai_family, raddrinfo->ai_socktype, 0);
        if (remotesock_r == -1) {
            udplink_fatal(__LINE__, "socket: %s\n", strerror(errno));
        }
        result = setsockopt(remotesock_r, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        if (result == -1) udplink_fatal(__LINE__, "setsockopt(remotesock_r, ..., SO_REUSEADDR, ...)");

        /* bind the listener to the multicast address */
	result = bind(remotesock_r, raddrinfo->ai_addr, raddrinfo->ai_addrlen);
        if (result == -1) {
            udplink_fatal(__LINE__, "bind(remotesock_r, local...): %s\n", strerror(errno));
        }
    }

    set_multicast_sockopt(remotesock_r, remotesock_w, raddrinfo, &options);

    if (options.localif_for_mcast_addrinfo != NULL) {
        result = bind(remotesock_w, options.localif_for_mcast_addrinfo->ai_addr, options.localif_for_mcast_addrinfo->ai_addrlen);
    } else {
        result = bind(remotesock_w, laddrinfo->ai_addr, laddrinfo->ai_addrlen);
    }
    if (result == -1) {
        udplink_fatal(__LINE__, "bind(remotesock_w, local...): %s\n", strerror(errno));
    }

    udplink_note("connected to %s:%s\n", canonical_remote, options.remoteport);


    /* announce our presence to ccnd and request CCNx PDU encapsulation */
    result = send(localsock_rw, CCN_EMPTY_PDU, CCN_EMPTY_PDU_LENGTH, 0);
    if (result == -1) {
        udplink_fatal(__LINE__, "initial send: %s\n", strerror(errno));
    }

    charbuf = ccn_charbuf_create();

    fds[0].fd = localsock_rw;
    fds[0].events = POLLIN;
    fds[1].fd = remotesock_r;
    fds[1].events = POLLIN;

    for (;;) {
        if (0 == (result = poll(fds, 2, -1))) continue;
        if (-1 == result) {
            if (errno == EINTR) continue;
            udplink_fatal(__LINE__, "poll: %s\n", strerror(errno));
        }
        /* process deferred send to local */
        if (fds[0].revents & (POLLOUT)) {
            fds[1].events |= POLLIN;
            fds[0].events &= ~POLLOUT;
            if (deferredlen > 0) {
                result = send(localsock_rw, deferredbuf, deferredlen, 0);
                if (result == -1 && (options.logging > 1 || errno != EAGAIN))
                    udplink_note("sendto(local, deferredbuf, %ld):"
                                 " %s (sending deferred)\n",
                                 (long) deferredlen, strerror(errno));
                if (result == deferredlen) {
                    /* success, but report drops at this point */
                    if (dropped_count != 0 && options.logging > 0) {
                        udplink_note("dropped %d from remote (%ld bytes)\n",
                                     dropped_count, (long)dropped_bytes);
                        dropped_count = 0;
                        dropped_bytes = 0;
                    }
                    deferredlen = 0;
                }
                else if (result > 0) {
                    memmove(deferredbuf,
                            deferredbuf + result,
                            deferredlen - result);
                    deferredlen -= result;
                    fds[0].events |= POLLOUT;
                }
                else
                    deferredlen = 0;
            }
        }

        /* process local data */
        if (fds[0].revents & (POLLIN)) {
            unsigned char *lbuf = ccn_charbuf_reserve(charbuf, 32);
            ssize_t recvlen;
            int tries;
            if (charbuf->length == 0) {
                memset(ld, 0, sizeof(*ld));
            }
            recvlen = recv(localsock_rw, lbuf , charbuf->limit - charbuf->length, 0);
            if (recvlen == -1) {
                if (errno == EAGAIN) continue;
                udplink_fatal(__LINE__, "recv(localsock_rw, ...): %s\n", strerror(errno));
            }
            if (recvlen == 0) {
                break;
            }
            charbuf->length += recvlen;
            dres = ccn_skeleton_decode(ld, lbuf, recvlen);
            tries = 0;
            while (ld->state == 0 && ld->nest == 0) {
                if (options.logging > 1)
                    udplink_print_data("local", charbuf->buf, msgstart, ld->index - msgstart, options.logging);
                result = send_remote_unencapsulated(remotesock_w, raddrinfo, charbuf->buf, msgstart, ld->index - msgstart);
                if (result == -1) {
                    if (errno == EAGAIN) continue;
                    if (errno == EPERM && (tries++ < 3)) {
                        /* don't die right away on this, it may be because the local firewall was set to drop some of the packets */
                        if (options.logging > 0)
                            udplink_note("sendto(remotesock_w, rbuf, %ld): %s (will retry)\n", (long) ld->index - msgstart, strerror(errno));
                        continue;
                    }
                    if (errno == ENOBUFS) {
                        /* If the O.S. is kind enough to tell us the output buffers are full, we'll just drop this packet ourselves. */
                        if (options.logging > 0)
                            udplink_note("sendto(remotesock_w, rbuf, %ld): %s (message dropped)\n", (long) ld->index - msgstart, strerror(errno));
                    }
                    else
                        udplink_fatal(__LINE__, "sendto(remotesock_w, rbuf, %ld): %s\n", (long)ld->index - msgstart, strerror(errno));
                }
                else if (result == -2) {
                    udplink_note("protocol error, missing CCNx PDU encapsulation. Message dropped\n");
                }

                msgstart = ld->index;
                if (msgstart == charbuf->length) {
                    charbuf->length = 0;
                    msgstart = 0;
                    break;
                }
                recvlen = charbuf->length - msgstart;
                dres = ccn_skeleton_decode(ld, charbuf->buf + msgstart, recvlen);
            }
            if (ld->state < 0) {
                udplink_fatal(__LINE__, "local data protocol error\n");
            }
            /* move partial message to start of buffer */
            if (msgstart < charbuf->length && msgstart > 0) {
                memmove(charbuf->buf, charbuf->buf + msgstart, charbuf->length - msgstart);
                charbuf->length -= msgstart;
                ld->index -= msgstart;
                msgstart = 0;
            }
        }

        /* process remote data */
        if (fds[1].revents & (POLLIN)) {
            struct sockaddr from = {0};
            socklen_t fromlen = sizeof(from);
            ssize_t recvlen;
            unsigned char *recvbuf;
            char addrbuf[128];

            memmove(rbuf, CCN_EMPTY_PDU, CCN_EMPTY_PDU_LENGTH - 1);
            recvbuf = &rbuf[CCN_EMPTY_PDU_LENGTH - 1];
            recvlen = recvfrom(remotesock_r, recvbuf, sizeof(rbuf) - CCN_EMPTY_PDU_LENGTH,
                               0, &from, &fromlen);
            if (options.logging > 1) {
                if (from.sa_family == AF_INET) {
                    inet_ntop(AF_INET, &((struct sockaddr_in *)&from)->sin_addr, addrbuf, sizeof(addrbuf));
                } else {
                    inet_ntop(AF_INET6, &((struct sockaddr_in6 *)&from)->sin6_addr, addrbuf, sizeof(addrbuf));
                }
                udplink_print_data(addrbuf, recvbuf, 0, recvlen, options.logging);
            }
            if (recvlen == sizeof(rbuf) - CCN_EMPTY_PDU_LENGTH) {
                udplink_note("remote packet too large, discarded\n");
                continue;
            }
            if (deferredlen != 0) {
                dropped_count++;
                dropped_bytes += recvlen;
                continue;
            }
            /* encapsulate, and send the packet out on the local side */
            recvbuf[recvlen] = CCN_EMPTY_PDU[CCN_EMPTY_PDU_LENGTH - 1];
            memset(rd, 0, sizeof(*rd));
            dres = ccn_skeleton_decode(rd, rbuf, recvlen + CCN_EMPTY_PDU_LENGTH);
            if (rd->state != 0 || dres != (recvlen + CCN_EMPTY_PDU_LENGTH)) {
                if (recvlen == 1)
                    udplink_note("remote data protocol error (1 byte recv): likely heartbeat from app sending to wrong port\n");
                else
                    udplink_note("remote data protocol error\n");
                continue;
            }

            result = send(localsock_rw, rbuf, recvlen + CCN_EMPTY_PDU_LENGTH, 0);
            if (result == -1) {
                if (errno == EAGAIN) {
                    // XXX if we clear POLLIN the kernel may drop packets
                    // when it runs out of udp buffer space. It's not clear
                    // whether that is preferable to dropping them ourselves.
                    //fds[1].events &= ~POLLIN;
                    fds[1].events &= ~POLLIN;
                    fds[0].events |= POLLOUT;
                    deferredbuf = realloc(deferredbuf, recvlen + CCN_EMPTY_PDU_LENGTH);
                    deferredlen = recvlen + CCN_EMPTY_PDU_LENGTH;
                    memcpy(deferredbuf, rbuf, deferredlen);
                    if (options.logging > 1)
                        udplink_note("sendto(localsock_rw, rbuf, %ld): %s (deferred)\n", (long) deferredlen, strerror(errno));
                    continue;
                } else {
                    udplink_fatal(__LINE__, "sendto(localsock_rw, rbuf, %ld): %s\n", (long) recvlen + CCN_EMPTY_PDU_LENGTH, strerror(errno));
                }
            }
            if (result != recvlen + CCN_EMPTY_PDU_LENGTH) {
                //fds[1].events &= ~POLLIN;
                fds[0].events |= POLLOUT;
                deferredlen = recvlen + CCN_EMPTY_PDU_LENGTH - result;
                deferredbuf = realloc(deferredbuf, deferredlen);
                memcpy(deferredbuf, rbuf + result, deferredlen);
                if (options.logging > 0)
                    udplink_note("sendto(localsock_rw, rbuf, %ld): %s (deferred partial)\n", (long) deferredlen, strerror(errno));
                continue;
            }
        }
    }

    udplink_note("disconnected\n");
    ccn_destroy(&ccn);
    freeaddrinfo(raddrinfo);
    freeaddrinfo(laddrinfo);
    if (deferredbuf != NULL) {
        free(deferredbuf);
        deferredbuf = NULL;
    }
    exit(0);
}
