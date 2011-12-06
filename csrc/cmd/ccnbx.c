/**
 * @file ccnbx.c
 *
 * Utility to extract a field from ccn binary encoded data.
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2009-2011 Palo Alto Research Center, Inc.
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

#define CCNBX_OPT_UNADORNED 1
#define CCNBX_OPT_VERBOSE   2

static void
usage(const char *progname)
{
    fprintf(stderr,
            "usage: %s [-h] [-d] [-v] file selector\n"
            " Utility to extract a field from ccn binary encoded data.\n"
            "  selector is an element name\n"
            "  -h      print this message and exit\n"
            "  -d      data only - no element tags\n"
            "  -v      verbose\n"
            " use - for file to specify stdin\n"
            " result is on stdout\n",
            progname);
    exit(1);
}


static int
dtag_lookup(const char *key)
{
    const struct ccn_dict_entry *dict = ccn_dtag_dict.dict;
    int n = ccn_dtag_dict.count;
    int i;
    for (i = 0; i < n; i++)
        if (0 == strcmp(key, dict[i].name))
            return (dict[i].index);
    return (-1);
}

static int
ccnbx(const char *path, const char *selector, int options) {
    struct ccn_skeleton_decoder skel_decoder = {0};
    struct ccn_skeleton_decoder *d = &skel_decoder;
    struct ccn_charbuf *c = NULL;
    int fd = 0;
    int status = 1;
    int dtag;
    ssize_t res;
    ssize_t s;
    size_t offset = 0;
    size_t start = 0;
    size_t end = ~0U;
    int verbose = (options & CCNBX_OPT_VERBOSE) != 0;
        
    if (0 != strcmp(path, "-")) {
        fd = open(path, O_RDONLY);
        if (-1 == fd) {
            perror(path);
            return(1);
        }
    }
    dtag = dtag_lookup(selector);
    if (dtag == -1) {
        fprintf(stderr, "%s is not a DTAG\n", selector);
        goto Finish;
    }
    c = ccn_charbuf_create();
    d->state |= CCN_DSTATE_PAUSE;
    do {
        ccn_charbuf_reserve(c, 512);
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
            offset += s;
            if (verbose) fprintf(stderr, "%d, ", (int)s);
            if (d->state < 0) {
                fprintf(stderr, "error state %d after %d chars from %s\n",
                        (int)d->state, (int)(d->index), path);
                goto Finish;
            }
            if (s == 0 || CCN_FINAL_DSTATE(d->state))
                break;
            if (CCN_GET_TT_FROM_DSTATE(d->state) == CCN_DTAG &&
                d->numval == dtag) {
                if (verbose)
                    fprintf(stderr, "(%s starts at %d, level is %d) ",
                            selector,
                            (int)d->token_index,
                            (int)d->nest);
                start = d->token_index;
                d->nest = 1;
                if ((options & CCNBX_OPT_UNADORNED) == 0)
                    d->state &= ~CCN_DSTATE_PAUSE; /* full speed to the end */                   
                else
                    start = end = d->index; /* in case of no data */
                status = 0;
            }
            else if (status == 0 && d->nest == 1 &&
                     (CCN_GET_TT_FROM_DSTATE(d->state) == CCN_UDATA ||
                      CCN_GET_TT_FROM_DSTATE(d->state) == CCN_BLOB)) {
                /** @bug This would be confused by attributes */
                start = d->index;
                end = d->index + d->numval;
                d->state &= ~CCN_DSTATE_PAUSE;
            }
        } 
    } while (!CCN_FINAL_DSTATE(d->state));
    if (verbose) fprintf(stderr, "complete element after %lu chars from %s\n",
                         (unsigned long)offset, path);
    if (offset < end)
        end = offset;
    if (status == 0)
        res = write(1, c->buf + start, end - start);
Finish:
    ccn_charbuf_destroy(&c);
    close(fd);
    return(status);
}

int
main(int argc, char **argv)
{
    int opt;
    int status = 0;
    int options = 0;

    while ((opt = getopt(argc, argv, "dhv")) != -1) {
        switch (opt) {
        case 'd':
            options |= CCNBX_OPT_UNADORNED;
            break;
        case 'v':
            options |= CCNBX_OPT_VERBOSE;
            break;
        case 'h':
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
    status = ccnbx(argv[optind], argv[optind + 1], options);
    return(status);
}

