/**
 * @file ccnhexdumpdata.c
 * Parse a ccnb-encoded ContentObject in a file and dump the content as hex data to stdout.
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
#include <ccn/ccn.h>

struct fstate {
    char *prefix;
    int segnum;
};

static char *
segment_prefix(char *path)
{
    char *s, *d, *r;

    s = strrchr(path, '/');
    if (s == NULL) s = path;
    d = strrchr(s, '.');
    if (d == NULL) d = s + strlen(s);
    r = calloc(1, 1 + d - path);
    memcpy(r, path, d - path);
    return (r);
}

static int
process_test(unsigned char *data, size_t n, struct fstate *perfilestate)
{
    struct ccn_skeleton_decoder skel_decoder = {0};
    struct ccn_skeleton_decoder *d = &skel_decoder;
    struct ccn_parsed_ContentObject content;
    struct ccn_indexbuf *comps = ccn_indexbuf_create();
    const unsigned char * content_value;
    size_t content_length;
    int res = 0;
    size_t s;
    unsigned int i;

 retry:
    s = ccn_skeleton_decode(d, data, n);
    if (d->state < 0) {
        res = 1;
        fprintf(stderr, "error state %d after %d of %d chars\n",
                (int)d->state, (int)s, (int)n);
    }
    else if (s == 0) {
        fprintf(stderr, "nothing to do\n");
    } else {
        if (s < n) {
            content_value = NULL;
            content_length = 0;
            if (ccn_parse_ContentObject(data, s, &content, comps) != 0) {
                fprintf(stderr, "unable to parse content object\n");
                res = 1;
            }
            else if (ccn_content_get_value(data, s, &content, &content_value, &content_length) != 0) {
                fprintf(stderr, "unable to retrieve content value\n");
                res = 1;
            }
            for (i = 0; i < content_length; i++) {
                if (i % 16 == 0) printf("\n%08x ", i);
                printf(" %02x", content_value[i]);
            }
            printf("\n");
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
        content_value = NULL;
        content_length = 0;
        if (ccn_parse_ContentObject(data, s, &content, comps) != 0) {
            fprintf(stderr, "unable to parse content object\n");
            res = 1;
        }
        else if (ccn_content_get_value(data, s, &content, &content_value, &content_length) != 0) {
            fprintf(stderr, "unable to retrieve content value\n");
            res = 1;
        }
        for (i = 0; i < content_length; i++) {
            if (i % 16 == 0) printf("\n%08x ", i);
            printf(" %02x", content_value[i]);
        }
        printf("\n\n");
    }
    return(res);
}

static int
process_fd(int fd, struct fstate *perfilestate)
{
    unsigned char *buf;
    ssize_t len;
    struct stat s;
    int res = 0;

    res = fstat(fd, &s);
    len = s.st_size;
    buf = (unsigned char *)mmap((void *)NULL, len, PROT_READ, MAP_PRIVATE, fd, 0);
    if (buf == (void *)-1) return (1);
    fprintf(stderr, " <!-- input is %6lu bytes -->\n", (unsigned long)len);
    res |= process_test(buf, len, perfilestate);
    munmap((void *)buf, len);
    return(res);
}


static int
process_file(char *path, struct fstate *perfilestate)
{
    int fd;
    int res = 0;

    fd = open(path, O_RDONLY);
    if (-1 == fd) {
        perror(path);
        return(1);
    }

    perfilestate->segnum = 0;
    if (perfilestate->prefix != NULL) free(perfilestate->prefix);
    perfilestate->prefix = segment_prefix(path);

    res = process_fd(fd, perfilestate);
    if (fd > 0)
        close(fd);
    return(res);
}

int
main(int argc, char *argv[])
{
    int i;
    int res = 0;
    struct fstate perfilestate = {0};

    for (i = 1; argv[i] != 0; i++) {
        fprintf(stderr, "<!-- Processing %s -->\n", argv[i]);
        res |= process_file(argv[i], &perfilestate);
    }
    return(res);
}
