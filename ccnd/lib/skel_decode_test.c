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

#define CCN_NO_SCHEMA INT_MIN
#define CCN_UNKNOWN_SCHEMA (INT_MIN+1)

static int
process_test(unsigned char *data, size_t n) {
    struct ccn_skeleton_decoder skel_decoder = {0};
    struct ccn_skeleton_decoder *d = &skel_decoder;
    int res = 0;
    size_t s;
retry:
    s = ccn_skeleton_decode(d, data, n);
    if (d->state != 0 || d->nest != 0 || d->tagstate != 0) {
        res = 1;
        fprintf(stderr, "error state %d after %d of %d chars\n",
            (int)d->state, (int)s, (int)n);
    }
    else if (s == 0) {
        fprintf(stderr, "nothing to do\n");
    }
    else if (s < n) {
        fprintf(stderr, "resuming parse at index %d\n", (int)d->index);
        data += s;
        n -= s;
        goto retry;
    }
    return(res);
}

static int
process_fd(int fd)
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
    res |= process_test(c->buf, c->length);
    ccn_charbuf_destroy(&c);
    return(res);
}


static int
process_file(char *path) {
    int fd = 0;
    int res = 0;
    if (0 != strcmp(path, "-")) {
        fd = open(path, O_RDONLY);
        if (-1 == fd) {
            perror(path);
            return(1);
        }
    }
    
    res = process_fd(fd);
    
    if (fd > 0)
        close(fd);
    return(res);
}

int
main(int argc, char **argv) {
    int i;
    int res = 0;
    for (i = 1; argv[i] != 0; i++) {
        fprintf(stderr, "<!-- Processing %s -->\n", argv[i]);
        res |= process_file(argv[i]);
    }
    return(res);
}

