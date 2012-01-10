/**
 * @file ccn_indexbuf.c
 * @brief Support for expandable buffer of non-negative values.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <ccn/indexbuf.h>

#define ELEMENT size_t

/**
 * Create a new indexbuf.
 */
struct ccn_indexbuf *
ccn_indexbuf_create(void)
{
    struct ccn_indexbuf *c;
    c = calloc(1, sizeof(*c));
    return(c);
}

/**
 * Deallocate indexbuf.
 */
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

/**
 * Expand buffer as necessary to hold at least n more values.
 * @returns pointer to reserved space
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
#ifdef CCN_NOREALLOC
        buf = malloc(newlim * sizeof(ELEMENT));
        if (buf == NULL)
            return(NULL);
        memcpy(buf, c->buf, oldlim * sizeof(ELEMENT));
        free(c->buf);
#else
        buf = realloc(c->buf, newlim * sizeof(ELEMENT));
        if (buf == NULL)
            return(NULL);
#endif
        memset(buf + oldlim, 0, (newlim - oldlim) * sizeof(ELEMENT));
        c->buf = buf;
        c->limit = newlim;
    }
    buf += c->n;
    return(buf);
}

/**
 * Append multiple elements to the indexbuf.
 * @returns 0 for success, -1 for failure.
 */
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

/**
 * Append v to the indexbuf
 * @returns 0 for success, -1 for failure.
 */
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

/**
 * @returns index at which the element was found or appended, or -1 if not found.
 */
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

/**
 * Removes up to one instance of val from the indexbuf.
 * Order of elements not preserved.
 */
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

/**
 * @returns index at which the element was found or appended,
 *          or -1 in case of error.
 */
int
ccn_indexbuf_set_insert(struct ccn_indexbuf *x, ELEMENT val)
{
    int i;
    if (x == NULL)
        return (-1);
    for (i = 0; i < x->n; i++)
        if (x->buf[i] == val)
            return(i);
    if (ccn_indexbuf_append_element(x, val) < 0)
        return(-1);
    return(i);
}

/**
 * Removes first occurrence of val, preserving order
 * @returns index at which the element was found,
 *          or -1 if the element was not found.
 */
int
ccn_indexbuf_remove_first_match(struct ccn_indexbuf *x, ELEMENT val)
{
    int i;
    int n;
    if (x == NULL)
        return (-1);
    for (i = 0, n = x->n; i < n; i++) {
        if (x->buf[i] == val) {
            if (i + 1 < n)
                memmove(&(x->buf[i]),
                        &(x->buf[i + 1]),
                        sizeof(x->buf[i]) * (n - i - 1));
            x->n--;
            return(i);
        }
    }
    return(-1);
}

/**
 * If val is present in the indexbuf, move it to the final place.
 */
void
ccn_indexbuf_move_to_end(struct ccn_indexbuf *x, ELEMENT val)
{
    int i;
    int n;
    if (x == NULL)
        return;
    for (i = 0, n = x->n; i + 1 < n; i++) {
        if (x->buf[i] == val) {
            memmove(&(x->buf[i]),
                    &(x->buf[i + 1]),
                    sizeof(x->buf[i]) * (n - i - 1));
            x->buf[n - 1] = val;
            return;
        }
    }
}

/**
 * If val is present in the indexbuf, move it to the first place.
 */
void
ccn_indexbuf_move_to_front(struct ccn_indexbuf *x, ELEMENT val)
{
    int i;
    int n;
    if (x == NULL)
        return;
    for (i = 0, n = x->n; i < n; i++) {
        if (x->buf[i] == val) {
            memmove(&(x->buf[1]),
                    &(x->buf[0]),
                    sizeof(x->buf[i]) * i);
            x->buf[0] = val;
            return;
        }
    }

}

