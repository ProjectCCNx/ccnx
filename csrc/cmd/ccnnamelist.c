/**
 * @file ccn_splitccnb.c
 * Utility to break up a file filled with ccnb-encoded data items into
 * one data item per file.
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <limits.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <ccn/coding.h>
#include <ccn/uri.h>

static int
process_test(unsigned char *data, size_t n, struct ccn_charbuf *c)
{
    struct ccn_skeleton_decoder skel_decoder = {0};
    struct ccn_skeleton_decoder *d = &skel_decoder;
    int res = 0;
    size_t s;
    
retry:
    s = ccn_skeleton_decode(d, data, n);
    if (d->state < 0) {
        res = 1;
        fprintf(stderr, "error state %d after %d of %d chars\n",
                (int)d->state, (int)s, (int)n);
    }
    else if (s == 0) {
        fprintf(stderr, "nothing to do\n");
    }
    else {
        if (s < n) {
            c->length = 0;
            ccn_uri_append(c, data, s, 1);
            printf("%s\n", ccn_charbuf_as_string(c));
            data += s;
            n -= s;
            goto retry;
        }
    }
    if (!CCN_FINAL_DSTATE(d->state)) {
        res = 1;
        fprintf(stderr, "incomplete state %d after %d of %d chars\n",
                (int)d->state, (int)s, (int)n);
    } else {
        c->length = 0;
        ccn_uri_append(c, data, s, 1);
        printf("%s\n", ccn_charbuf_as_string(c));               
    }
    return(res);
}

static int
process_fd(int fd, struct ccn_charbuf *c)
{
    unsigned char *buf;
    ssize_t len;
    struct stat s;
    int res = 0;
    
    res = fstat(fd, &s);
    len = s.st_size;
    buf = (unsigned char *)mmap((void *)NULL, len, PROT_READ, MAP_PRIVATE, fd, 0);
    if (buf == (void *)-1) return (1);
    res |= process_test(buf, len, c);
    munmap((void *)buf, len);
    return(res);
}


static int
process_file(char *path, struct ccn_charbuf *c)
{
    int fd;
    int res = 0;
    
    fd = open(path, O_RDONLY);
    if (-1 == fd) {
        perror(path);
        return(1);
    }
    
    res = process_fd(fd, c);
    if (fd > 0)
        close(fd);
    return(res);
}

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-h] file1 ...fileN\n"
            "   Produces a list of names from the ccnb encoded"
            " objects in the given file(s) which must be accessible to mmap\n",
            progname);
    exit(1);
}

int
main(int argc, char *argv[])
{
    int i;
    int res = 0;
    struct ccn_charbuf *c = ccn_charbuf_create();
    int opt;
    
    while ((opt = getopt(argc, argv, "h")) != -1) {
        switch (opt) {
            case 'h':
            default:
                usage(argv[0]);
        }
    }
    
    if (argv[optind] == NULL)
        usage(argv[0]);
    
    for (i = optind; argv[i] != 0; i++) {
        res |= process_file(argv[i], c);
    }
    return(res);
}

