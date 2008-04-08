/*
 * ccnd.c
 *  
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * $Id$
 */

#include <sys/types.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <ccn/ccn.h>
#include <ccn/ccnd.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/hashtb.h>
#include <ccn/schedule.h>

struct ccnd;
struct dbl_links;
struct face;

/*
 * We pass this handle almost everywhere.
 */
struct ccnd {
    int local_listener_fd;
    struct hashtb *faces; /* keyed by fd */
    struct hashtb *dgram_faces; /* keyed by sockaddr */
    nfds_t nfds;
    struct pollfd *fds;
    struct ccn_schedule *sched;
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
    int sock_type;
    int flags;
    struct ccn_charbuf *inbuf;
    struct ccn_skeleton_decoder decoder;
    size_t outbufindex;
    struct ccn_charbuf *outbuf;
    const struct sockaddr *addr;
    socklen_t addrlen;
};
#define CCN_FACE_LINK (1 << 0)

static void cleanup_at_exit(void);
static void unlink_at_exit(const char *path);
static int create_local_listener(const char *sockname, int backlog);
static void accept_new_client(struct ccnd *h);
static void shutdown_client_fd(struct ccnd *h, int fd);
static void process_input_message(struct ccnd *h, struct face *face,
                                  unsigned char *msg, size_t size);
static void process_input(struct ccnd *h, int fd);
static void do_write(struct ccnd *h, struct face *face,
                     unsigned char *data, size_t size);
static void do_deferred_write(struct ccnd *h, int fd);
static void run(struct ccnd *h);

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
    face->sock_type = SOCK_STREAM;
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
    ccn_charbuf_destroy(&face->outbuf);
    hashtb_delete(e);
}

/*
 * do_write_BFI:
 * This is temporary...
 */
static void
do_write_BFI(struct ccnd *h, struct face *face,
             unsigned char *data, size_t size) {
    struct ccn_charbuf *c;
    if ((face->flags & CCN_FACE_LINK) != 0) {
        c = ccn_charbuf_create();
        ccn_charbuf_reserve(c, size + 5);
        ccn_charbuf_append_tt(c, CCN_DTAG_CCNProtocolDataUnit, CCN_DTAG);
        ccn_charbuf_append(c, data, size);
        ccn_charbuf_append_closer(c);
        do_write(h, face, c->buf, c->length);
        ccn_charbuf_destroy(&c);
        return;
    }
    do_write(h, face, data, size);
}

static void
process_input_message_BFI(struct ccnd *h, struct face *face,
                unsigned char *msg, size_t size)
{
    // BFI version
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    for (hashtb_start(h->faces, e); e->data != NULL; hashtb_next(e)) {
        struct face *otherface = e->data;
        if (face != otherface && otherface->sock_type != SOCK_DGRAM) {
            do_write_BFI(h, otherface, msg, size);
        }
    }
    for (hashtb_start(h->dgram_faces, e); e->data != NULL; hashtb_next(e)) {
        struct face *otherface = e->data;
        if (face != otherface) {
            do_write_BFI(h, otherface, msg, size);
        }
    }
}

static void
process_input_message(struct ccnd *h, struct face *face, unsigned char *msg, size_t size)
{
    struct ccn_skeleton_decoder decoder = {0};
    struct ccn_skeleton_decoder *d = &decoder;
    ssize_t dres;
    d->state |= CCN_DSTATE_PAUSE;
    dres = ccn_skeleton_decode(d, msg, size);
    if (CCN_GET_TT_FROM_DSTATE(d->state) == CCN_DTAG) {
        if (d->numval == CCN_DTAG_CCNProtocolDataUnit) {
            size -= d->index;
            if (size > 0)
                size--;
            msg += d->index;
            face->flags |= CCN_FACE_LINK;
            memset(d, 0, sizeof(*d));
            while (d->index < size) {
                dres = ccn_skeleton_decode(d, msg + d->index, size - d->index);
                if (d->state != 0)
                    break;
                process_input_message_BFI(h, face, msg + d->index - dres, dres);
            }
            return;
        }
        else if (d->numval == CCN_DTAG_Interest || CCN_DTAG_Content) {
            process_input_message_BFI(h, face, msg, size);
            return;
        }
    }
    fprintf(stderr, "discarding unknown message; size = %lu", (unsigned long)size);
}

struct face *
get_source(struct ccnd *h, struct face *face,
           struct sockaddr *addr, socklen_t addrlen)
{
    struct face *source;
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    if (face->sock_type != SOCK_DGRAM)
        return(face);
    hashtb_start(h->dgram_faces, e);
    hashtb_seek(e, addr, addrlen);
    source = e->data;
    if (source != NULL && source->addr == NULL) {
        source->addr = e->key;
        source->addrlen = e->keysize;
        source->fd = face->fd;
        source->sock_type = SOCK_DGRAM;
    }
    return(source);
}

static void
process_input(struct ccnd *h, int fd)
{
    struct face *face = hashtb_lookup(h->faces, &fd, sizeof(fd));
    struct face *source = NULL;
    ssize_t res;
    ssize_t dres;
    ssize_t msgstart;
    unsigned char *buf;
    struct ccn_skeleton_decoder *d = &face->decoder;
    struct sockaddr_storage sstor;
    socklen_t addrlen = sizeof(sstor);
    struct sockaddr *addr = (struct sockaddr *)&sstor;
    if (face->inbuf == NULL)
        face->inbuf = ccn_charbuf_create();
    if (face->inbuf->length == 0)
        memset(d, 0, sizeof(*d));
    buf = ccn_charbuf_reserve(face->inbuf, 8800);
    res = recvfrom(face->fd, buf, face->inbuf->limit - face->inbuf->length,
            /* flags */ 0, addr, &addrlen);
    if (res == -1)
        perror("ccnd: recvfrom");
    else if (res == 0) {
        if (face->sock_type != SOCK_DGRAM)
            shutdown_client_fd(h, fd);
    }
    else {
        source = get_source(h, face, addr, addrlen);
        face->inbuf->length += res;
        msgstart = 0;
        dres = ccn_skeleton_decode(d, buf, res);
        fprintf(stderr, "ccn_skeleton_decode of %d bytes accepted %d\n",
                        (int)res, (int)dres);
        while (d->state == 0) {
            fprintf(stderr, "%lu byte msg received on %d\n",
                (unsigned long)(d->index - msgstart), fd);
            process_input_message(h, source, face->inbuf->buf + msgstart, 
                                           d->index - msgstart);
            msgstart = d->index;
            if (msgstart == face->inbuf->length) {
                face->inbuf->length = 0;
                return;
            }
            dres = ccn_skeleton_decode(d,
                    face->inbuf->buf + d->index,
                    res = face->inbuf->length - d->index);
            fprintf(stderr, "  ccn_skeleton_decode of %d bytes accepted %d\n",
                            (int)res, (int)dres);
        }
        if (face->sock_type == SOCK_DGRAM) {
            fprintf(stderr, "ccnd: protocol error, discarding %d bytes\n",
                (int)(face->inbuf->length - d->index));
            face->inbuf->length = 0;
            return;
        }
        else if (d->state < 0) {
            fprintf(stderr, "ccnd: protocol error on %d\n", fd);
            shutdown_client_fd(h, fd);
            return;
        }
        if (msgstart < face->inbuf->length && msgstart > 0) {
            /* move partial message to start of buffer */
            memmove(face->inbuf->buf, face->inbuf->buf + msgstart,
                face->inbuf->length - msgstart);
            face->inbuf->length -= msgstart;
            d->index -= msgstart;
        }
    }
}

static void
do_write(struct ccnd *h, struct face *face, unsigned char *data, size_t size)
{
    ssize_t res;
    if (face->outbuf != NULL) {
        ccn_charbuf_append(face->outbuf, data, size);
        return;
    }
    if (face->addr == NULL)
        res = send(face->fd, data, size, 0);
    else
        res = sendto(face->fd, data, size, 0, face->addr, face->addrlen);
    if (res == size)
        return;
    if (res == -1) {
        if (errno == EAGAIN)
            res = 0;
        else {
            perror("ccnd: send");
            return;
        }
    }
    if (face->sock_type == SOCK_DGRAM) {
        fprintf(stderr, "ccnd: sendto short\n");
        return;
    }
    face->outbuf = ccn_charbuf_create();
    if (face->outbuf == NULL) {
        perror("ccnd: ccn_charbuf_create");
        exit(1);
    }
    ccn_charbuf_append(face->outbuf, data + res, size - res);
    face->outbufindex = 0;
}

static void
do_deferred_write(struct ccnd *h, int fd)
{
    /* This only happens on connected sockets */
    ssize_t res;
    struct face *face = hashtb_lookup(h->faces, &fd, sizeof(fd));
    if (face != NULL && face->outbuf != NULL) {
        ssize_t sendlen = face->outbuf->length - face->outbufindex;
        if (sendlen > 0) {
            res = send(fd, face->outbuf->buf + face->outbufindex, sendlen, 0);
            if (res == -1) {
                perror("ccnd: write");
                shutdown_client_fd(h, fd);
                return;
            }
            if (res == sendlen) {
                face->outbufindex = 0;
                ccn_charbuf_destroy(&face->outbuf);
                return;
            }
            face->outbufindex += res;
            return;
        }
        face->outbufindex = 0;
        ccn_charbuf_destroy(&face->outbuf);
    }
    fprintf(stderr, "ccnd:do_deferred_write: something fishy on %d\n", fd);
}

static void
run(struct ccnd *h)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    int i;
    int res;
    int timeout_ms = -1;
    int usec;
    for (;;) {
        usec = ccn_schedule_run(h->sched);
        timeout_ms = (usec < 0) ? -1 : usec / 1000;
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
            if (face->outbuf != NULL)
                h->fds[i].events |= POLLOUT;
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
                if (h->fds[i].revents & (POLLOUT))
                    do_deferred_write(h, h->fds[i].fd);
                if (h->fds[i].revents & (POLLIN))
                    process_input(h, h->fds[i].fd);
            }
        }
    }
}

static struct ccnd *
ccnd_create(void)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct face *face;
    const char *sockname = CCN_DEFAULT_LOCAL_SOCKNAME;
    int fd;
    int res;
    struct ccnd *h;
    struct addrinfo hints = {0};
    struct addrinfo *addrinfo = NULL;
    struct addrinfo *a;
    h = calloc(1, sizeof(*h));
    h->faces = hashtb_create(sizeof(struct face));
    h->dgram_faces = hashtb_create(sizeof(struct face));
    h->sched = ccn_schedule_create(h);
    fd = create_local_listener(sockname, 42);
    if (fd == -1) {
        perror(sockname);
        exit(1);
    }
    fprintf(stderr, "ccnd[%d] listening on %s\n", (int)getpid(), sockname);
    h->local_listener_fd = fd;
    hints.ai_family = PF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    hints.ai_flags = AI_ADDRCONFIG;
    res = getaddrinfo(NULL, "4485", &hints, &addrinfo);
    if (res == 0) {
        for (a = addrinfo; a != NULL; a = a->ai_next) {
            fd = socket(a->ai_family, SOCK_DGRAM, 0);
            if (fd != -1) {
                res = bind(fd, a->ai_addr, a->ai_addrlen);
                if (res != 0) {
                    close(fd);
                    continue;
                }
                res = fcntl(fd, F_SETFL, O_NONBLOCK);
                if (res == -1)
                    perror("fcntl");
                hashtb_start(h->faces, e);
                if (hashtb_seek(e, &fd, sizeof(fd)) != HT_NEW_ENTRY)
                    exit(1);
                face = e->data;
                face->fd = fd;
                face->sock_type = SOCK_DGRAM;
                fprintf(stderr, "ccnd[%d] accepting datagrams on fd %d\n",
                    (int)getpid(), fd);
            }
        }
        freeaddrinfo(addrinfo);
    }
    return(h);
}

static int
beat(struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags)
{
    fprintf(stderr, "%c", 'G'&31);
    fflush(stderr);
    if (--ev->evint <= 0)
        return(0);
    return(60000000/72);
}

int
main(int argc, char **argv)
{
    struct ccnd *h;
    h = ccnd_create();
    if (1)
        ccn_schedule_event(h->sched, 500000, &beat, NULL, 5);
    run(h);
    fprintf(stderr, "ccnd[%d] exiting.\n", (int)getpid());
    exit(0);
}
