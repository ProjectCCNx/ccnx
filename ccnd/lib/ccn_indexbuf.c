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

int ccn_indexbuf_comp_strcmp(const char *data, const struct ccn_indexbuf* indexbuf, unsigned int index, const char *val) {
    /* indexbuf should have an extra value marking end of last component,
       so we need to use last 2 values */
    if (index > indexbuf->n-2) {
	/* There isn't a component at this index, so no match, call
	   supplied value greater-than */
	return 1;
    }
    return strncmp(val, data + indexbuf->buf[index], indexbuf->buf[index+1]-indexbuf->buf[index]);
}

char * ccn_indexbuf_comp_strdup(const char *data, const struct ccn_indexbuf *indexbuf, unsigned int index) {
    char * result;
    int len;
    /* indexbuf should have an extra value marking end of last component,
       so we need to use last 2 values */
    if (index > indexbuf->n-2) {
	/* There isn't a component at this index */
	return NULL;
    }
    len = indexbuf->buf[index+1]-indexbuf->buf[index];
    result = malloc(len + 1); /* add extra byte for safety \0 */
    if (result == NULL) {
	return NULL;
    }
    memcpy(result, data + indexbuf->buf[index], len);
    /* Ensure null-terminated by adding \0 here */
    result[len] = '\0';
    return result;
}

