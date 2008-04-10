#include <stdio.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdarg.h>
#include <errno.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <net/if.h>
#include <netdb.h>
#include <poll.h>
#include <string.h>

#include "ccn/ccn.h"
#include "ccn/ccnd.h"

#define UDPMAXBUF 8800

struct options {
    const char *localsockname;
    const char *remotehostname;
    char remoteport[8];
    char localport[8];
    unsigned int remoteifindex;
    int multicastttl;
    int debug;
};


void
usage(char *name) {
    fprintf(stderr, "Usage: %s [-d(ebug)] [-c ccnsocket] -h remotehost -r remoteport [-l localport] [-t multicastttl]\n", name);
}

void
udplink_fatal(char *format, ...)
{
    va_list ap;
    va_start(ap, format);

    fprintf(stderr, "udplink[%d]: ", getpid());
    vfprintf(stderr, format, ap);
    exit(1);
}

void
udplink_note(char *format, ...)
{
    va_list ap;
    va_start(ap, format);

    fprintf(stderr, "udplink[%d]: ", getpid());
    vfprintf(stderr, format, ap);
}

void
udplink_print_data(char *source, unsigned char *data, int start, int length)
{
    int i;
    fprintf(stderr, "%d bytes from %s:", length, source);
    for (i = 0; i < length; i++) {
        if ((i % 20) == 0) fprintf(stderr, "\n%4d: ", i);
        if (((i + 10) % 20) == 0) fprintf(stderr, "| ");
        fprintf(stderr, "%02x ", data[i + start]);
    }
    fprintf(stderr, "\n");
}

ssize_t
send_remote_unencapsulated(int s, struct addrinfo *r, unsigned char *buf, size_t start, size_t length) {
    ssize_t result;

    if (memcmp(&buf[start], CCN_EMPTY_PDU, CCN_EMPTY_PDU_LENGTH - 1) != 0) {
        return (-2);
    }
    result = sendto(s, buf + CCN_EMPTY_PDU_LENGTH - 1, length - CCN_EMPTY_PDU_LENGTH,
                    0, r->ai_addr, r->ai_addrlen);
    return (result);
}

void process_options(int argc, char * const argv[], struct options *options) {
    int c;
    char *cp;

    while ((c = getopt(argc, argv, "dc:h:r:l:t:")) != -1) {
        switch (c) {
        case 'd': {
            options->debug++;
        }
        case 'c': {
            options->localsockname = optarg;
            break;
        }
        case 'h': {
            options->remotehostname = optarg;
            break;
        }
        case 'r':
        case 'l': {
            int port = atoi(optarg);
            char portstr[8];
            if (port <= 0 || port >= 65536) {
                usage(argv[0]);
                udplink_fatal("port must be in range 1..65535\n");
            } 
            snprintf(portstr, sizeof(portstr), "%d", port);
            if (c == 'r') {
                memmove(options->remoteport, portstr, sizeof(options->remoteport));
            } else {
                memmove(options->localport, portstr, sizeof(options->localport));
            }
            break;
        }
        case 't': {
            int ttl = atoi(optarg);
            if (ttl < 1 || ttl > 255) {
                udplink_fatal("multicast ttl/hopcount must be in range 1..255\n");
            }
        }
        }
    }
    
    /* the remote end of the connection must be specified */
    if (options->remotehostname == NULL) {
        usage(argv[0]);
        udplink_fatal("remote hostname/address required\n");
    }

    if (options->remoteport[0] == '\0') {
        usage(argv[0]);
        udplink_fatal("remote port required\n");
    }

    cp = strchr(options->remotehostname, '%');
    if (cp != NULL) {
        cp++;
        errno = 0;
        options->remoteifindex = atoi(cp);
        if (options->remoteifindex == 0) {
            options->remoteifindex = if_nametoindex(cp);
            if (options->remoteifindex == 0 && errno != 0) {
                udplink_fatal("Invalid interface name %s\n", cp);
            }
        }
    }

    /* if the local port is not specified, default to same as remote port */
    if (options->localport[0] == '\0') {
        memmove(options->localport, options->remoteport, sizeof(options->localport));
    }
}

void
set_multicast_sockopt(int socket, struct addrinfo *ai, struct options *options)
{
    struct addrinfo hints;
    struct ip_mreq mreq;
    struct ipv6_mreq mreq6;
    unsigned char csockopt;
    unsigned int isockopt;
    int result;

    memset((void *)&hints, 0, sizeof(hints));
    memset((void *)&mreq, 0, sizeof(mreq));
    memset((void *)&mreq6, 0, sizeof(mreq6));

    if (ai->ai_family == PF_INET && IN_MULTICAST(ntohl(((struct sockaddr_in *)(ai->ai_addr))->sin_addr.s_addr))) {
        if (options->debug) udplink_note("IPv4 multicast\n");
#ifdef IP_ADD_MEMBERSHIP
        memcpy((void *)&mreq.imr_multiaddr, &((struct sockaddr_in *)ai->ai_addr)->sin_addr, sizeof(mreq.imr_multiaddr));
        if (options->remoteifindex != 0) {
            /* mreq.imr_interface.s_addr = local address of interface remoteifindex */
        }
        result = setsockopt(socket, IPPROTO_IP, IP_ADD_MEMBERSHIP, &mreq, sizeof(mreq));
        if (result == -1) {
            udplink_fatal("setsockopt(..., IP_ADD_MEMBERSHIP, ...): %s\n", strerror(errno));
        }
#endif
#ifdef IP_MULTICAST_LOOP
        csockopt = 0;
        result = setsockopt(socket, IPPROTO_IP, IP_MULTICAST_LOOP, &csockopt, sizeof(csockopt));
        if (result == -1) {
            udplink_fatal("setsockopt(..., IP_MULTICAST_LOOP, ...): %s\n", strerror(errno));
        }
#endif
#ifdef IP_MULTICAST_TTL
        if (options->multicastttl > 0) {
            csockopt = options->multicastttl;
            result = setsockopt(socket, IPPROTO_IP, IP_MULTICAST_TTL, &csockopt, sizeof(csockopt));
            if (result == -1) {
                udplink_fatal("setsockopt(..., IP_MULTICAST_TTL, ...): %s\n", strerror(errno));
            }
        }
#endif
    } else if (ai->ai_family == PF_INET6 && IN6_IS_ADDR_MULTICAST((&((struct sockaddr_in6 *)ai->ai_addr)->sin6_addr))) {
        if (options->debug) udplink_note("IPv6 multicast\n");
#ifdef IPV6_JOIN_GROUP
        memcpy((void *)&mreq6.ipv6mr_multiaddr, &((struct sockaddr_in6 *)ai->ai_addr)->sin6_addr, sizeof(mreq6.ipv6mr_multiaddr));
        if (options->remoteifindex > 0) {
            mreq6.ipv6mr_interface = options->remoteifindex;
        }
        result = setsockopt(socket, IPPROTO_IPV6, IPV6_JOIN_GROUP, &mreq6, sizeof(mreq6));
        if (result == -1) {
            udplink_fatal("setsockopt(..., IPV6_JOIN_GROUP, ...): %s\n", strerror(errno));
        }
#endif
#ifdef IPV6_MULTICAST_LOOP
        isockopt = 0;
        result = setsockopt(socket, IPPROTO_IPV6, IPV6_MULTICAST_LOOP, &isockopt, sizeof(isockopt));
        if (result == -1) {
            udplink_fatal("setsockopt(..., IPV6_MULTICAST_LOOP, ...): %s\n", strerror(errno));
        }
#endif
#ifdef IPV6_MULTICAST_HOPS
        if (options->multicastttl > 0) {
            isockopt = options->multicastttl;
            result = setsockopt(socket, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, &isockopt, sizeof(isockopt));
            if (result == -1) {
                udplink_fatal("setsockopt(..., IPV6_MULTICAST_LOOP, ...): %s\n", strerror(errno));
            }
        }
#endif
    }
}

int
main (int argc, char * const argv[]) {

    struct options options = {NULL, NULL, "", "", 0, 0, 0};

    int result;
    int localsock;
    int remotesock;
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
    unsigned char rbuf[UDPMAXBUF];
    struct ccn_charbuf *charbuf;
    ssize_t msgstart = 0;
    ssize_t recvlen = 0;
    ssize_t dres;

    process_options(argc, argv, &options);

    /* connect to the local ccn socket */
    ccn = ccn_create();
    localsock = ccn_connect(ccn, options.localsockname);
    if (localsock == -1) {
        udplink_fatal("ccn_connect: %s\n", strerror(errno));
    }

    hints.ai_family = PF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    hints.ai_flags = AI_ADDRCONFIG;
#ifdef AI_NUMERICSERV
    hints.ai_flags |= AI_NUMERICSERV;
#endif

    result = getaddrinfo(options.remotehostname, options.remoteport, &hints, &raddrinfo);
    if (result != 0 || raddrinfo == NULL) {
        udplink_fatal("getaddrinfo(%s, %s, ...): %s\n", options.remotehostname, options.remoteport, gai_strerror(result));
    }

    getnameinfo(raddrinfo->ai_addr, raddrinfo->ai_addrlen, canonical_remote, sizeof(canonical_remote), NULL, 0, 0);

    remotesock = socket(raddrinfo->ai_family, raddrinfo->ai_socktype, 0);
    if (remotesock == -1) {
        udplink_fatal("socket: %s\n", strerror(errno));
    }

    hints.ai_family = raddrinfo->ai_family;
    hints.ai_flags = AI_PASSIVE;
#ifdef AI_NUMERICSERV
    hints.ai_flags |= AI_NUMERICSERV;
#endif
    result = getaddrinfo(NULL, options.localport, &hints, &laddrinfo);
    if (result != 0 || laddrinfo == NULL) {
        udplink_fatal("getaddrinfo(NULL, %s, ...): %s\n", options.localport, gai_strerror(result));
    }
    
    result = bind(remotesock, laddrinfo->ai_addr, laddrinfo->ai_addrlen);
    if (result == -1) {
        udplink_fatal("bind(remotesock, local...): %s\n", strerror(errno));
    }

    udplink_note("connected to %s\n", canonical_remote);

    set_multicast_sockopt(remotesock, raddrinfo, &options);

    /* announce our presence to ccnd and request CCN PDU encapsulation */
    result = send(localsock, CCN_EMPTY_PDU, CCN_EMPTY_PDU_LENGTH, 0);
    if (result == -1) {
        udplink_fatal("initial send: %s\n", strerror(errno));
    }

    charbuf = ccn_charbuf_create();

    fds[0].fd = localsock;
    fds[0].events = POLLIN;
    fds[1].fd = remotesock;
    fds[1].events = POLLIN;

    for (;;) {
        if (0 == (result = poll(fds, 2, -1))) continue;
        if (-1 == result && errno != EINTR) {
            udplink_fatal("poll: %s\n", strerror(errno));
        }

        /* process local data */
        if (fds[0].revents & (POLLIN)) {
            unsigned char *lbuf = ccn_charbuf_reserve(charbuf, 32);
            if (charbuf->length == 0) {
                memset(ld, 0, sizeof(*ld));
            }
            recvlen = recv(localsock, lbuf , charbuf->limit - charbuf->length, 0);
            if (recvlen == -1) {
                udplink_fatal("recv(localsock, ...): %s\n", strerror(errno));
            }
            if (recvlen == 0) {
                break;
            }
            charbuf->length += recvlen;
            dres = ccn_skeleton_decode(ld, lbuf, recvlen);
            while (ld->state == 0 && ld->nest == 0) {
                if (options.debug) {
                    udplink_print_data("local", charbuf->buf, msgstart, ld->index - msgstart);
                }
                result = send_remote_unencapsulated(remotesock, raddrinfo, charbuf->buf, msgstart, ld->index - msgstart);
                if (result == -1) {
                    udplink_fatal("sendto(remotesock, rbuf, %ld): %s\n", (long)ld->index - msgstart, strerror(errno));
                }
                else if (result == -2) {
                    udplink_note("protocol error, missing CCN PDU encapsulation. Message dropped\n");
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
                udplink_fatal("local data protocol error\n");
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

            memmove(rbuf, CCN_EMPTY_PDU, CCN_EMPTY_PDU_LENGTH - 1);
            recvbuf = &rbuf[CCN_EMPTY_PDU_LENGTH - 1];
            recvlen = recvfrom(remotesock, recvbuf, sizeof(rbuf) - CCN_EMPTY_PDU_LENGTH,
                               0, &from, &fromlen);
            if (recvlen == sizeof(rbuf) - CCN_EMPTY_PDU_LENGTH) {
                udplink_note("remote packet too large, discarded\n");
                continue;
            }
            /* encapsulate, and send the packet out on the local side */
            recvbuf[recvlen] = CCN_EMPTY_PDU[CCN_EMPTY_PDU_LENGTH - 1];
            memset(rd, 0, sizeof(*rd));
            dres = ccn_skeleton_decode(rd, rbuf, recvlen + CCN_EMPTY_PDU_LENGTH);
            if (rd->state != 0 || dres != (recvlen + CCN_EMPTY_PDU_LENGTH)) {
                udplink_note("remote data protocol error\n");
                continue;
            }

            result = send(localsock, rbuf, recvlen + CCN_EMPTY_PDU_LENGTH, 0);
            if (result == -1) {
                udplink_fatal("sendto(localsock, rbuf, %ld): %s\n", (long) recvlen + CCN_EMPTY_PDU_LENGTH, strerror(errno));
            }
            if (options.debug) {
                udplink_print_data("remote", rbuf, 0, recvlen + CCN_EMPTY_PDU_LENGTH);
            }
        }
    }

    udplink_note("disconnected\n");
    ccn_destroy(&ccn);
    freeaddrinfo(raddrinfo);
    freeaddrinfo(laddrinfo);
    exit(0);
}
