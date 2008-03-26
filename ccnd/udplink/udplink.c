#include <stdio.h>
#include <unistd.h>
#include <stdlib.h>
#include <err.h>
#include <sys/socket.h>
#include <netdb.h>
#include <poll.h>
#include <string.h>

#include "ccn/ccnd.h"
#include "ccn/coding.h"

int
main (int argc, char * const argv[]) {

    struct {
        const char *localsockname;
        const char *remotehostname;
        char remoteport[8];
        char localport[8];
        int debug;
    } options = {NULL, NULL, NULL, 0};

    int c;
    int result;
    int localsock;
    int remotesock;
    int timeout_1s = 1000;
    struct sockaddr_un localsockaddr = {0};
    struct addrinfo hints = {0};
    struct addrinfo *remote_addrinfo_result = NULL;
    struct addrinfo *local_addrinfo_result = NULL;
    struct pollfd fds[2];
    void *ccn;
    struct ccn_skeleton_decoder decoder = {0};
    unsigned char buf[PIPE_BUF];

    while ((c = getopt(argc, argv, "dl:r:p:P:")) != -1) {
        switch (c) {
        case 'd': {
            options.debug = 1;
        }
        case 'l': {
            options.localsockname = optarg;
            break;
        }
        case 'r': {
            options.remotehostname = optarg;
            break;
        }
        case 'p': {
            int port = atoi(optarg);
            if (port <= 0 || port >= 65536) {
                errx(1, "remote port must be in range 1..65535");
            } 
            snprintf(options.remoteport, sizeof(options.remoteport), "%d", port);
            break;
        }
        case 'P': {
            int port = atoi(optarg);
            if (port <= 0 || port >= 65536) {
                errx(1, "local port must be in range 1..65535");
            } 
            snprintf(options.localport, sizeof(options.localport), "%d", port);
            break;
        }
        }
    }
    
    /* the remote end of the connection must be specified */
    if (options.remotehostname == NULL) {
        errx(1, "remote host/address required");
    }

    if (options.remoteport[0] == '\0') {
        errx(1, "remote port required");
    }

    /* connect to the local ccn socket */
    ccn = ccn_create();
    localsock = ccn_connect(ccn, options.localsockname);

    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
#ifdef AI_NUMERICSERV
    hints.ai_flags = AI_NUMERICSERV;
#endif
    result = getaddrinfo(options.remotehostname, options.remoteport, &hints, &remote_addrinfo_result);
    if (result != 0 || remote_addrinfo_result == NULL) {
        errx(1, "getaddrinfo(%s, %s, ...): %s\n", options.remotehostname, options.remoteport, gai_strerror(result));
    }

    hints.ai_family = remote_addrinfo_result->ai_family;
    hints.ai_flags = AI_PASSIVE;
#ifdef AI_NUMERICSERV
    hints.ai_flags |= AI_NUMERICSERV;
#endif
    result = getaddrinfo(NULL, options.localport, &hints, &local_addrinfo_result);
    if (result != 0 || local_addrinfo_result == NULL) {
        errx(1, "getaddrinfo(NULL, %s, ...): %s\n", options.localport, gai_strerror(result));
    }

    remotesock = socket(remote_addrinfo_result->ai_family, remote_addrinfo_result->ai_socktype, 0);
    if (remotesock == -1) {
        err(1, "socket");
    }

    result = bind(remotesock, local_addrinfo_result->ai_addr, local_addrinfo_result->ai_addrlen);
    if (result == -1) {
        err(1, "bind(remotesock, local...)");
    }
    /* XXX we need to play games for multicast here */

    /* announce our presence to ccnd and request CCN PDU encapsulation */
    result = send(localsock, CCN_EMPTY_PDU, CCN_EMPTY_PDU_LENGTH, 0);
    if (result == -1) {
        err(1, "initial send");
    }

    fds[0].fd = localsock;
    fds[0].events = POLLIN;
    fds[1].fd = remotesock;
    fds[1].events = POLLIN;

    for (;;) {
        result = poll(fds, 2, timeout_1s);
        if (result == -1) {
            err(1, "poll");
        }
        /* process local data */
        if (fds[0].revents & (POLLIN)) {
            int dres;
            ssize_t recvlen = recv(localsock, buf, sizeof(buf), 0);
            if (recvlen == -1) {
                err(1, "recv(localsock, ...)");
            }
            if (recvlen == 0) {
                errx(1, "recv(localsock, ...) EOF");
            }
            memset((void *)&decoder, 0, sizeof(decoder));
            dres = ccn_skeleton_decode(&decoder, buf, recvlen);
            if (decoder.state != 0 || dres != recvlen) {
                errx(1, "protocol error on local socket");
            }
            /* send the packet out on the remote side, removing encapsulation */
            result = sendto(remotesock, &buf[CCN_EMPTY_PDU_LENGTH - 1], dres - CCN_EMPTY_PDU_LENGTH, 0,
                            remote_addrinfo_result->ai_addr, remote_addrinfo_result->ai_addrlen);
            if (result == -1) {
                err(1, "sendto(remotesock, buf, %d)", dres - CCN_EMPTY_PDU_LENGTH);
            }
            fprintf(stderr, "local->remote %d bytes in, decoder accepted %d bytes, sent %d bytes to remote\n",
                    recvlen, dres, dres - CCN_EMPTY_PDU_LENGTH);
            if (options.debug) {
                for (int i = 0; i < dres; i++) {
                    fprintf(stderr, "%02x ", buf[i]);
                }
                fprintf(stderr, "\n");
            }
        }
        /* process remote data */
        if (fds[1].revents & (POLLIN)) {
            struct sockaddr from = {0};
            socklen_t fromlen = sizeof(from);
            memset(buf, 0, sizeof(buf));
            memmove(buf, CCN_EMPTY_PDU, CCN_EMPTY_PDU_LENGTH - 1);
            
            unsigned char *recvbuf;
            recvbuf = &buf[CCN_EMPTY_PDU_LENGTH - 1];
            ssize_t recvlen = recvfrom(remotesock, recvbuf,
                                       sizeof(buf) - CCN_EMPTY_PDU_LENGTH,
                                       0, &from, &fromlen);
            /* encapsulate, and send the packet out on the local side */
            recvbuf[recvlen] = '\0';
            result = send(localsock, buf, recvlen + CCN_EMPTY_PDU_LENGTH, 0);
            if (result == -1) {
                err(1, "sendto(localsock, buf, %d)", recvlen + CCN_EMPTY_PDU_LENGTH);
            }
            fprintf(stderr, "remote->local %d bytes in, sent %d bytes to local\n", recvlen, recvlen + CCN_EMPTY_PDU_LENGTH);
            if (options.debug) {
                for (int i = 0; i < recvlen + CCN_EMPTY_PDU_LENGTH; i++) {
                    fprintf(stderr, "%02x ", buf[i]);
                }
                fprintf(stderr, "\n");
            }
        }
    }
    /* if we were to exit the loop gracefully we would... */
    /* ccn_destroy(&ccn); */
}
