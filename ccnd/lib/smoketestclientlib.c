/*
 * Simple program for smoke-test of ccn client lib
 * Author: Michael Plass
 */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <ccn/ccn.h>
#include <unistd.h>

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
    int res;
    int binary = 0;
    char *filename = NULL;
    int rep = 1;
    struct ccn *ccn = NULL;
    while ((c = getopt(argc, argv, "hf:n:")) != -1) {
        switch (c) {
            default:
            case 'h':
                fprintf(stderr, "%s\n", "options: -f infilename -n repeat");
                exit(1);
            case 'f':
		filename = optarg;
		binary = 1;
		close(0);
		res = open(filename, O_RDONLY);
		if (res != 0) {
			perror(filename);
                        exit(1);
		}
                break;
	    case 'n':
		rep = atoi(optarg);
		break;
        }
    }
    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("ccn_connect");
        exit(1);
    }
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
	res = ccn_put(ccn, rawbuf, rawlen);
        if (res == -1) {
            fprintf(stderr, "got error on ccn_put\n");
            exit(1);
        }
        if (res == 1)
            sleep(1);
	// blatant API violation follows!
        rawlen = read(ccn_get_connection_fd(ccn), rawbuf, sizeof(rawbuf));
        if (rawlen == -1 && errno == EAGAIN)
            continue;
        if (rawlen == -1)
            perror("recv");
        if (rawlen == 0)
            break;
        printf("recv of %lu bytes\n", (unsigned long)rawlen);
        printraw(rawbuf, rawlen);
        }
    ccn_destroy(&ccn);
    exit(0);
}
