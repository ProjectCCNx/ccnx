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

