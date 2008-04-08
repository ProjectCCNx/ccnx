#include <arpa/inet.h>
#include <err.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/ccnd.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/hashtb.h>

struct ccn {
    int sock;
    size_t outbufindex;
    struct ccn_charbuf *interestbuf;
    struct ccn_charbuf *inbuf;
    struct ccn_charbuf *outbuf;
    struct hashtb *interests;
    struct ccn_closure *default_content_action;
    struct ccn_closure *default_interest_action;
    struct ccn_skeleton_decoder decoder;
    int err;
    int errline;
};

struct expressed_interest {
    struct timeval lasttime;
    struct ccn_closure *action;
    int repeat;
    int target;
    int outstanding;
};

#define NOTE_ERR(h, e) (h->err = (e), h->errline = __LINE__, -1)
#define NOTE_ERRNO(h) NOTE_ERR(h, errno)

static void
ccn_replace_handler(struct ccn *h, struct ccn_closure **dstp, struct ccn_closure *src)
{
    struct ccn_closure *old = *dstp;
    if (src == old)
        return;
    if (src != NULL)
        src->refcount++;
    *dstp = src;
    if (old != NULL && (--(old->refcount)) == 0) {
        (old->p)(old, CCN_UPCALL_FINAL, h, NULL, 0, NULL, 0);
    }
}

struct ccn *
ccn_create(void)
{
    struct ccn *h;
    h = calloc(1, sizeof(*h));
    if (h != NULL) {
        h->sock = -1;
    }
    h->interestbuf = ccn_charbuf_create();
    return(h);
}

int
ccn_connect(struct ccn *h, const char *name)
{
    struct sockaddr_un addr = {0};
    int res;
    if (h == NULL || h->sock != -1)
        return(NOTE_ERR(h, EINVAL));
    if (name == NULL)
        name = CCN_DEFAULT_LOCAL_SOCKNAME;
    h->sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (h->sock == -1)
        return(NOTE_ERRNO(h));
    strncpy(addr.sun_path, name, sizeof(addr.sun_path));
    addr.sun_family = AF_UNIX;
    res = connect(h->sock, (struct sockaddr *)&addr, sizeof(addr));
    if (res == -1)
        return(NOTE_ERRNO(h));
    res = fcntl(h->sock, F_SETFL, O_NONBLOCK);
    if (res == -1)
        return(NOTE_ERRNO(h));
    return(h->sock);
}

int
ccn_get_connection_fd(struct ccn *h)
{
    return(h->sock);
}

int
ccn_disconnect(struct ccn *h)
{
    int res;
    ccn_charbuf_destroy(&h->inbuf);
    ccn_charbuf_destroy(&h->outbuf);
    res = close(h->sock);
    h->sock = -1;
    if (res == -1)
        return(NOTE_ERRNO(h));
    return(0);
}

void
ccn_destroy(struct ccn **hp)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct ccn *h = *hp;
    if (h == NULL)
        return;
    ccn_disconnect(h);
    ccn_replace_handler(h, &(h->default_interest_action), NULL);
    ccn_replace_handler(h, &(h->default_content_action), NULL);
    if (h->interests != NULL) {
        for (hashtb_start(h->interests, e); e->data != NULL; hashtb_next(e)) {
            struct expressed_interest *i = e->data;
            ccn_replace_handler(h, &(i->action), NULL);
        }
        hashtb_destroy(&(h->interests));
    }
    ccn_charbuf_destroy(&h->interestbuf);
    free(h);
    *hp = NULL;
}

int
ccn_name_init(struct ccn_charbuf *c)
{
    int res;
    const unsigned char closer = CCN_CLOSE;
    c->length = 0;
    res = ccn_charbuf_append_tt(c, CCN_DTAG_Name, CCN_DTAG);
    if (res == -1) return(res);
    res = ccn_charbuf_append(c, &closer, 1);
    return(res);
}

int
ccn_name_append(struct ccn_charbuf *c, const void *component, size_t n)
{
    int res;
    const unsigned char closer[2] = {CCN_CLOSE, CCN_CLOSE};
    if (c->length < 2 || c->buf[c->length-1] != closer[1])
        return(-1);
    c->length -= 1;
    ccn_charbuf_reserve(c, n + 8);
    res = ccn_charbuf_append_tt(c, CCN_DTAG_Component, CCN_DTAG);
    if (res == -1) return(res);
    res = ccn_charbuf_append_tt(c, n, CCN_BLOB);
    if (res == -1) return(res);
    res = ccn_charbuf_append(c, component, n);
    if (res == -1) return(res);
    res = ccn_charbuf_append(c, closer, sizeof(closer));
    return(res);
}

int
ccn_express_interest(struct ccn *h, struct ccn_charbuf *namebuf,
                     int repeat, struct ccn_closure *action)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    int res;
    struct expressed_interest *interest;
    if (h->interests == NULL) {
        h->interests = hashtb_create(sizeof(struct expressed_interest));
        if (h->interests == NULL)
            return(NOTE_ERRNO(h));
    }
    hashtb_start(h->interests, e);
    // XXX - should validate namebuf more than this
    if (namebuf == NULL || namebuf->length < 2 ||
          namebuf->buf[namebuf->length-1] != CCN_CLOSE)
        return(NOTE_ERR(h, EINVAL));
    /*
     * To make it easy to lookup prefixes of names, we do not 
     * keep the final CCN_CLOSE as part of the key.
     */
    res = hashtb_seek(e, namebuf->buf, namebuf->length-1);
    interest = e->data;
    if (interest == NULL)
        return(NOTE_ERRNO(h));
    if (repeat == 0) {
        ccn_replace_handler(h, &(interest->action), NULL);
        hashtb_delete(e);
        return(0);
    }
    if (repeat > 0 && interest->repeat >= 0)
        interest->repeat += repeat;
    else
        interest->repeat = -1;
    ccn_replace_handler(h, &(interest->action), action);
    interest->target = 1;
    return(0);
}

int
ccn_set_default_interest_handler(struct ccn *h,
                                struct ccn_closure *action)
{
    if (h == NULL)
        return(-1);
    ccn_replace_handler(h, &(h->default_interest_action), action);
    return(0);
}

int
ccn_set_default_content_handler(struct ccn *h,
                                struct ccn_closure *action)
{
    if (h == NULL)
        return(-1);
    ccn_replace_handler(h, &(h->default_content_action), action);
    return(0);
}

static int
ccn_pushout(struct ccn *h)
{
    ssize_t res;
    size_t size;
    if (h->outbuf != NULL && h->outbufindex < h->outbuf->length) {
        size = h->outbuf->length - h->outbufindex;
        res = write(h->sock, h->outbuf->buf + h->outbufindex, size);
        if (res == size) {
            h->outbuf->length = h->outbufindex = 0;
            return(0);
        }
        if (res == -1)
            return ((errno == EAGAIN) ? 1 : NOTE_ERRNO(h));
        h->outbufindex += res;
        return(1);
    }
    return(0);
}

int
ccn_put(struct ccn *h, const void *p, size_t length)
{
    struct ccn_skeleton_decoder dd = {0};
    ssize_t res;
    if (h == NULL || p == NULL || length == 0)
        return(NOTE_ERR(h, EINVAL));
    res = ccn_skeleton_decode(&dd, p, length);
    if (!(res == length && dd.state == 0))
        return(NOTE_ERR(h, EINVAL));
    if (h->outbuf != NULL && h->outbufindex < h->outbuf->length) {
        // XXX - should limit unbounded growth of h->outbuf
        ccn_charbuf_append(h->outbuf, p, length); // XXX - check res
        return (ccn_pushout(h));
    }
    res = write(h->sock, p, length);
    if (res == length)
        return(0);
    if (res == -1) {
        if (errno != EAGAIN)
            return(NOTE_ERRNO(h));
        res = 0;
    }
    if (h->outbuf == NULL) {
        h->outbuf = ccn_charbuf_create();
        h->outbufindex = 0;
    }
    ccn_charbuf_append(h->outbuf, ((const unsigned char *)p)+res, length-res);
    return(1);
}

int
ccn_output_is_pending(struct ccn *h)
{
    return(h != NULL && h->outbuf != NULL && h->outbufindex < h->outbuf->length);
}

static void
ccn_refresh_interest(struct ccn *h, struct expressed_interest *interest,
                     const unsigned char *namepre, size_t nameprelen)
{
    struct ccn_charbuf *c = h->interestbuf;
    c->length = 0;
    ccn_charbuf_append_tt(c, CCN_DTAG_Interest, CCN_DTAG);
    ccn_charbuf_append(c, namepre, nameprelen);
    ccn_charbuf_append_closer(c);
    ccn_charbuf_append_closer(c);
    while (interest->outstanding < interest->target) {
        ccn_put(h, c->buf, c->length);
        interest->outstanding += 1;
    }
}

static void
ccn_dispatch_message(struct ccn *h, unsigned char *msg, size_t size)
{
    struct ccn_parsed_interest interest = {0};
    int res;
    res = ccn_parse_interest(msg, size, &interest);
    if (res >= 0) {
        if (h->default_interest_action != NULL) {
            (h->default_interest_action->p)(
                h->default_interest_action,
                CCN_UPCALL_INTEREST,
                h, msg, size, NULL, 0);
        }
        return;
    }
    /* Assume it is a ContentObject. */
    /* XXX - Should check against interests here! */
    if (h->default_content_action != NULL) {
        (h->default_content_action->p)(
            h->default_content_action,
            CCN_UPCALL_CONTENT,
            h, msg, size, NULL, 0);
    }
}

static int
ccn_process_input(struct ccn *h)
{
    ssize_t res;
    ssize_t msgstart;
    unsigned char *buf;
    struct ccn_skeleton_decoder *d = &h->decoder;
    struct ccn_charbuf *inbuf = h->inbuf;
    if (inbuf == NULL)
        h->inbuf = inbuf = ccn_charbuf_create();
    if (inbuf->length == 0)
        memset(d, 0, sizeof(*d));
    buf = ccn_charbuf_reserve(inbuf, 8800);
    res = read(h->sock, buf, inbuf->limit - inbuf->length);
    if (res == 0) {
        ccn_disconnect(h);
        return(-1);
    }
    if (res == -1) {
        if (errno == EAGAIN)
            res = 0;
        else
            return(NOTE_ERRNO(h));
    }
    inbuf->length += res;
    msgstart = 0;
    ccn_skeleton_decode(d, buf, res);
    while (d->state == 0) {
        ccn_dispatch_message(h, inbuf->buf + msgstart, 
                              d->index - msgstart);
        msgstart = d->index;
        if (msgstart == inbuf->length) {
            inbuf->length = 0;
            return(0);
        }
        ccn_skeleton_decode(d, inbuf->buf + d->index,
                            inbuf->length - d->index);
    }
    if (msgstart < inbuf->length && msgstart > 0) {
        /* move partial message to start of buffer */
        memmove(inbuf->buf, inbuf->buf + msgstart,
                inbuf->length - msgstart);
        inbuf->length -= msgstart;
        d->index -= msgstart;
    }
    return(0);
}

#define CCN_INTEREST_HALFLIFE_MICROSEC 4000000
int
ccn_run(struct ccn *h, int timeout)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct timeval now;
    struct timeval start;
    struct expressed_interest *interest;
    int delta;
    int refresh;
    struct pollfd fds[1];
    int timeout_ms;
    int res;
    memset(fds, 0, sizeof(fds));
    start.tv_sec = 0;
    while (h->sock != -1) {
        refresh = 5 * CCN_INTEREST_HALFLIFE_MICROSEC;
        gettimeofday(&now, NULL);
        if (h->interests != NULL && !ccn_output_is_pending(h)) {
             for (hashtb_start(h->interests, e); e->data != NULL; hashtb_next(e)) {
                interest = e->data;
                if (interest->lasttime.tv_sec + 30 < now.tv_sec) {
                    interest->outstanding = 0;
                    interest->lasttime = now;
                }
                delta = (now.tv_sec  - interest->lasttime.tv_sec)*1000000 +
                        (now.tv_usec - interest->lasttime.tv_usec);
                while (delta >= CCN_INTEREST_HALFLIFE_MICROSEC) {
                    interest->outstanding /= 2;
                    delta -= CCN_INTEREST_HALFLIFE_MICROSEC;
                }
                if (delta < 0)
                    delta = 0;
                if (CCN_INTEREST_HALFLIFE_MICROSEC - delta < refresh)
                    refresh = CCN_INTEREST_HALFLIFE_MICROSEC - delta;
                interest->lasttime = now;
                while (delta > interest->lasttime.tv_usec) {
                    delta -= 1000000;
                    interest->lasttime.tv_sec -= 1;
                }
                interest->lasttime.tv_usec -= delta;
                if (interest->outstanding < interest->target)
                    ccn_refresh_interest(h, interest, e->key, e->keysize);
             }
        }
        if (start.tv_sec == 0)
            start = now;
        else if (timeout >= 0) {
            delta = (now.tv_sec  - start.tv_sec) *1000 +
                    (now.tv_usec - start.tv_usec)/1000;
            if (delta > timeout)
                return(0);
        }
        fds[0].fd = h->sock;
        fds[0].events = POLLIN;
        if (ccn_output_is_pending(h))
            fds[0].events |= POLLOUT;
        timeout_ms = refresh / 1000;
        if (timeout >= 0 && timeout < timeout_ms)
            timeout_ms = timeout;
        res = poll(fds, 1, timeout_ms);
        if (res < 0)
            return (NOTE_ERRNO(h));
        if (res > 0) {
            if ((fds[0].revents | POLLOUT) != 0)
                ccn_pushout(h);
            if ((fds[0].revents | POLLIN) != 0) {
                ccn_process_input(h);
            }
        }
    }
    return(-1);
}

int
ccn_charbuf_append_tt(struct ccn_charbuf *c, size_t val, enum ccn_tt tt)
{
    unsigned char buf[1+8*((sizeof(val)+6)/7)];
    unsigned char *p = &(buf[sizeof(buf)-1]);
    int n = 1;
    p[0] = (CCN_TT_HBIT & ~CCN_CLOSE) |
           ((val & CCN_MAX_TINY) << CCN_TT_BITS) |
           (CCN_TT_MASK & tt);
    val >>= (7-CCN_TT_BITS);
    while (val != 0) {
        (--p)[0] = (((unsigned char)val) & ~CCN_TT_HBIT) | CCN_CLOSE;
        n++;
        val >>= 7;
    }
    return (ccn_charbuf_append(c, p, n));
}

int
ccn_charbuf_append_closer(struct ccn_charbuf *c)
{
    int res;
    const unsigned char closer = CCN_CLOSE;
    res = ccn_charbuf_append(c, &closer, 1);
    return(res);
}


