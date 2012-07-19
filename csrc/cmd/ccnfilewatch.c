/**
 * @file ccnfilewatch.c
 * 
 * Utility program to record a file's size
 *
 */

/*
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/poll.h>
#include <sys/stat.h>
#include <sys/time.h>

static int
usage(const char *progname) {
    fprintf(stderr, "%s" " wrong args\n", progname);
    exit(1);
}

static int
statchanged(struct stat *prev, struct stat *curr)
{
    if (curr->st_size != prev->st_size)
        return(1);
    if (curr->st_mtime != prev->st_mtime)
        return(1);
    return(0);
}

static void
printstat(FILE *out, struct stat *s)
{
    struct timeval now;
    time_t sec;
    unsigned usec;
    
    gettimeofday(&now, NULL);
    sec = s->st_mtime;
    usec = 0;
    if (now.tv_sec <= s->st_mtime + 1) {
        /* If more file systems kept hi-res mtime, we could use that here. */
        sec = now.tv_sec;
        usec = now.tv_usec;
    }
    fprintf(out, "%lu.%06u d=%u,i=%lu %ju\n",
            (unsigned long)sec,
            usec,
            (unsigned)s->st_dev,
            (unsigned long)s->st_ino,
            (uintmax_t)(s->st_size)
            );
}

/**
 * Monitor the size of the named file, recording its growth
 *
 * This is used for repository tests.  We want to measure the point at which
 * the repository file is a stable size.
 *
 * @param path is the name of the file to monitor
 * @param out is where to write the logged data
 * @param minsize is the minimum expected final size
 * @param maxsize is threshold beyond which we stop monitoring
 * @param maxsec is a limit, in seconds, on how long to monitor
 * @param msecstable is a time in milliseconds to consider file size stable
 * @param msecpoll is the delay, in milliseconds, between polls.
 *
 * @returns 0 if stability was obtained within given parameters,
 *         -1 if system call failed or invaild arguments (see errno),
 *         -2 if maxsize exceeded,
 *         -3 if maxsec exceeded,
 *         -4 if the file is unlinked.
 */
#define FW_NBUF 4
int
ccn_filewatch(const char *path,
              FILE *out,
              off_t minsize,
              off_t maxsize,
              time_t maxsec,
              int msecstable,
              int msecpoll)
{
    int fd = -1;
    int res = 0;
    unsigned i = 0;
    const unsigned int nbuf = FW_NBUF;
    struct stat stats[FW_NBUF];
    struct stat *curr = NULL;
    struct stat *prev = NULL;
    struct stat statname = {0};
    struct pollfd dummy[1];
    time_t elapsed = 0;
    unsigned elapsedms = 0;
    unsigned stablems = 0;
    
    memset(stats, 0, sizeof(stats));
    memset(dummy, 0, sizeof(dummy));
    errno = EINVAL;
    if (msecpoll < 1)
        return(-1);
    if (msecstable < 1)
        return(-1);
    fd = open(path, O_RDONLY, 0);
    if (fd < 0)
        return(-1);
    for (i = 0;;) {
        curr = &(stats[i % nbuf]);
        res = fstat(fd, curr);
        if (res == -1)
            break;
        if (prev == NULL || statchanged(prev, curr)) {
            printstat(out, curr);
            prev = curr;
            stablems = 0;
            if (maxsize != 0 && curr->st_size > maxsize) {
                res = -2;
                break;
            }
            i++;
        }
        else {
            stablems += msecpoll;
            if (stablems >= msecstable && curr->st_size >= minsize) {
                res = 0;
                break;
            }
        }
        res = poll(dummy, 0, msecpoll);
        if (res != 0) abort();
        elapsedms += msecpoll;
        if (elapsed + elapsedms / 1000 > maxsec) {
            res = -3;
            break;
        }
        if (elapsedms >= 3000) {
            elapsed += elapsedms / 1000;
            elapsedms %= 1000;
            res = stat(path, &statname);
            if (res < 0 ||
                statname.st_dev != curr->st_dev ||
                statname.st_ino != curr->st_ino) {
                res = -4;
                break;
            }
            fflush(out);
        }
    }
    close(fd);
    fflush(out);
    return(res);
}

int
main(int argc, char** argv)
{
    int opt = 0;
    int res = 0;
    const char *path = NULL;
    FILE *out = stdout;
    off_t minsize = 1;
    off_t maxsize = 0; /* unlimited */
    time_t maxsec = 600;
    int msecstable = 5000;
    int msecpoll = 100;

    while ((opt = getopt(argc, argv, "h")) != -1) {
        switch (opt) {
            case 'a':
                break;
            case 'h':
            default:
                usage(argv[0]);
        }
    }
    if (argv[optind] == NULL || argv[optind + 1] != NULL)
        usage(argv[0]);
    path = argv[optind];
    setvbuf(out, NULL, _IOLBF, 0);
    res = ccn_filewatch(path,
                        out,
                        minsize,
                        maxsize,
                        maxsec,
                        msecstable,
                        msecpoll);
    if (res == -1)
        perror(path);
    exit(-res);
}
