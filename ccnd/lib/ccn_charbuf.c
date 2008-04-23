#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <ccn/charbuf.h>

struct ccn_charbuf *
ccn_charbuf_create(void)
{
    struct ccn_charbuf *c;
    c = calloc(1, sizeof(*c));
    return(c);
}

void
ccn_charbuf_destroy(struct ccn_charbuf **cbp)
{
    struct ccn_charbuf *c = *cbp;
    if (c != NULL) {
        if (c->buf != NULL) {
            free(c->buf);
        }
        free(c);
        *cbp = NULL;
    }
}

/*
 * ccn_charbuf_reserve: expand buffer as necessary to hold n more chars
 */
unsigned char *
ccn_charbuf_reserve(struct ccn_charbuf *c, size_t n)
{
    size_t newsz = n + c->length;
    unsigned char *buf = c->buf;
    if (newsz < n)
        return(NULL);
    if (newsz > c->limit) {
        if (2 * c->limit > newsz)
            newsz = 2 * c->limit;
        buf = realloc(c->buf, newsz);
        if (buf == NULL)
            return(NULL);
        c->buf = buf;
        c->limit = newsz;
    }
    buf += c->length;
    return(buf);
}

int
ccn_charbuf_append(struct ccn_charbuf *c, const void *p, size_t n)
{
    unsigned char *dst = ccn_charbuf_reserve(c, n);
    if (dst == NULL)
        return(-1);
    memcpy(dst, p, n);
    c->length += n;
    return(0);
}

int
ccn_charbuf_putf(struct ccn_charbuf *c, const char *fmt, ...)
{
    int sz;
    va_list ap;
    char *buf;
    buf = (char *)ccn_charbuf_reserve(c, strlen(fmt) + 10); /* estimate */
    if (buf == NULL) return(-1);
    va_start(ap, fmt);
    sz = vsnprintf(buf, c->limit - c->length, fmt, ap);
    if (sz < 0)
        return(sz);
    if (c->length + sz < c->limit) {
        c->length += sz;
        return(sz);
    }
    va_end(ap);
    buf = (char *)ccn_charbuf_reserve(c, sz + 1); /* accurate */
    if (buf == NULL) return(-1);
    va_start(ap, fmt);
    sz = vsnprintf(buf, c->limit - c->length, fmt, ap);
    if (c->length + sz < c->limit) {
        c->length += sz;
        return(sz);
    }
    return(-1);
}
