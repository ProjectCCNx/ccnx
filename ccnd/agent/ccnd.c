/*
 *  ccnd.c
 *  
 *  Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 *
 */

#include <sys/types.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <ccn/ccnd.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/hashtb.h>

struct ccnd;
struct dbl_links;
struct face;

/*
 * We pass this handle almost everywhere.
 */
struct ccnd {
    int local_listener_fd;
    struct hashtb *faces;
    nfds_t nfds;
    struct pollfd *fds;
};

struct dbl_links {
    struct dbl_links *prev;
    struct dbl_links *next;
};

/*
 * One of our active interfaces
 */
struct face {
    int fd;
    struct ccn_charbuf *inbuf;
    struct ccn_skeleton_decoder decoder;
};

static const char *unlink_this_at_exit = NULL;
static void
cleanup_at_exit(void)
{
    if (unlink_this_at_exit != NULL) {
        unlink(unlink_this_at_exit);
        unlink_this_at_exit = NULL;
    }
}
static void
handle_fatal_signal(int sig) {
    cleanup_at_exit();
    _exit(sig);
}

static void
unlink_at_exit(const char *path)
{
    if (unlink_this_at_exit == NULL) {
        unlink_this_at_exit = path;
        signal(SIGTERM, &handle_fatal_signal);
        signal(SIGINT, &handle_fatal_signal);
        signal(SIGHUP, &handle_fatal_signal);
        atexit(&cleanup_at_exit);
    }
}

static int
create_local_listener(const char *sockname, int backlog)
{
    int res;
    int sock;
    struct sockaddr_un a = { 0 };
    res = unlink(sockname);
    if (!(res == 0 || errno == ENOENT))
        fprintf(stderr, "failed to unlink %s\n", sockname);
    a.sun_family = AF_UNIX;
    strncpy(a.sun_path, sockname, sizeof(a.sun_path));
    sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock == -1)
        return(sock);
    res = bind(sock, (struct sockaddr *)&a, sizeof(a));
    if (res == -1) {
        close(sock);
        return(-1);
    }
    unlink_at_exit(sockname);
    res = listen(sock, backlog);
    if (res == -1) {
        close(sock);
        return(-1);
    }
    return(sock);
}

static void
accept_new_client(struct ccnd *h)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct sockaddr who;
    socklen_t wholen = sizeof(who);
    int fd;
    int res;
    struct face *face;
    fd = accept(h->local_listener_fd, &who, &wholen);
    if (fd == -1) {
        perror("accept");
        return;
    }
    res = fcntl(fd, F_SETFL, O_NONBLOCK);
    if (res == -1)
        perror("fcntl");
    hashtb_start(h->faces, e);
    if (hashtb_seek(e, &fd, sizeof(fd)) != HT_NEW_ENTRY) {
        perror("ccnd: accept_new_client");
        exit(1);
    }
    face = e->data;
    face->fd = fd;
    fprintf(stderr, "ccnd[%d] accepted client fd=%d\n", (int)getpid(), fd);
}

static void
shutdown_client_fd(struct ccnd *h, int fd)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct face *face;
    hashtb_start(h->faces, e);
    if (hashtb_seek(e, &fd, sizeof(fd)) != HT_OLD_ENTRY) {
        perror("ccnd: shutdown_client_fd");
        exit(1);
    }
    face = e->data;
    if (face->fd != fd) abort();
    close(fd);
    face->fd = -1;
    fprintf(stderr, "ccnd[%d] shutdown client fd=%d\n", (int)getpid(), fd);
    ccn_charbuf_destroy(&face->inbuf);
    hashtb_delete(e);
}

static void
process_input(struct ccnd *h, int fd)
{
    struct face *face = hashtb_lookup(h->faces, &fd, sizeof(fd));
    ssize_t res;
    ssize_t msgstart;
    void *buf;
    struct ccn_skeleton_decoder *d = &face->decoder;
    if (face->inbuf == NULL)
        face->inbuf = ccn_charbuf_create();
    if (face->inbuf->length == 0)
        memset(d, 0, sizeof(*d));
    buf = ccn_charbuf_reserve(face->inbuf, 32);
    res = read(face->fd, buf, face->inbuf->limit - face->inbuf->length);
    if (res == -1)
        perror("ccnd: read");
    else if (res == 0) {
        shutdown_client_fd(h, fd);
    }
    else {
        face->inbuf->length += res;
        msgstart = 0;
        ccn_skeleton_decode(d, buf, res);
        while (d->state == 0 && d->tagstate == 0 && d->nest == 0) {
            fprintf(stderr, "%lu byte msg received on %d\n",
                (unsigned long)(d->index - msgstart), fd);
            if (d->index == face->inbuf->length) {
                face->inbuf->length = 0;
                break;
            }
            msgstart = d->index;
            ccn_skeleton_decode(d,
                    face->inbuf->buf + d->index,
                    face->inbuf->length - d->index);
        }
        if (d->state < 0) {
            fprintf(stderr, "ccnd: protocol error on %d\n", fd);
            shutdown_client_fd(h, fd);
            return;
        }
        if (d->index < face->inbuf->length) {
            /* move partial message to start of buffer */
            memmove(face->inbuf->buf, face->inbuf->buf + d->index,
                face->inbuf->length - d->index);
            face->inbuf->length -= d->index;
            d->index = 0;
        }
    }
}

static void
run(struct ccnd *h)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    int i;
    int res;
    int timeout_ms = -1;
    char buf[1] = "?";
    for (;;) {
        if (hashtb_n(h->faces) + 1 != h->nfds) {
            h->nfds = hashtb_n(h->faces) + 1;
            h->fds = realloc(h->fds, h->nfds * sizeof(h->fds[0]));
            memset(h->fds, 0, h->nfds * sizeof(h->fds[0]));
        }
        h->fds[0].fd = h->local_listener_fd;
        h->fds[0].events = POLLIN;
        for (i = 1, hashtb_start(h->faces, e);
             i < h->nfds && e->data != NULL;
             i++, hashtb_next(e)) {
            struct face *face = e->data;
            h->fds[i].fd = face->fd;
            h->fds[i].events = POLLIN;
        }
        h->nfds = i;
        res = poll(h->fds, h->nfds, timeout_ms);
        if (-1 == res) {
            perror("ccnd: poll");
            sleep(1);
            continue;
        }
        /* Check for new clients first */
        if (h->fds[0].revents != 0) {
            if (h->fds[0].revents & (POLLERR | POLLNVAL | POLLHUP))
                return;
            if (h->fds[0].revents & (POLLIN))
                accept_new_client(h);
            res--;
        }
        for (i = 1; res > 0 && i < h->nfds; i++) {
            if (h->fds[i].revents != 0) {
                res--;
                if (h->fds[i].revents & (POLLERR | POLLNVAL | POLLHUP)) {
                    shutdown_client_fd(h, h->fds[i].fd);
                    continue;
                }
                if (h->fds[i].revents & (POLLIN))
                    process_input(h, h->fds[i].fd);
                if (h->fds[i].revents & (POLLOUT))
                    write(h->fds[i].fd, buf, 1);
            }
        }
    }
}

int
main(int argc, char **argv)
{
    const char *sockname = CCN_DEFAULT_LOCAL_SOCKNAME;
    int ll;
    struct ccnd *h;
    h = calloc(1, sizeof(h));
    h->faces = hashtb_create(sizeof(struct face));
    ll = create_local_listener(sockname, 42);
    if (ll == -1) {
        perror(sockname);
        exit(1);
    }
    fprintf(stderr, "ccnd[%d] listening on %s\n", (int)getpid(), sockname);
    h->local_listener_fd = ll;
    run(h);
    fprintf(stderr, "ccnd[%d] exiting.\n", (int)getpid());
    exit(0);
}
