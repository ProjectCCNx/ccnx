/**
 * @file ccndsmoketest.c
 * Simple program for smoke-test of ccnd.
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
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <sys/stat.h>
#include <sys/un.h>

#if defined(NEED_GETADDRINFO_COMPAT)
#include "getaddrinfo.h"
#include "dummyin6.h"
#endif

#include <ccn/ccnd.h>
#include <ccn/ccn_private.h>

#define CRLF "\r\n"
#define HTTPVERSION "1.1"

char rawbuf[1024*1024];

static void
printraw(char *p, int n)
{
    int i, l;
    while (n > 0) {
        l = (n > 40 ? 40 : n);
        for (i = 0; i < l; i++)
            printf(" %c", (' ' <= p[i] && p[i] <= '~') ? p[i] : '.');
        printf("\n");
        for (i = 0; i < l; i++)
            printf("%02X", (unsigned char)p[i]);
        printf("\n");
        p += l;
        n -= l;
    }
}

static int
open_local(struct sockaddr_un *sa, const char *verb)
{
    int sock;
    int res;
    
    sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock == -1) {
        perror("socket");
        exit(1);
    }
    res = connect(sock, (struct sockaddr *)sa, sizeof(*sa));
    if (res == -1 && errno == ENOENT) {
        /* Don't wait for startup just to shut it down */
        if (verb != NULL && 0 == strcmp(verb, "kill"))
            exit(1);
        /* Retry after a delay in case ccnd was just starting up. */
        sleep(1);
        res = connect(sock, (struct sockaddr *)sa, sizeof(*sa));
    }
    if (res == -1) {
        perror((char *)sa->sun_path);
        exit(1);
    }
    return(sock);
}

static int
open_socket(const char *host, const char *portstr, int sock_type)
{
    int res;
    int sock = 0;
    char canonical_remote[NI_MAXHOST] = "";
    struct addrinfo *addrinfo = NULL;
    struct addrinfo *myai = NULL;
    struct addrinfo hints = {0};
    
    if (portstr == NULL || portstr[0] == 0)
        portstr = CCN_DEFAULT_UNICAST_PORT;
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = sock_type;
    hints.ai_flags = 0;
#ifdef AI_ADDRCONFIG
    hints.ai_flags |= AI_ADDRCONFIG;
#endif
#ifdef AI_NUMERICSERV
    hints.ai_flags |= AI_NUMERICSERV;
#endif
    res = getaddrinfo(host, portstr, &hints, &addrinfo);
    if (res != 0 || addrinfo == NULL) {
        fprintf(stderr, "getaddrinfo(\"%s\", \"%s\", ...): %s\n",
                host, portstr, gai_strerror(res));
        exit(1);
    }
    
    res = getnameinfo(addrinfo->ai_addr, addrinfo->ai_addrlen,
                      canonical_remote, sizeof(canonical_remote), NULL, 0, 0);
    
    sock = socket(addrinfo->ai_family, addrinfo->ai_socktype, 0);
    if (sock == -1) {
        perror("socket");
        exit(1);
    }
    hints.ai_family = addrinfo->ai_family;
    hints.ai_flags = AI_PASSIVE;
#ifdef AI_NUMERICSERV
    hints.ai_flags |= AI_NUMERICSERV;
#endif
    res = getaddrinfo(NULL, NULL, &hints, &myai);
    if (myai != NULL) {
        res = bind(sock, (struct sockaddr *)myai->ai_addr, myai->ai_addrlen);
        if (res == -1) {
            perror("bind");
            exit(1);
        }
    }
    res = connect(sock, (struct sockaddr *)addrinfo->ai_addr, addrinfo->ai_addrlen);
    if (res == -1) {
        perror(canonical_remote);
        exit(1);
    }
    freeaddrinfo(addrinfo);
    if (myai != NULL)
        freeaddrinfo(myai);
    return (sock);
}

static void
send_ccnb_file(int sock, FILE *msgs, const char *filename, int is_dgram)
{
    ssize_t rawlen;
    ssize_t sres;
    int fd = 0;
    int truncated = 0;
    char onemore[1] = {0};
    
    if (strcmp(filename, "-") != 0) {
        fd = open(filename, O_RDONLY);
        if (fd == -1) {
            perror(filename);
            exit(-1);
        }
    }
    for (;;) {
        rawlen = read(fd, rawbuf, sizeof(rawbuf));
        if (rawlen == -1) {
            perror(filename);
            exit(-1);
        }
        if (rawlen == 0 && !is_dgram)
            break;
        if (is_dgram && rawlen == sizeof(rawbuf))
            truncated = read(fd, onemore, 1);
        if (truncated == 1)
            fprintf(msgs, "TRUNCATED ");
        fprintf(msgs, "send %s (%lu bytes)\n", filename, (unsigned long)rawlen);
        sres = send(sock, rawbuf, rawlen, 0);
        if (sres == -1) {
            perror("send");
            exit(1);
        }
        if (is_dgram)
            break;
    }
    if (fd != 0)
        close(fd);
}

static int
is_ccnb_name(const char *s)
{
    size_t len = strlen(s);
    return (len > 5 && 0 == strcasecmp(s + len - 5, ".ccnb"));
}

void
write_to_stream(FILE *outstream, const void *rawbuf, size_t rawlen)
{
    size_t wlen;
    wlen = fwrite(rawbuf, 1, rawlen, outstream);
    if (wlen != rawlen)
        fprintf(stderr, "short write (%ld of %ld)\n",
                (long)wlen, (long)rawlen);
    if (fflush(outstream) != 0)
        perror("fflush");
}

int
main(int argc, char **argv)
{
    struct sockaddr_un addr = {0};
    int opt;
    struct pollfd fds[1];
    int res;
    ssize_t rawlen;
    ssize_t wlen;
    int sock;
    char *filename = NULL;
    const char *portstr;
    int msec = 1000;
    int argp;
    FILE *msgs = stdout;
    FILE *outstream = NULL;
    int udp = 0;
    int tcp = 0;
    int recvloop = 0;
    int do_pclose = 0;
    const char *host = "localhost";
    const char *cmd = NULL;

    while ((opt = getopt(argc, argv, "bht:T:u:")) != -1) {
        switch (opt) {
            case 'b':
                outstream = stdout;
                msgs = stderr;
                break;
            case 't':
                msec = atoi(optarg);
                break;
            case 'u':
                udp = 1;
                host = optarg;
                break;
            case 'T':
                tcp = 1;
                host = optarg;
                break;
            case 'h':
            default:
                fprintf(stderr, "Usage %s %s\n", argv[0],
                        " [-b(inaryout)] "
                        " [-u udphost] "
                        " [-T tcphost] "
                        " [-t millisconds] "
                        " ( send <filename>"
                        " | <sendfilename>.ccnb"
                        " | recv"
                        " | kill"
                        " | status [-x]"
                        " | timeo <millisconds>"
                        " ) ...");
                exit(1);
        }
    }
    argp = optind;
    portstr = getenv(CCN_LOCAL_PORT_ENVNAME);
    ccn_setup_sockaddr_un(portstr, &addr);
    if (udp)
        sock = open_socket(host, portstr, SOCK_DGRAM);
    else if (tcp)
        sock = open_socket(host, portstr, SOCK_STREAM);
    else
        sock = open_local(&addr, argv[optind]);
    fds[0].fd = sock;
    fds[0].events = POLLIN;
    fds[0].revents = 0;
    for (argp = optind; argv[argp] != NULL; argp++) {
        if (0 == strcmp(argv[argp], "send")) {
            filename = argv[argp + 1];
            if (filename == NULL)
                filename = "-";
            else
                argp++;
            send_ccnb_file(sock, msgs, filename, udp);
        }
        else if (is_ccnb_name(argv[argp])) {
            filename = argv[argp];
            send_ccnb_file(sock, msgs, filename, udp);
        }
        else if (recvloop || 0 == strcmp(argv[argp], "recv")) {
            res = poll(fds, 1, msec);
            if (res == -1) {
                perror("poll");
                exit(1);
            }
            if (res == 0) {
                fprintf(msgs, "recv timed out after %d ms\n", msec);
                recvloop = 0;
                continue;
            }
            rawlen = recv(sock, rawbuf, sizeof(rawbuf), 0);
            if (rawlen == -1) {
                perror("recv");
                exit(1);
            }
            if (rawlen == 0)
                break;
            if (recvloop)
                argp--;
            else
                fprintf(msgs, "recv of %lu bytes\n", (unsigned long)rawlen);
            if (outstream != NULL)
                write_to_stream(outstream, rawbuf, rawlen);
            else
                printraw(rawbuf, rawlen);
        }
        else if (0 == strcmp(argv[argp], "kill")) {
            poll(fds, 1, 1);
            res = unlink((char *)addr.sun_path);
            if (res == 0) {
                res = open_socket(host, portstr, SOCK_STREAM);
                if (res != -1) {
                    write(res, " ", 1);
                    close(res);
                }
                poll(fds, 1, 5000);
                rawlen = recv(sock, rawbuf, sizeof(rawbuf), 0);
                if (rawlen == 0)
                    exit(0);
                if (rawlen > 0)
                    exit(2);
            }
            fprintf(stderr, "%s kill (%s) ", argv[0], (char *)addr.sun_path);
            perror("failed");
            exit(1);
        }
        else if (0 == strcmp(argv[argp], "timeo")) {
            if (argv[argp + 1] != NULL)
                msec = atoi(argv[++argp]);
        }
        else if (udp == 0 && 0 == strcmp(argv[argp], "status")) {
            msgs = stderr;
            if ((argv[argp + 1] != NULL) && (0 == strcmp(argv[argp + 1], "-x"))) {
                argp++;
                cmd = ("tail -n +6");
                outstream = popen(cmd, "w");
                wlen = send(sock, "GET /?f=xml " HTTPVERSION CRLF , 14, 0);
            } else {
                cmd = ("sed -e 's=[<]style .*/style[>]==g' -e 's=[<][^>]*[>]==g'|"
                   "tail -n +7");
                outstream = popen(cmd, "w");
                wlen = send(sock, "GET / " HTTPVERSION CRLF , 8, 0);
            }
            if (wlen < 0) perror("send");
            recvloop = 1;
            do_pclose = 1;
            argp--;
        }
        else {
            fprintf(stderr, "%s: unknown verb %s, try -h switch for usage\n",
                    argv[0], argv[argp]);
            exit(1);
        }
    }
    if (do_pclose)
        pclose(outstream);
    exit(0);
}
