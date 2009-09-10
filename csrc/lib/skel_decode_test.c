/**
 * @file skel_decode_test.c
 * A simple test program for exercising ccn_skeleton_decoder.
 * 
 * A CCNx program.
 *
 * Copyright (C) 2009 Palo Alto Research Center, Inc.
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
#include <limits.h>
#include <stddef.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <ccn/charbuf.h>
#include <ccn/coding.h>

static const char *tt_name[8] = {
    [CCN_EXT] = "CCN_EXT",
    [CCN_TAG] = "CCN_TAG",
    [CCN_DTAG] = "CCN_DTAG",
    [CCN_ATTR] = "CCN_ATTR",
    [CCN_DATTR] = "CCN_DATTR",
    [CCN_BLOB] = "CCN_BLOB",
    [CCN_UDATA] = "CCN_UDATA",
    [CCN_NO_TOKEN] = "CCN_CLOSE"
};

#define SHOW_HEX_STATE 1

static int
process_test(unsigned char *data, size_t n, int flags)
{
    struct ccn_skeleton_decoder skel_decoder = {0};
    struct ccn_skeleton_decoder *d = &skel_decoder;
    int res = 0;
    size_t s;
    d->state |= flags & CCN_DSTATE_PAUSE;
retry:
    s = ccn_skeleton_decode(d, data, n);
    if (flags & SHOW_HEX_STATE)
        fprintf(stderr, "state = 0x%x\n", d->state);
    if (d->state < 0) {
        res = 1;
        fprintf(stderr, "error state %d after %d of %d chars\n",
            (int)d->state, (int)s, (int)n);
    }
    else if (s == 0) {
        fprintf(stderr, "nothing to do\n");
    }
    else {
        if (d->state & CCN_DSTATE_PAUSE)
            fprintf(stderr, "Token type %s(%d) at index %d; el %d nest %d; ",
                tt_name[CCN_GET_TT_FROM_DSTATE(d->state)],
                (int)d->numval,
                (int)d->token_index,
                (int)d->element_index,
                (int)d->nest);
        if (s < n) {
            fprintf(stderr, "resuming at index %d\n", (int)d->index);
            data += s;
            n -= s;
            goto retry;
        }
        fprintf(stderr, "\n");
    }
    if (!CCN_FINAL_DSTATE(d->state)) {
        res = 1;
        fprintf(stderr, "incomplete state %d after %d of %d chars\n",
            (int)d->state, (int)s, (int)n);
    }
    return(res);
}

static int
process_fd(int fd, int flags)
{
    struct ccn_charbuf *c = ccn_charbuf_create();
    ssize_t len;
    int res = 0;
    for (;;) {
        unsigned char *p = ccn_charbuf_reserve(c, 80);
        if (p == NULL) {
            perror("ccn_charbuf_reserve");
            res = 1;
            break;
        }
        len = read(fd, p, c->limit - c->length);
        if (len <= 0) {
            if (len == -1) {
                perror("read");
                res = 1;
            }
            break;
        }
        c->length += len;
    }
    fprintf(stderr, " <!-- input is %6lu bytes -->\n", (unsigned long)c->length);
    res |= process_test(c->buf, c->length, flags);
    ccn_charbuf_destroy(&c);
    return(res);
}


static int
process_file(char *path, int flags)
{
    int fd = 0;
    int res = 0;
    if (0 != strcmp(path, "-")) {
        fd = open(path, O_RDONLY);
        if (-1 == fd) {
            perror(path);
            return(1);
        }
    }
    res = process_fd(fd, flags);
    if (fd > 0)
        close(fd);
    return(res);
}

int
main(int argc, char **argv)
{
    int i;
    int res = 0;
    int flags = 0;
    for (i = 1; argv[i] != 0; i++) {
        if (0 == strcmp(argv[i], "-d")) {
            flags |= CCN_DSTATE_PAUSE;
            continue;
        }
        if (0 == strcmp(argv[i], "-D")) {
            flags |= CCN_DSTATE_PAUSE | SHOW_HEX_STATE;
            continue;
        }
        fprintf(stderr, "<!-- Processing %s -->\n", argv[i]);
        res |= process_file(argv[i], flags);
    }
    return(res);
}

