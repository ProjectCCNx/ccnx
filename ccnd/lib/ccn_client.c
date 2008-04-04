#include <arpa/inet.h>
#include <err.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
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
    struct ccn_charbuf *outbuf;
    struct hashtb *interests;
    struct ccn_closure *default_content_handler;
    struct ccn_closure *default_interest_handler;
    int err;
    int errline;
};

struct expressed_interest {
    int repeat;
    struct ccn_closure *action;
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
    ccn_replace_handler(h, &(h->default_interest_handler), NULL);
    ccn_replace_handler(h, &(h->default_content_handler), NULL);
    if (h->interests != NULL) {
        for (hashtb_start(h->interests, e); e->data != NULL; hashtb_next(e)) {
            struct expressed_interest *i = e->data;
            ccn_replace_handler(h, &(i->action), NULL);
        }
        hashtb_destroy(&(h->interests));
    }
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
    if (res == HT_NEW_ENTRY) {
        // set up the timekeeping stuff
    }
    return(0);
}

int
ccn_set_default_content_handler(struct ccn *h,
                                struct ccn_closure *action)
{
    if (h == NULL)
        return(-1);
    ccn_replace_handler(h, &(h->default_content_handler), action);
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
        size_t size;
        // XXX - should limit unbounded growth of h->outbuf
        ccn_charbuf_append(h->outbuf, p, length); // XXX - check res
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

int
ccn_run(struct ccn *h, int timeout)
{
/**********
    for (each expressed interest) {
        if (interest has timed out) {
            refresh the interest
        }
    }
    if (have data to send)
        set POLLOUT
    for (;;) {
        set POLLIN
        poll(...)
        if (output is ready)
            send more stuff
        if (input is ready)
            read and dispatch
    }
*************/
    return(NOTE_ERR(h, ENOSYS));
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


