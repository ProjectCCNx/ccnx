/**
 * @file ccnbx.c
 *
 * Utility to extract fields from ccn binary encoded data.
 */

#include <fcntl.h>
#include <stddef.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/ccn.h>

#define CCNBX_OPT_SHOWTAGS 1

static void
usage(const char *progname)
{
    fprintf(stderr,
            "usage: %s [-h] Selector file\n"
            " Utility to extract a field from ccn binary encoded data.\n"
            " Selector is a series of element names separated by slashes\n"
            "          example: /ContentObject/SignedInfo/Timestamp\n"
            "  -e      include element tag in output\n"
            " use - for file to specify stdin\n"
            " result is on stdout\n",
            progname);
    exit(1);
}

int
ccnbx(const char *selector, const char *path, int opt) {
    struct ccn_skeleton_decoder skel_decoder = {0};
    struct ccn_skeleton_decoder *d = &skel_decoder;
    struct ccn_charbuf *c = NULL;
    int fd = 0;
    int status = 1;
    ssize_t res;
    size_t s;
    
    if (0 != strcmp(path, "-")) {
        fd = open(path, O_RDONLY);
        if (-1 == fd) {
            perror(path);
            return(1);
        }
    }
    c = ccn_charbuf_create();
    d->state |= CCN_DSTATE_PAUSE;
    do {
        ccn_charbuf_reserve(c, 64);
        res = read(fd, c->buf + c->length, c->limit - c->length);
        if (res < 0) {
            perror(path);
            goto Finish;
        }
        if (res == 0) {
            fprintf(stderr, "premature end of file on %s\n", path);
            goto Finish;
        }
        c->length += res;
        while (d->index < c->length) {
            s = ccn_skeleton_decode(d, c->buf + d->index, c->length - d->index);
            fprintf(stderr, "%d, ", (int)s);
            if (d->state < 0) {
                fprintf(stderr, "error state %d after %d chars from %s\n",
                        (int)d->state, (int)(d->index), path);
                goto Finish;
            }
            if (s == 0 || CCN_FINAL_DSTATE(d->state))
                break;
            if (CCN_GET_TT_FROM_DSTATE(d->state) == CCN_DTAG &&
                d->numval == CCN_DTAG_Component)
                fprintf(stderr, "(Component starts at %d) ", (int)d->token_index);
        } 
    } while (!CCN_FINAL_DSTATE(d->state));
    fprintf(stderr, "complete element after %d chars from %s\n",
            (int)(d->index), path);
    status = 0;
Finish:
    ccn_charbuf_destroy(&c);
    close(fd);
    return(status);
}

int
main(int argc, char **argv)
{
    int c;
    int i;
    int status = 0;
    int opt = 0;

    while ((c = getopt(argc, argv, "he")) != -1) {
        switch (c) {
        case 'h':
            usage(argv[0]);
            break;
        case 'e':
            opt |= CCNBX_OPT_SHOWTAGS;
            break;
        default:
            usage(argv[0]);
            break;
        }
    }
    if (argv[optind] == NULL || argv[optind + 1] == NULL) {
        fprintf(stderr, "Too few arguments\n");
        usage(argv[0]);
    }
    if (argv[optind + 2] != NULL) {
        fprintf(stderr, "Too many arguments\n");
        usage(argv[0]);
    }
    status = ccnbx(argv[optind], argv[optind + 1], opt);
    return(status);
}

