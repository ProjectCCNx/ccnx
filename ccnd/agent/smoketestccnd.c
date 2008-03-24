/*
 * Simple program for smoke-test of ccnd
 * Author: Michael Plass
 */
#include <arpa/inet.h>
#include <err.h>
#include <errno.h>
#include <fcntl.h>
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

void printraw(char *p, int n) {
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
int main(int argc, char **argv) {
    int c;
    struct sockaddr_un addr = {0};
    int res;
    int sock;
    int binary = 0;
    char *filename = NULL;
    int rep = 1;
    while ((c = getopt(argc, argv, "hf:n:")) != -1) {
        switch (c) {
            default:
                fprintf(stderr, "Unknown option: -%c\n", optopt);
            case 'h':
                fprintf(stderr, "%s\n", "options: -f infilename -n repeat");
                exit(1);
            case 'f':
		filename = optarg;
		binary = 1;
		close(0);
		res = open(filename, O_RDONLY);
		if (res != 0)
			err(1, filename);
		break;
	    case 'n':
		rep = atoi(optarg);
		break;
        }
    }
    sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock == -1)
        err(1, "socket");
    strncpy(addr.sun_path, "/tmp/.ccnd.sock", sizeof(addr.sun_path));
    addr.sun_family = AF_UNIX;
    res = connect(sock, (struct sockaddr *)&addr, sizeof(addr));
    if (res == -1)
        err(1, "connect(..., %s)", addr.sun_path);
    for (;;) {
        ssize_t rawlen;
        rawlen = read(0, rawbuf, sizeof(rawbuf));
        if (rawlen <= 0) {
            if (filename == NULL || --rep <= 0)
                    break;
            close(0);
            res = open(filename, O_RDONLY);
            if (res != 0)
                    break;
        }
	send(sock, rawbuf, rawlen, 0);
	sleep(1);
	rawlen = recv(sock, rawbuf, sizeof(rawbuf), MSG_DONTWAIT);
        if (rawlen == -1 && errno == EAGAIN)
            continue;
        if (rawlen == -1)
            err(1, "recv");
        if (rawlen == 0)
            break;
        printf("recv of %lu bytes\n", (unsigned long)rawlen);
        printraw(rawbuf, rawlen);
        }
    exit(0);
}
