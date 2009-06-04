#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <ccn/indexbuf.h>

#define ELEMENT size_t

struct ccn_indexbuf *
ccn_indexbuf_create(void)
{
    struct ccn_indexbuf *c;
    c = calloc(1, sizeof(*c));
    return(c);
}

void
ccn_indexbuf_destroy(struct ccn_indexbuf **cbp)
{
    struct ccn_indexbuf *c = *cbp;
    if (c != NULL) {
        if (c->buf != NULL) {
            free(c->buf);
        }
        free(c);
        *cbp = NULL;
    }
}

/*
 * ccn_indexbuf_reserve:
 * expand buffer as necessary to hold at least n more values
 * return pointer to reserved space
 */
ELEMENT *
ccn_indexbuf_reserve(struct ccn_indexbuf *c, size_t n)
{
    size_t newlim = n + c->n;
    size_t oldlim = c->limit;
    ELEMENT *buf = c->buf;
    if (newlim < n)
        return(NULL);
    if (newlim > oldlim) {
        if (2 * oldlim > newlim)
            newlim = 2 * oldlim;
        buf = realloc(c->buf, newlim * sizeof(ELEMENT));
        if (buf == NULL)
            return(NULL);
        memset(buf + oldlim, 0, (newlim - oldlim) * sizeof(ELEMENT));
        c->buf = buf;
        c->limit = newlim;
    }
    buf += c->n;
    return(buf);
}

int
ccn_indexbuf_append(struct ccn_indexbuf *c, const ELEMENT *p, size_t n)
{
    ELEMENT *dst = ccn_indexbuf_reserve(c, n);
    if (dst == NULL)
        return(-1);
    memcpy(dst, p, n * sizeof(ELEMENT));
    c->n += n;
    return(0);
}

int
ccn_indexbuf_append_element(struct ccn_indexbuf *c, ELEMENT v)
{
    ELEMENT *dst = ccn_indexbuf_reserve(c, 1);
    if (dst == NULL)
        return(-1);
    *dst = v;
    c->n += 1;
    return(0);
}

int
ccn_indexbuf_member(struct ccn_indexbuf *x, ELEMENT val)
{
    int i;
    if (x == NULL)
        return (-1);
    for (i = x->n - 1; i >= 0; i--)
        if (x->buf[i] == val)
            return(i);
    return(-1);
}

void
ccn_indexbuf_remove_element(struct ccn_indexbuf *x, ELEMENT val)
{
    int i;
    if (x == NULL) return;
    for (i = x->n - 1; i >= 0; i--)
        if (x->buf[i] == val) {
            x->buf[i] = x->buf[--x->n]; /* move last element into vacant spot */
            return;
        }
}

