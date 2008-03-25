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

struct ccn {
    int sock;
    int err;
    int errline;
};

#define NOTE_ERR(h, e) (h->err = (e), h->errline = __LINE__, -1)
#define NOTE_ERRNO(h) NOTE_ERR(h, errno)

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
    if (*hp == NULL)
        return;
    ccn_disconnect(*hp);
    free(*hp);
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


