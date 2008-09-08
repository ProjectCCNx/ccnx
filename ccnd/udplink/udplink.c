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

#include <ccn/ccn.h>
#include <ccn/ccnd.h>

#define UDPMAXBUF 8800

static struct options {
    const char *localsockname;
    const char *remotehostname;
    struct addrinfo *multicastaddrinfo;
    char remoteport[8];
    char localport[8];
    unsigned int remoteifindex;
    int multicastttl;
    int logging;
} options = {NULL, NULL, NULL, "", "", 0, 0, 0};


void
usage(char *name) {
    fprintf(stderr, "Usage: %s [-d(ebug)] [-c ccnsocket] -h remotehost -r remoteport [-l localport] [-m multicastlocaladdress] [-t multicastttl]\n", name);
}

void
udplink_fatal(char *format, ...)
{
    struct timeval t;
    va_list ap;
    va_start(ap, format);

    gettimeofday(&t, NULL);
    fprintf(stderr, "%d.%06d udplink[%d]: ", (int)t.tv_sec, (unsigned)t.tv_usec, getpid());
    vfprintf(stderr, format, ap);
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
}

void
udplink_print_data(char *source, unsigned char *data, int start, int length)
{
    int i;

    udplink_note("%d bytes from %s:", length, source);
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
    result = sendto(s, buf + CCN_EMPTY_PDU_LENGTH - 1 + start, length - CCN_EMPTY_PDU_LENGTH,
                    0, r->ai_addr, r->ai_addrlen);
    return (result);
}

void process_options(int argc, char * const argv[], struct options *options) {
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
            options->logging++;
            break;
        case 'c':
            options->localsockname = optarg;
            break;
        case 'h':
            options->remotehostname = optarg;
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
    if (options->remotehostname == NULL || rportstr == NULL) {
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
    sprintf(options->remoteport, "%d", n);

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
    sprintf(options->localport, "%d", n);

    if (mcastoutstr != NULL) {
	hints.ai_family = PF_INET;
	hints.ai_socktype = SOCK_DGRAM;
	hints.ai_flags =  AI_NUMERICHOST;
#ifdef AI_NUMERICSERV
	hints.ai_flags |= AI_NUMERICSERV;
#endif
	udplink_note("interface %s requested (port %s)\n", mcastoutstr, options->localport);
	result = getaddrinfo(mcastoutstr, options->localport, &hints, &options->multicastaddrinfo);
	if (result != 0 || options->multicastaddrinfo == NULL) {
	    udplink_fatal("getaddrinfo(\"%s\", ...): %s\n", mcastoutstr, gai_strerror(result));
	}
    }

    if (ttlstr != NULL) {
        if (strspn(ttlstr, "0123456789") != strlen(ttlstr)) {
            usage(argv[0]);
            exit(1);
        }
        options->multicastttl = atoi(ttlstr);
        if (options->multicastttl < 1 || options->multicastttl > 255) {
            usage(argv[0]);
            exit(1);
        }
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
        if (options->logging > 1) udplink_note("IPv4 multicast\n");
#ifdef IP_ADD_MEMBERSHIP
        memcpy((void *)&mreq.imr_multiaddr, &((struct sockaddr_in *)ai->ai_addr)->sin_addr, sizeof(mreq.imr_multiaddr));
        if (options->multicastaddrinfo != NULL) {
            memcpy((void *)&mreq.imr_interface.s_addr, &((struct sockaddr_in *)options->multicastaddrinfo->ai_addr)->sin_addr, sizeof(mreq.imr_interface.s_addr));
        }
        result = setsockopt(socket, IPPROTO_IP, IP_ADD_MEMBERSHIP, &mreq, sizeof(mreq));
        if (result == -1) udplink_fatal("setsockopt(..., IP_ADD_MEMBERSHIP, ...): %s\n", strerror(errno));
#endif
#ifdef IP_MULTICAST_LOOP
        csockopt = 0;
        result = setsockopt(socket, IPPROTO_IP, IP_MULTICAST_LOOP, &csockopt, sizeof(csockopt));
        if (result == -1) udplink_fatal("setsockopt(..., IP_MULTICAST_LOOP, ...): %s\n", strerror(errno));
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
        if (options->logging > 1) udplink_note("IPv6 multicast\n");
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

void
changeloglevel(int s) {
    switch (s) {
    case SIGUSR1:
        options.logging = 0;
        udplink_note("logging disabled\n");
        break;
    case SIGUSR2:
        if (options.logging < 100) options.logging++;
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
    struct addrinfo *raddrinfoz = NULL;
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
    struct sigaction sigact_changeloglevel;
    unsigned char *deferredbuf = NULL;
    size_t deferredlen = 0;
    const int one = 1;

    process_options(argc, argv, &options);
    memset(&sigact_changeloglevel, 0, sizeof(sigact_changeloglevel));
    sigact_changeloglevel.sa_handler = changeloglevel;
    sigaction(SIGUSR1, &sigact_changeloglevel, NULL);
    sigaction(SIGUSR2, &sigact_changeloglevel, NULL);

    /* connect to the local ccn socket */
    ccn = ccn_create();
    localsock_rw = ccn_connect(ccn, options.localsockname);
    if (localsock_rw == -1) {
        udplink_fatal("ccn_connect: %s\n", strerror(errno));
    }

    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    hints.ai_flags = AI_ADDRCONFIG;
#ifdef AI_NUMERICSERV
    hints.ai_flags |= AI_NUMERICSERV;
#endif

    result = getaddrinfo(options.remotehostname, options.remoteport, &hints, &raddrinfo);
    if (result != 0 || raddrinfo == NULL) {
        udplink_fatal("getaddrinfo(\"%s\", \"%s\", ...): %s\n", options.remotehostname, options.remoteport, gai_strerror(result));
    }

    getnameinfo(raddrinfo->ai_addr, raddrinfo->ai_addrlen, canonical_remote, sizeof(canonical_remote), NULL, 0, 0);

    remotesock_r = socket(raddrinfo->ai_family, raddrinfo->ai_socktype, 0);
    if (remotesock_r == -1) {
        udplink_fatal("socket: %s\n", strerror(errno));
    }
    result = setsockopt(remotesock_r, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    if (result == -1) {
        udplink_fatal("setsockopt(remotesock_r, ..., SO_REUSEADDR, ...): %s\n", strerror(errno));
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
    if (options.multicastaddrinfo != NULL) {
	/* We have a specific interface to bind to.  multicastaddrinfo
 	   is actually the unicast ipv4 address of this interface. */

        remotesock_w = socket(raddrinfo->ai_family, raddrinfo->ai_socktype, 0);
        if (remotesock_w == -1) {
            udplink_fatal("socket: %s\n", strerror(errno));
        }
	result = bind(remotesock_w, options.multicastaddrinfo->ai_addr, options.multicastaddrinfo->ai_addrlen);
        if (result == -1) {
            udplink_fatal("bind(remotesock_w, local...): %s\n", strerror(errno));
        }

    }
    result = bind(remotesock_r, laddrinfo->ai_addr, laddrinfo->ai_addrlen);
    if (result == -1 && errno == EADDRINUSE) {
        
        result = getaddrinfo(options.remotehostname, NULL, &hints, &raddrinfoz);
        if (result == -1) udplink_fatal("getaddrinfo()");
        result = bind(remotesock_r, raddrinfoz->ai_addr, raddrinfoz->ai_addrlen);
    }
    if (result == -1) {
        udplink_fatal("bind(remotesock_r, local...): %s\n", strerror(errno));
    }

    udplink_note("connected to %s:%s\n", canonical_remote, options.remoteport);

    set_multicast_sockopt(remotesock_r, raddrinfo, &options);

    /* announce our presence to ccnd and request CCN PDU encapsulation */
    result = send(localsock_rw, CCN_EMPTY_PDU, CCN_EMPTY_PDU_LENGTH, 0);
    if (result == -1) {
        udplink_fatal("initial send: %s\n", strerror(errno));
    }

    charbuf = ccn_charbuf_create();

    fds[0].fd = localsock_rw;
    fds[0].events = POLLIN;
    if (remotesock_r != 0) {
        fds[1].fd = remotesock_r;
        fds[1].events = POLLIN;
    } else {
        /* no need to split the sockets for send and receive */
        remotesock_w = remotesock_r;
        fds[1].fd = remotesock_r;
        fds[1].events = POLLIN;
    }
    for (;;) {
        if (0 == (result = poll(fds, 2, -1))) continue;
        if (-1 == result) {
            if (errno == EINTR) continue;
            udplink_fatal("poll: %s\n", strerror(errno));
        }
        /* process deferred send to local */
        if (fds[0].revents & (POLLOUT)) {
            fds[1].events |= POLLIN;
            fds[0].events &= ~POLLOUT;
            if (deferredlen > 0) {
                result = send(localsock_rw, deferredbuf, deferredlen, 0);
                if (result != deferredlen && options.logging > 1)
                    udplink_note("sendto(local, deferredbuf, %ld): %s (deferred)\n", (long) deferredlen, strerror(errno));
                deferredlen = 0;
            }
        }

        /* process local data */
        if (fds[0].revents & (POLLIN)) {
            unsigned char *lbuf = ccn_charbuf_reserve(charbuf, 32);
            if (charbuf->length == 0) {
                memset(ld, 0, sizeof(*ld));
            }
            recvlen = recv(localsock_rw, lbuf , charbuf->limit - charbuf->length, 0);
            if (recvlen == -1) {
                if (errno == EAGAIN) continue;
                udplink_fatal("recv(localsock_rw, ...): %s\n", strerror(errno));
            }
            if (recvlen == 0) {
                break;
            }
            charbuf->length += recvlen;
            dres = ccn_skeleton_decode(ld, lbuf, recvlen);
            while (ld->state == 0 && ld->nest == 0) {
                if (options.logging > 1) {
                    udplink_print_data("local", charbuf->buf, msgstart, ld->index - msgstart);
                }
                result = send_remote_unencapsulated(remotesock_w, raddrinfo, charbuf->buf, msgstart, ld->index - msgstart);
                if (result == -1) {
                    if (errno == EAGAIN) continue;
                    udplink_fatal("sendto(remotesock_w, rbuf, %ld): %s\n", (long)ld->index - msgstart, strerror(errno));
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
            char addrbuf[128];

            memmove(rbuf, CCN_EMPTY_PDU, CCN_EMPTY_PDU_LENGTH - 1);
            recvbuf = &rbuf[CCN_EMPTY_PDU_LENGTH - 1];
            recvlen = recvfrom(remotesock_r, recvbuf, sizeof(rbuf) - CCN_EMPTY_PDU_LENGTH,
                               0, &from, &fromlen);
            if (options.logging > 0) {
                if (from.sa_family == AF_INET) {
                    inet_ntop(AF_INET, &((struct sockaddr_in *)&from)->sin_addr, addrbuf, sizeof(addrbuf));
                } else {
                    inet_ntop(AF_INET6, &((struct sockaddr_in6 *)&from)->sin6_addr, addrbuf, sizeof(addrbuf));
                }
                udplink_note("%d bytes from %s\n", recvlen, addrbuf);
            }
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

            result = send(localsock_rw, rbuf, recvlen + CCN_EMPTY_PDU_LENGTH, 0);
            if (result == -1) {
                if (errno == EAGAIN) {
                    fds[1].events &= ~POLLIN;
                    fds[0].events |= POLLOUT;
                    deferredbuf = realloc(deferredbuf, recvlen + CCN_EMPTY_PDU_LENGTH);
                    deferredlen = recvlen + CCN_EMPTY_PDU_LENGTH;
                    memcpy(deferredbuf, rbuf, deferredlen);
                    if (options.logging > 0)
                        udplink_note("sendto(localsock_rw, rbuf, %ld): %s (deferred)\n", (long) deferredlen, strerror(errno));
                    continue;
                } else {
                    udplink_fatal("sendto(localsock_rw, rbuf, %ld): %s\n", (long) recvlen + CCN_EMPTY_PDU_LENGTH, strerror(errno));
                }
            }
            if (result != recvlen + CCN_EMPTY_PDU_LENGTH) abort();
            if (options.logging > 1) {
                udplink_print_data("remote", rbuf, 0, recvlen + CCN_EMPTY_PDU_LENGTH);
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
