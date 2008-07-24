#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
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
ccn_charbuf_append_charbuf(struct ccn_charbuf *c, const struct ccn_charbuf *in)
{
  return(ccn_charbuf_append(c, in->buf, in->length));
}

int
ccn_charbuf_append_string(struct ccn_charbuf *c, const char *s)
{
  return(ccn_charbuf_append(c, s, strlen(s)));
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

int
ccn_charbuf_append_datetime(struct ccn_charbuf *c, time_t secs, int nsecs)
{
    char timestring[32];
    int timelen;
    struct tm time_tm;
    int res;

    timelen = strftime(timestring, sizeof(timestring),
                       "%FT%T", gmtime_r(&secs, &time_tm));
    if (timelen >= sizeof(timestring))
        return(-1);
    if (nsecs != 0) {
        if (nsecs < 0 || nsecs >= 1000000000)
            return(-1);
        timelen += snprintf(&timestring[timelen], sizeof(timestring) - timelen,
                            ".%09d", nsecs);
        if (timelen >= sizeof(timestring))
            return(-1);
        while (timestring[timelen - 1] == '0') timelen--;
    }
    timestring[timelen++] = 'Z';
    res = ccn_charbuf_append(c, timestring, timelen);
    return (res);
}

int
ccn_charbuf_append_datetime_now(struct ccn_charbuf *c, int precision)
{
    struct timeval now;
    int res;
    int r = 1;

    gettimeofday(&now, NULL);

    if (precision < 0)
        return (-1);
    if (precision < CCN_DATETIME_PRECISION_USEC) {
        for (; precision < CCN_DATETIME_PRECISION_USEC; precision++)
            r *= 10;
        now.tv_usec = r * ((now.tv_usec + (r / 2)) / r);
        if (now.tv_usec >= 1000000) {
            now.tv_sec++;
            now.tv_usec -= 1000000;
        }
    }
    res = ccn_charbuf_append_datetime(c, now.tv_sec, now.tv_usec * 1000);
    return (res);
}

char *
ccn_charbuf_as_string(struct ccn_charbuf *c)
{
    unsigned char *r;
    r = ccn_charbuf_reserve(c, 1);
    if (r == NULL)
        return(NULL);
    r[0] = 0;
    return((char *)c->buf);
}
