#include <stdio.h>
#include <unistd.h>
#include <stdlib.h>
#include <errno.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netdb.h>
#include <poll.h>
#include <string.h>

#include "ccn/ccn.h"
#include "ccn/ccnd.h"

void
usage(char *name) {
    fprintf(stderr, "Usage: %s [-d(ebug)] [-c ccnsocket] -h remotehost -r remoteport [-l localport]\n", name);
}

ssize_t
send_remote(int s, struct addrinfo *r, unsigned char *buf, size_t start, size_t length) {
    ssize_t result;

    if (memcmp(&buf[start], CCN_EMPTY_PDU, CCN_EMPTY_PDU_LENGTH - 1) != 0) {
        return (-2);
    }
    result = sendto(s, buf + CCN_EMPTY_PDU_LENGTH - 1, length - CCN_EMPTY_PDU_LENGTH,
                    0, r->ai_addr, r->ai_addrlen);
    return (result);
}

int
main (int argc, char * const argv[]) {

    struct options {
        const char *localsockname;
        const char *remotehostname;
        char remoteport[8];
        char localport[8];
        int debug;
    } options = {NULL, NULL, "", "", 0};

    int c, i;
    int result;
    int localsock;
    int remotesock;
    char canonical_remote[NI_MAXHOST] = "";
    struct addrinfo hints = {0};
    struct addrinfo *raddrinfo = NULL;
    struct addrinfo *laddrinfo = NULL;
    struct pollfd fds[2];
    struct ccn *ccn;
    struct ccn_skeleton_decoder ldecoder = {0};
    struct ccn_skeleton_decoder *ld = &ldecoder;
    struct ccn_skeleton_decoder rdecoder = {0};
    struct ccn_skeleton_decoder *rd = &rdecoder;
    unsigned char rbuf[8800];
    struct ccn_charbuf *charbuf;
    ssize_t msgstart = 0;
    ssize_t recvlen = 0;

    while ((c = getopt(argc, argv, "dc:h:r:l:")) != -1) {
        switch (c) {
        case 'd': {
            options.debug++;
        }
        case 'c': {
            options.localsockname = optarg;
            break;
        }
        case 'h': {
            options.remotehostname = optarg;
            break;
        }
        case 'r':
        case 'l': {
            int port = atoi(optarg);
            char portstr[8];
            if (port <= 0 || port >= 65536) {
                usage(argv[0]);
                fprintf(stderr, "port must be in range 1..65535\n");
                exit(1);
            } 
            snprintf(portstr, sizeof(portstr), "%d", port);
            if (c == 'r') {
                memmove(options.remoteport, portstr, sizeof(options.remoteport));
            } else {
                memmove(options.localport, portstr, sizeof(options.localport));
            }
            break;
        }
        }
    }
    
    /* the remote end of the connection must be specified */
    if (options.remotehostname == NULL) {
        usage(argv[0]);
        fprintf(stderr, "remote hostname/address required\n");
        exit(1);
    }

    if (options.remoteport[0] == '\0') {
        usage(argv[0]);
        fprintf(stderr, "remote port required\n");
        exit(1);
    }

    /* if the local port is not specified, default to same as remote port */
    if (options.localport[0] == '\0') {
        memmove(options.localport, options.remoteport, sizeof(options.localport));
    }

    /* connect to the local ccn socket */
    ccn = ccn_create();
    localsock = ccn_connect(ccn, options.localsockname);
    if (localsock == -1) {
        fprintf(stderr, "ccn_connect: %s\n", strerror(errno));
        exit(1);
    }

    hints.ai_family = PF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    hints.ai_flags = AI_ADDRCONFIG;
#ifdef AI_NUMERICSERV
    hints.ai_flags |= AI_NUMERICSERV;
#endif

    result = getaddrinfo(options.remotehostname, options.remoteport, &hints, &raddrinfo);
    if (result != 0 || raddrinfo == NULL) {
        fprintf(stderr, "getaddrinfo(%s, %s, ...): %s\n", options.remotehostname, options.remoteport, gai_strerror(result));
        exit(1);
    }
    getnameinfo(raddrinfo->ai_addr, raddrinfo->ai_addrlen, canonical_remote, sizeof(canonical_remote), NULL, 0, 0);

    hints.ai_family = raddrinfo->ai_family;
    hints.ai_flags = AI_PASSIVE;
#ifdef AI_NUMERICSERV
    hints.ai_flags |= AI_NUMERICSERV;
#endif
    result = getaddrinfo(NULL, options.localport, &hints, &laddrinfo);
    if (result != 0 || laddrinfo == NULL) {
        fprintf(stderr, "getaddrinfo(NULL, %s, ...): %s\n", options.localport, gai_strerror(result));
        exit(1);
    }

    remotesock = socket(raddrinfo->ai_family, raddrinfo->ai_socktype, 0);
    if (remotesock == -1) {
        fprintf(stderr, "socket: %s\n", strerror(errno));
        exit(1);
    }


    /* XXX we may need to play games for multicast here if we want to specify other than the default interface */

    if (raddrinfo->ai_family == PF_INET && IN_MULTICAST(((struct sockaddr_in *)(raddrinfo->ai_addr))->sin_addr.s_addr)) {
        /* IPv4 multicast */
        if (options.debug) fprintf(stderr, "it's an IPv4 multicast\n");
    } else if (raddrinfo->ai_family == PF_INET6 && IN6_IS_ADDR_MULTICAST((&((struct sockaddr_in6 *)raddrinfo->ai_addr)->sin6_addr))) {
        /* IPv6 multicast */
        if (options.debug) fprintf(stderr, "it's an IPv6 multicast\n");
    }

    result = bind(remotesock, laddrinfo->ai_addr, laddrinfo->ai_addrlen);
    if (result == -1) {
        fprintf(stderr, "bind(remotesock, local...): %s\n", strerror(errno));
        exit(1);
    }


    /* announce our presence to ccnd and request CCN PDU encapsulation */
    result = send(localsock, CCN_EMPTY_PDU, CCN_EMPTY_PDU_LENGTH, 0);
    if (result == -1) {
        fprintf(stderr, "initial send: %s\n", strerror(errno));
        exit(1);
    }

    charbuf = ccn_charbuf_create();

    fprintf(stderr, "udplink[%d] connected to %s\n", getpid(), canonical_remote);

    fds[0].fd = localsock;
    fds[0].events = POLLIN;
    fds[1].fd = remotesock;
    fds[1].events = POLLIN;

    for (;;) {
        if (0 == (result = poll(fds, 2, -1))) continue;
        if (-1 == result) {
            (1, "poll");
        }

        /* process local data */
        if (fds[0].revents & (POLLIN)) {
            ssize_t dres;
            unsigned char *lbuf = ccn_charbuf_reserve(charbuf, 32);
            if (charbuf->length == 0) {
                memset(ld, 0, sizeof(*ld));
            }
            recvlen = recv(localsock, lbuf , charbuf->limit - charbuf->length, 0);
            if (recvlen == -1) {
                fprintf(stderr, "recv(localsock, ...): %s\n", strerror(errno));
                exit(1);
            }
            if (recvlen == 0) {
                fprintf(stderr, "udplink[%d] ccnd disconnected\n", getpid());
                break;
            }
            charbuf->length += recvlen;
            msgstart = 0;
            dres = ccn_skeleton_decode(ld, lbuf, recvlen);
            while (ld->state == 0 && ld->tagstate == 0 && ld->nest == 0) {
                fprintf(stderr, "%lu byte msg accepted\n", (unsigned long) (ld->index - msgstart));

                /* send the packet out on the remote side, removing CCNPDU encapsulation */
                result = send_remote(remotesock, raddrinfo, charbuf->buf, msgstart, ld->index - msgstart);
                if (result == -1) {
                    fprintf(stderr, "udplink[%d] sendto(remotesock, rbuf, %d): %s\n", getpid(), dres - CCN_EMPTY_PDU_LENGTH, strerror(errno));
                    exit(1);
                }
                else if (result == -2) {
                    fprintf(stderr, "udplink[%d] protocol error, missing CCNPDU encapsulation. Message dropped\n");
                }

                if (options.debug) {
                    for (i = msgstart; i < ld->index; i++) {
                        fprintf(stderr, "%02x ", charbuf->buf[i]);
                    }
                    fprintf(stderr, "\n");
                }
                msgstart = ld->index;
                if (msgstart == charbuf->length) {
                    charbuf->length = 0;
                    break;
                }
                dres = ccn_skeleton_decode(ld, charbuf->buf + msgstart, recvlen = charbuf->length - msgstart);
            }
            if (ld->state < 0) {
                fprintf(stderr, "udplink[%d]: local data protocol error\n", getpid());
                exit(1);
            }
            if (msgstart < charbuf->length && msgstart > 0) {
                /* move partial message to start of buffer */
                memmove(charbuf->buf, charbuf->buf + msgstart, charbuf->length - msgstart);
                charbuf->length -= msgstart;
                ld->index -= msgstart;
            }
        }

        /* process remote data */
        if (fds[1].revents & (POLLIN)) {
            struct sockaddr from = {0};
            socklen_t fromlen = sizeof(from);
            memset(rbuf, 0, sizeof(rbuf));
            memmove(rbuf, CCN_EMPTY_PDU, CCN_EMPTY_PDU_LENGTH - 1);
            
            unsigned char *recvbuf;
            recvbuf = &rbuf[CCN_EMPTY_PDU_LENGTH - 1];
            ssize_t recvlen = recvfrom(remotesock, recvbuf,
                                       sizeof(rbuf) - CCN_EMPTY_PDU_LENGTH,
                                       0, &from, &fromlen);
            /* encapsulate, and send the packet out on the local side */
            recvbuf[recvlen] = '\0';
            result = send(localsock, rbuf, recvlen + CCN_EMPTY_PDU_LENGTH, 0);
            if (result == -1) {
                fprintf(stderr, "sendto(localsock, rbuf, %d): %s\n", recvlen + CCN_EMPTY_PDU_LENGTH, strerror(errno));
                exit(1);
            }
            fprintf(stderr, "remote->local %ld bytes in, sent %ld bytes to local\n", (long) recvlen, (long)recvlen + CCN_EMPTY_PDU_LENGTH);
            if (options.debug) {
                for (i = 0; i < recvlen + CCN_EMPTY_PDU_LENGTH; i++) {
                    fprintf(stderr, "%02x ", rbuf[i]);
                }
                fprintf(stderr, "\n");
            }
        }
    }
    ccn_destroy(&ccn);
    freeaddrinfo(raddrinfo);
    freeaddrinfo(laddrinfo);
}
