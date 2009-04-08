/*
 * Copyright 2008, 2009 Palo Alto Research Center, Inc. All rights reserved.
 * Simple program for smoke-test of ccnd
 * Author: Michael Plass
 */
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#include <ccn/ccnd.h>

void printraw(char *p, int n)
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

char rawbuf[1024*1024];
int main(int argc, char **argv)
{
    int c;
    struct sockaddr_un addr = {0};
    struct pollfd fds[1];
    int res;
    ssize_t sres;
    ssize_t rawlen;
    int sock;
    char *filename = NULL;
    const char *portstr;
    int msec = 1000;
    int argp;
    int fd;
    while ((c = getopt(argc, argv, "ht:")) != -1) {
        switch (c) {
            default:
            case 'h':
                fprintf(stderr, "Usage %s %s\n", argv[0],
                            " [-t millisconds] "
                            " ( send <filename>"
                            " | recv"
                            " | kill"
                            " | timeo <millisconds>"
                            " ) ...");
                exit(1);
	    case 't':
		msec = atoi(optarg);
		break;
        }
    }
    argp = optind;
    sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock == -1) {
        perror("socket");
        exit(1);
    }
    portstr = getenv(CCN_LOCAL_PORT_ENVNAME);
    if (portstr != NULL && atoi(portstr) > 0 && atoi(portstr) != 4485)
        snprintf(addr.sun_path, sizeof(addr.sun_path),
            CCN_DEFAULT_LOCAL_SOCKNAME ".%s", portstr);
    else
        snprintf(addr.sun_path, sizeof(addr.sun_path),
            CCN_DEFAULT_LOCAL_SOCKNAME);
    addr.sun_family = AF_UNIX;
    res = connect(sock, (struct sockaddr *)&addr, sizeof(addr));
    if (res == -1) {
        perror((char *)addr.sun_path);
        exit(1);
    }
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
            if (strcmp(filename, "-") == 0)
                fd = 0;
            else {
                fd = open(filename, O_RDONLY);
                if (fd == -1) {
                    perror(filename);
                    exit(-1);
                }
            }
            rawlen = read(fd, rawbuf, sizeof(rawbuf));
            if (rawlen == -1) {
                perror(filename);
                exit(-1);
            }
            if (fd != 0)
                close(fd);
            if (rawlen == 0)
                continue;
            printf("send %s (%lu bytes)\n", filename, (unsigned long)rawlen);
            sres = send(sock, rawbuf, rawlen, 0);
            if (sres == -1) {
                perror("send");
                exit(1);
            }
        }
        else if (0 == strcmp(argv[argp], "recv")) {
            res = poll(fds, 1, msec);
            if (res == -1) {
                perror("poll");
                exit(1);
            }
            if (res == 0) {
                printf("recv timed out after %d ms\n", msec);
                continue;
            }
            rawlen = recv(sock, rawbuf, sizeof(rawbuf), 0);
            if (rawlen == -1) {
                perror("recv");
                exit(1);
            }
            if (rawlen == 0)
                break;
            printf("recv of %lu bytes\n", (unsigned long)rawlen);
            printraw(rawbuf, rawlen);
        }
        else if (0 == strcmp(argv[argp], "kill")) {
            unlink((char *)addr.sun_path);
            break;
        }
        else if (0 == strcmp(argv[argp], "timeo")) {
            if (argv[argp + 1] != NULL)
                msec = atoi(argv[++argp]);
        }
        else {
            fprintf(stderr, "%s: unknown verb %s, try -h switch for usage\n",
                    argv[0], argv[argp]);
            exit(1);
        }
    }
    exit(0);
}
