/*
 * Utility to check the signature on ccnb-formatted ContentObjects
 */

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <openssl/evp.h>

#include <ccn/ccn.h>

static unsigned char rawbuf[8801];

int
main(int argc, char **argv)
{
    int ch;
    int res;
    int i;
    int fd;
    ssize_t size;
    const char *filename;
    struct ccn_parsed_ContentObject obj = {0};
    struct ccn_indexbuf *comps = ccn_indexbuf_create();
    int status = 0;
    
    const EVP_MD *md;
    
    OpenSSL_add_all_digests();
    
    md = EVP_get_digestbynid(NID_sha256);
    
    if (md == NULL) abort();
    
    while ((ch = getopt(argc, argv, "h")) != -1) {
        switch (ch) {
            default:
            case 'h':
                fprintf(stderr, "provide names of files containing ccnb format content\n");
                exit(1);
        }
    }
    argc -= optind;
    argv += optind;
    for (i = 0; argv[i] != NULL; i++) {
        filename = argv[i];
        fd = open(filename, O_RDONLY);
        if (fd == -1) {
            perror(filename);
            status = 1;
            continue;
        }
        fprintf(stderr, "Reading %s ... ", filename);
        size = read(fd, rawbuf, sizeof(rawbuf));
        if (size < 0) {
            perror("skipping");
            close(fd);
            status = 1;
            continue;
        }
        close(fd);
        if (size == sizeof(rawbuf)) {
            fprintf(stderr, "skipping: too big\n");
            status = 1;
            continue;
        }
        res = ccn_parse_ContentObject(rawbuf, size, &obj, comps);
        if (res < 0) {
            fprintf(stderr, "skipping: not a ContentObject\n");
            status = 1;
            continue;
        }
        fprintf(stderr, "to be continued\n");
    }
    exit(status);
}