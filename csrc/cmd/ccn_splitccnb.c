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
write_segment(unsigned char *data, size_t s, struct fstate *perfilestate)
{
    int ofd;
    char ofile[256];
    mode_t mode = S_IRUSR | S_IWUSR | S_IRGRP | S_IROTH;
    int res;

    sprintf(ofile, "%s-%05d.ccnb", perfilestate->prefix, perfilestate->segnum);
    ofd = open(ofile, O_CREAT | O_WRONLY | O_TRUNC, mode);
    if (ofd == -1) {
        perror("open");
        return (1);
    }
    res = write(ofd, data, s);
    close(ofd);
    if (res != s) {
        perror("write");
        return (1);
    }
    perfilestate->segnum++;
    return (0);
}

static int
process_test(unsigned char *data, size_t n, struct fstate *perfilestate)
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
            if (write_segment(data, s, perfilestate) != 0) {
                return (1);
            }
            /* fprintf(stderr, "resuming at index %d\n", (int)d->index); */
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
    } else  if (write_segment(data, s, perfilestate) != 0) {
        res = 1;
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

