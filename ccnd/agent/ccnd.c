/*
 * ccnd.c
 *  
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * $Id$
 */

#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

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
    struct hashtb *faces_by_fd; /* keyed by fd */
    struct hashtb *dgram_faces; /* keyed by sockaddr */
    struct hashtb *content_tab; /* keyed by name components */
    struct hashtb *interest_tab; /* keyed by name components */
    struct hashtb *propagating_tab; /* keyed by pubid + nonce */
    unsigned face_gen;
    unsigned face_rover;        /* for faceid allocation */
    unsigned face_limit;
    struct face **faces_by_faceid; /* array with face_limit elements */
    int local_listener_fd;
    nfds_t nfds;
    struct pollfd *fds;
    struct ccn_schedule *sched;
    struct ccn_charbuf *scratch_charbuf;
    struct ccn_indexbuf *scratch_indexbuf;
    uint_least64_t accession;
    unsigned short seed[3];
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
    int flags;
    unsigned generation; /* (generation, faceid) is unique */
    unsigned faceid; /* internal face id */
    struct ccn_charbuf *inbuf;
    struct ccn_skeleton_decoder decoder;
    size_t outbufindex;
    struct ccn_charbuf *outbuf;
    const struct sockaddr *addr;
    socklen_t addrlen;
};
/* face flags */
#define CCN_FACE_LINK   (1 << 0) /* Elements wrapped by CCNProtocolDataUnit */
#define CCN_FACE_DGRAM  (1 << 1) /* Datagram interface, respect packets */

struct interest_entry {
    /* The interest hash table is keyed by the Component elements of the Name */
    int ncomp;           /* Number of name components */
    int refcount;
};

struct content_entry {
    /* The content hash table is keyed by the Component elements of the Name */
    uint_least64_t accession;   /* keep track of arrival order */
    unsigned short *comp_end;  /* byte length of each name prefix */
    int ncomp;           /* Number of name components */    
    int tail_size;
    unsigned char *tail; /* ContentObject elements other than the Name */
};

/*
 * The propagating interest hash table is keyed by the concatenation of
 * the PublisherID and the Nonce.
 */
struct propagating_entry {
    struct interest_entry *interest;
    
};

static void cleanup_at_exit(void);
static void unlink_at_exit(const char *path);
static int create_local_listener(const char *sockname, int backlog);
static void accept_new_client(struct ccnd *h);
static void shutdown_client_fd(struct ccnd *h, int fd);
static void process_input_message(struct ccnd *h, struct face *face,
                                  unsigned char *msg, size_t size, int pdu_ok);
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
handle_fatal_signal(int sig)
{
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

static void
fatal_err(const char *msg)
{
    perror(msg);
    exit(1);
}

static struct ccn_charbuf *
charbuf_obtain(struct ccnd *h)
{
    struct ccn_charbuf *c = h->scratch_charbuf;
    if (c == NULL)
        return(ccn_charbuf_create());
    h->scratch_charbuf = NULL;
    c->length = 0;
    return(c);
}

static void
charbuf_release(struct ccnd *h, struct ccn_charbuf *c)
{
    c->length = 0;
    if (h->scratch_charbuf == NULL)
        h->scratch_charbuf = c;
    else
        ccn_charbuf_destroy(&c);
}

static struct ccn_indexbuf *
indexbuf_obtain(struct ccnd *h)
{
    struct ccn_indexbuf *c = h->scratch_indexbuf;
    if (c == NULL)
        return(ccn_indexbuf_create());
    h->scratch_indexbuf = NULL;
    c->n = 0;
    return(c);
}

static void
indexbuf_release(struct ccnd *h, struct ccn_indexbuf *c)
{
    c->n = 0;
    if (h->scratch_indexbuf == NULL)
        h->scratch_indexbuf = c;
    else
        ccn_indexbuf_destroy(&c);
}

static int
enroll_face(struct ccnd *h, struct face *face) {
    unsigned i;
    unsigned n = h->face_limit;
    struct face **a = h->faces_by_faceid;
    for (i = h->face_rover; i < n; i++)
        if (a[i] == NULL) goto use_i;
    h->face_gen++;
    for (i = 0; i < n; i++)
        if (a[i] == NULL) goto use_i;
    i = (h->face_limit + 1) * 3 / 2;
    if (i <= h->face_limit)
        return(-1); /* overflow */
    a = realloc(a, i * sizeof(struct face *));
    if (a == NULL)
        return(-1); /* ENOMEM */
    h->face_limit = i;
    while (--i > n)
        a[i] = NULL;
    h->faces_by_faceid = a;
use_i:
    a[i] = face;
    h->face_rover = i + 1;
    face->faceid = i;
    face->generation = h->face_gen;
    return (i);
}

static void
finalize_face(struct hashtb_enumerator *e)
{
    struct ccnd *h = hashtb_get_param(e->ht, NULL);
    struct face *face = e->data;
    unsigned faceid = face->faceid;
    if (faceid < h->face_limit && h->faces_by_faceid[faceid] == face) {
        h->faces_by_faceid[faceid] = NULL;
        fprintf(stderr, "releasing face id (%u,%u)\n", face->generation, faceid);
    }
    else
        abort();
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
    hashtb_start(h->faces_by_fd, e);
    if (hashtb_seek(e, &fd, sizeof(fd)) != HT_NEW_ENTRY)
        fatal_err("ccnd: accept_new_client");
    face = e->data;
    face->fd = fd;
    res = enroll_face(h, face);
    hashtb_end(e);
    fprintf(stderr, "ccnd[%d] accepted client fd=%d id=%d\n", (int)getpid(), fd, res);
}

static void
shutdown_client_fd(struct ccnd *h, int fd)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct face *face;
    hashtb_start(h->faces_by_fd, e);
    if (hashtb_seek(e, &fd, sizeof(fd)) != HT_OLD_ENTRY)
        fatal_err("ccnd: shutdown_client_fd");
    face = e->data;
    if (face->fd != fd) abort();
    close(fd);
    face->fd = -1;
    fprintf(stderr, "ccnd[%d] shutdown client fd=%d id=%d\n", (int)getpid(), fd, (int)face->faceid);
    ccn_charbuf_destroy(&face->inbuf);
    ccn_charbuf_destroy(&face->outbuf);
    hashtb_delete(e);
    hashtb_end(e);
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
        c = charbuf_obtain(h);
        ccn_charbuf_reserve(c, size + 5);
        ccn_charbuf_append_tt(c, CCN_DTAG_CCNProtocolDataUnit, CCN_DTAG);
        ccn_charbuf_append(c, data, size);
        ccn_charbuf_append_closer(c);
        do_write(h, face, c->buf, c->length);
        charbuf_release(h, c);
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
    for (hashtb_start(h->faces_by_fd, e); e->data != NULL; hashtb_next(e)) {
        struct face *otherface = e->data;
        if (face != otherface && (otherface->flags & CCN_FACE_DGRAM) == 0) {
            do_write_BFI(h, otherface, msg, size);
        }
    }
    hashtb_end(e);
    for (hashtb_start(h->dgram_faces, e); e->data != NULL; hashtb_next(e)) {
        struct face *otherface = e->data;
        if (face != otherface) {
            do_write_BFI(h, otherface, msg, size);
        }
    }
    hashtb_end(e);
}

static int
propagate_interest(struct ccnd *h, struct face *face,
                      struct interest_entry *entry,
                      unsigned char *msg, struct ccn_parsed_interest *pi)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    unsigned char *pkey;
    size_t pkeysize;
    struct ccn_charbuf *cb = NULL;
    int res;
    struct propagating_entry *pe = NULL;
    if (pi->nonce_size == 0) {
        /* This interest has no nonce; add one before going on */
        int noncebytes = 6;
        int i;
        unsigned char *s;
        cb = charbuf_obtain(h);
        ccn_charbuf_append(cb, msg + pi->pubid_start, pi->pubid_size);
        ccn_charbuf_append_tt(cb, CCN_DTAG_Nonce, CCN_DTAG);
        ccn_charbuf_append_tt(cb, noncebytes, CCN_BLOB);
        s = ccn_charbuf_reserve(cb, noncebytes);
        for (i = 0; i < noncebytes; i++)
            s[i] = nrand48(h->seed) >> i;
        cb->length += noncebytes;
        ccn_charbuf_append_closer(cb);
        pkey = cb->buf;
        pkeysize = cb->length;
    }
    else if (pi->nonce_start == pi->pubid_start + pi->pubid_size) {
        pkey = msg + pi->pubid_start;
        pkeysize = pi->pubid_size + pi->nonce_size;
    }
    else {
        pkey = msg + pi->nonce_start;
        pkeysize = pi->nonce_size;
    }
    hashtb_start(h->propagating_tab, e);
    res = hashtb_seek(e, pkey, pkeysize);
    pe = e->data;
    if (res == HT_NEW_ENTRY) {
        pe->interest = entry;
        entry->refcount++;
        res = 0;
    }
    else if (res == HT_OLD_ENTRY) {
        res = -1; /* We've seen this already, do not propagate */
    }
    hashtb_end(e);
    if (cb != NULL)
        charbuf_release(h, cb);
    return(res);
}

static void
process_incoming_interest(struct ccnd *h, struct face *face,
                      unsigned char *msg, size_t size)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct ccn_parsed_interest interest = {0};
    size_t namesize = 0;
    int res;
    struct interest_entry *entry = NULL;
    struct ccn_indexbuf *comps = indexbuf_obtain(h);
    res = ccn_parse_interest(msg, size, &interest, comps);
    if (res < 0) {
        fprintf(stderr, "error parsing Interest - code %d\n", res);
    }
    else if (comps->n < 1 ||
             (namesize = comps->buf[comps->n - 1] - comps->buf[0]) > 65535) {
        fprintf(stderr, "Interest with namesize %lu discarded\n",
                (unsigned long)namesize);
        res = -__LINE__;
    }
    else {
        hashtb_start(h->interest_tab, e);
        res = hashtb_seek(e, msg + comps->buf[0], namesize);
        entry = e->data;
        if (res == HT_NEW_ENTRY) {
            entry->ncomp = comps->n - 1;
            fprintf(stderr, "New interest\n");
        }
        hashtb_end(e);
        res = propagate_interest(h, face, entry, msg, &interest);
    }
    indexbuf_release(h, comps);
    if (res >= 0)
        process_input_message_BFI(h, face, msg, size);
}

static void
process_incoming_content(struct ccnd *h, struct face *face,
                      unsigned char *msg, size_t size)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct ccn_parsed_ContentObject obj = {0};
    int res;
    size_t keysize = 0;
    size_t tailsize = 0;
    unsigned char *tail = NULL;
    struct content_entry *content = NULL;
    unsigned short *comp_end;
    int i;
    struct ccn_indexbuf *comps = indexbuf_obtain(h);
    res = ccn_parse_ContentObject(msg, size, &obj, comps);
    if (res < 0) {
        fprintf(stderr, "error parsing ContentObject - code %d\n", res);
    }
    else if (comps->n < 1 ||
             (keysize = comps->buf[comps->n - 1] - comps->buf[0]) > 65535) {
        fprintf(stderr, "ContentObject with keysize %lu discarded\n",
                (unsigned long)keysize);
        res = -__LINE__;
    }
    else {
        tail = msg + obj.ContentAuthenticator;
        tailsize = size - obj.ContentAuthenticator - 2; /* 2 closers */
        hashtb_start(h->content_tab, e);
        res = hashtb_seek(e, msg + comps->buf[0], keysize);
        content = e->data;
        if (res == HT_OLD_ENTRY) {
            if (tailsize != content->tail_size || 0 != memcmp(tail, content->tail, tailsize)) {
                fprintf(stderr, "ContentObject name collision!!!!!\n");
                content = NULL;
                hashtb_delete(e); /* XXX - Mercilessly throw away both of them. */
                res = -__LINE__;
            }
            else
                fprintf(stderr, "received duplicate ContentObject\n");
        }
        else if (res == HT_NEW_ENTRY) {
            content->accession = ++(h->accession);
            content->ncomp = comps->n - 1;
            comp_end = content->comp_end = calloc(comps->n - 1, sizeof(comp_end[0]));
            content->tail_size = tailsize;
            content->tail = calloc(1, tailsize);
            if (content->tail != NULL && comp_end != NULL) {
                memcpy(content->tail, tail, tailsize);
                for (i = 1; i < comps->n; i++)
                    comp_end[i-1] = comps->buf[i] - comps->buf[0];
            }
            else {
                perror("process_incoming_content");
                hashtb_delete(e);
                res = -__LINE__;
            }
        }
        hashtb_end(e);
    }
    indexbuf_release(h, comps);
    if (res == HT_NEW_ENTRY) {
        process_input_message_BFI(h, face, msg, size);
    }
}

static void
process_input_message(struct ccnd *h, struct face *face,
                      unsigned char *msg, size_t size, int pdu_ok)
{
    struct ccn_skeleton_decoder decoder = {0};
    struct ccn_skeleton_decoder *d = &decoder;
    ssize_t dres;
    d->state |= CCN_DSTATE_PAUSE;
    dres = ccn_skeleton_decode(d, msg, size);
    if (d->state >= 0 && CCN_GET_TT_FROM_DSTATE(d->state) == CCN_DTAG) {
        if (pdu_ok && d->numval == CCN_DTAG_CCNProtocolDataUnit) {
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
                process_input_message(h, face, msg + d->index - dres, dres, 0);
            }
            return;
        }
        else if (d->numval == CCN_DTAG_Interest) {
            process_incoming_interest(h, face, msg, size);
            return;
        }
        else if (d->numval == CCN_DTAG_ContentObject) {
            process_incoming_content(h, face, msg, size);
            return;
        }
    }
    fprintf(stderr, "discarding unknown message; size = %lu\n", (unsigned long)size);
}

struct face *
get_dgram_source(struct ccnd *h, struct face *face,
           struct sockaddr *addr, socklen_t addrlen)
{
    struct face *source;
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    if ((face->flags & CCN_FACE_DGRAM) == 0)
        return(face);
    hashtb_start(h->dgram_faces, e);
    hashtb_seek(e, addr, addrlen);
    source = e->data;
    if (source != NULL && source->addr == NULL) {
        source->addr = e->key;
        source->addrlen = e->keysize;
        source->fd = face->fd;
        source->flags |= CCN_FACE_DGRAM;
        enroll_face(h, source);
    }
    hashtb_end(e);
    return(source);
}

static void
process_input(struct ccnd *h, int fd)
{
    struct face *face = hashtb_lookup(h->faces_by_fd, &fd, sizeof(fd));
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
        if ((face->flags & CCN_FACE_DGRAM) == 0)
            shutdown_client_fd(h, fd);
    }
    else {
        source = get_dgram_source(h, face, addr, addrlen);
        face->inbuf->length += res;
        msgstart = 0;
        dres = ccn_skeleton_decode(d, buf, res);
        fprintf(stderr, "ccn_skeleton_decode of %d bytes accepted %d\n",
                        (int)res, (int)dres);
        while (d->state == 0) {
            fprintf(stderr, "%lu byte msg received on %d\n",
                (unsigned long)(d->index - msgstart), fd);
            process_input_message(h, source, face->inbuf->buf + msgstart, 
                                           d->index - msgstart, 1);
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
        if ((face->flags & CCN_FACE_DGRAM) != 0) {
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
    if ((face->flags & CCN_FACE_DGRAM) != 0) {
        fprintf(stderr, "ccnd: sendto short\n");
        return;
    }
    face->outbuf = ccn_charbuf_create();
    if (face->outbuf == NULL)
        fatal_err("ccnd: ccn_charbuf_create");
    ccn_charbuf_append(face->outbuf, data + res, size - res);
    face->outbufindex = 0;
}

static void
do_deferred_write(struct ccnd *h, int fd)
{
    /* This only happens on connected sockets */
    ssize_t res;
    struct face *face = hashtb_lookup(h->faces_by_fd, &fd, sizeof(fd));
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
        if (hashtb_n(h->faces_by_fd) + 1 != h->nfds) {
            h->nfds = hashtb_n(h->faces_by_fd) + 1;
            h->fds = realloc(h->fds, h->nfds * sizeof(h->fds[0]));
            memset(h->fds, 0, h->nfds * sizeof(h->fds[0]));
        }
        h->fds[0].fd = h->local_listener_fd;
        h->fds[0].events = POLLIN;
        for (i = 1, hashtb_start(h->faces_by_fd, e);
             i < h->nfds && e->data != NULL;
             i++, hashtb_next(e)) {
            struct face *face = e->data;
            h->fds[i].fd = face->fd;
            h->fds[i].events = POLLIN;
            if (face->outbuf != NULL)
                h->fds[i].events |= POLLOUT;
        }
        hashtb_end(e);
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
    struct hashtb_param faceparam = { &finalize_face };
    h = calloc(1, sizeof(*h));
    faceparam.finalize_data = h;
    h->faces_by_fd = hashtb_create(sizeof(struct face), &faceparam);
    h->dgram_faces = hashtb_create(sizeof(struct face), &faceparam);
    h->content_tab = hashtb_create(sizeof(struct content_entry), NULL);
    h->interest_tab = hashtb_create(sizeof(struct interest_entry), NULL);
    h->propagating_tab = hashtb_create(sizeof(struct propagating_entry), NULL);
    h->sched = ccn_schedule_create(h);
    fd = create_local_listener(sockname, 42);
    if (fd == -1) fatal_err(sockname);
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
                hashtb_start(h->faces_by_fd, e);
                if (hashtb_seek(e, &fd, sizeof(fd)) != HT_NEW_ENTRY)
                    exit(1);
                face = e->data;
                face->fd = fd;
                face->flags |= CCN_FACE_DGRAM;
                hashtb_end(e);
                fprintf(stderr, "ccnd[%d] accepting datagrams on fd %d\n",
                    (int)getpid(), fd);
            }
        }
        freeaddrinfo(addrinfo);
    }
    h->seed[1] = (unsigned short)getpid(); /* should gather more entropy than this */
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
    if (0)
        ccn_schedule_event(h->sched, 500000, &beat, NULL, 5);
    run(h);
    fprintf(stderr, "ccnd[%d] exiting.\n", (int)getpid());
    exit(0);
}
