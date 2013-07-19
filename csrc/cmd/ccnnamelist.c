/**
 * @file cmd/ccnnamelist.c
 *
 * Utility to print on standard output a list of names from ccnb-encoded
 * data items in one or more files.
 *
 * A CCNx command-line utility.
 */
/*
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

struct options {
    int no_output;
};

/* returns
 *  res >= 0    res characters remaining to be processed from data
 *  decoder state will be set appropriately   
 */
static size_t
process_data(struct ccn_skeleton_decoder *d, unsigned char *data, size_t n, struct ccn_charbuf *c, const struct options *o)
{
    size_t s;
retry:
    s = ccn_skeleton_decode(d, data, n);
    if (d->state < 0)
        return (0);
    if (CCN_FINAL_DSTATE(d->state)) {
        if (!o->no_output) {
            c->length = 0;
            ccn_uri_append(c, data, s, 1);
            printf("%s\n", ccn_charbuf_as_string(c));                
        }
        data += s;
        n -= s;
        if (n > 0) goto retry;
    }
    return(n);
}

static int
process_fd(int fd, struct ccn_charbuf *c, const struct options *o)
{
    struct ccn_skeleton_decoder skel_decoder = {0};
    struct ccn_skeleton_decoder *d = &skel_decoder;
    unsigned char *bufp;
    unsigned char buf[1024 * 1024];
    ssize_t len;
    struct stat s;
    size_t res = 0;
    
    if (0 != fstat(fd, &s)) {
        perror("fstat");
        return(1);
    }
    
    if (S_ISREG(s.st_mode)) {
        res = s.st_size;
        bufp = (unsigned char *)mmap((void *)NULL, res,
                                     PROT_READ, MAP_PRIVATE, fd, 0);
        if (bufp != (void *)-1) {
            res = process_data(d, bufp, res, c, o);
            if (!CCN_FINAL_DSTATE(d->state)) {
                fprintf(stderr, "%s state %d after %lu bytes\n",
                        (d->state < 0) ? "error" : "incomplete",
                        (int)d->state,
                        (unsigned long)d->index);
                return(1);
            }
            return(0);
        }
    }
    if (o->no_output)
        fprintf(stderr, "Unable to mmap input, using read instead.\n");
    /* either not a regular file amenable to mapping, or the map failed */
    bufp = &buf[0];
    res = 0;
    while ((len = read(fd, bufp + res, sizeof(buf) - res)) > 0) {
        len += res;
        res = process_data(d, bufp, len, c, o);
        if (d->state < 0) {
            fprintf(stderr, "error state %d\n", (int)d->state);
            return(1);
        }
        /* move any remaining data back to the start, refresh the buffer,
         * reset the decoder state so we can reparse
         */
        if (res != 0) 
            memmove(bufp, bufp + (len - res), res);
        memset(d, 0, sizeof(*d));
    }
    if (!CCN_FINAL_DSTATE(d->state)) {
        fprintf(stderr, "%s state %d\n",
                (d->state < 0) ? "error" : "incomplete", d->state);
        return(1);
    }  
    return(0);
}


static int
process_file(char *path, struct ccn_charbuf *c, const struct options *o)
{
    int fd = -1;
    int res = 0;
    if (strcmp(path, "-") == 0) {
        fd = STDIN_FILENO;
    } else {
        fd = open(path, O_RDONLY);
        if (-1 == fd) {
            perror(path);
            return(1);
        }
        
    }
    res = process_fd(fd, c, o);
    close(fd);
    return(res);
}

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-hn] [file1 ... fileN]\n"
            "   Produces a list of names from the ccnb encoded"
            " objects in the given file(s), or from stdin if no files or \"-\"\n"
            "  -n parse the objects but generate no output.\n",
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
    struct options o = { 0 };
    
    while ((opt = getopt(argc, argv, "hn")) != -1) {
        switch (opt) {
            case 'n':
                o.no_output = 1;
                break;
            case 'h':
            default:
                usage(argv[0]);
        }
    }
    
    if (argv[optind] == NULL)
        return (process_fd(STDIN_FILENO, c, &o));
    
    for (i = optind; argv[i] != 0; i++) {
        res |= process_file(argv[i], c, &o);
    }
    return(res);
}

