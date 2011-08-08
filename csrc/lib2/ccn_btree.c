/**
 * BTree implementation
 */ 
/* (Will be) Part of the CCNx C Library.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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
 
#include <sys/types.h>
#include <stdint.h>
#include <string.h>

#include <ccn/charbuf.h>
#include <ccn/hashtb.h>

#include <ccn/btree.h>

#define MYFETCH(p, f) fetchval(&((p)->f[0]), sizeof((p)->f))
static unsigned
fetchval(const unsigned char *p, int size)
{
    int i;
    unsigned v;
    
    for (v = 0, i = 0; i < size; i++)
        v = (v << 8) + p[i];
    return(v);
}

#define MYFETCHL(p, f) fetchvall(&((p)->f[0]), sizeof((p)->f))
uintmax_t
fetchvall(const unsigned char *p, int size)
{
    int i;
    uintmax_t v;
    
    for (v = 0, i = 0; i < size; i++)
        v = (v << 8) + p[i];
    return(v);
}

/**
 * Find the entry trailer associated with entry i of the btree node.
 *
 * Sets node->corrupt if a problem with the node's structure is discovered.
 * @returns entry trailer pointer, or NULL if there is a problem.
 */
static struct ccn_btree_entry_trailer *
seek_trailer(struct ccn_btree_node *node, int i)
{
    struct ccn_btree_entry_trailer *t;
    unsigned last;
    unsigned ent;
    
    if (node->corrupt || node->buf->length < sizeof(struct ccn_btree_entry_trailer))
        return(NULL);
    if (node->buf->length < sizeof(struct ccn_btree_entry_trailer))
        return(NULL);
    t = (struct ccn_btree_entry_trailer *)(node->buf->buf +
        (node->buf->length - sizeof(struct ccn_btree_entry_trailer)));
    last = MYFETCH(t, index);
    ent = MYFETCH(t, entsz) * CCN_BT_SIZE_UNITS;
    if (ent < sizeof(struct ccn_btree_entry_trailer))
        return(node->corrupt = __LINE__, NULL);
    if (ent * (last + 1) >= node->buf->length)
        return(node->corrupt = __LINE__, NULL);
    if ((unsigned)i > last)
        return(NULL);
    t = (struct ccn_btree_entry_trailer *)(node->buf->buf +
        node->buf->length - (ent * (last + 1 - i)));
    if (MYFETCH(t, index) != i)
        return(node->corrupt = __LINE__, NULL);
    return(t);
}

int
ccn_btree_key_fetch(struct ccn_charbuf *dst,
                    struct ccn_btree_node *node,
                    int index)
{
    dst->length = 0;
    return(ccn_btree_key_append(dst, node, index));
}

int
ccn_btree_key_append(struct ccn_charbuf *dst,
                     struct ccn_btree_node *node,
                     int index)
{
    struct ccn_btree_entry_trailer *p = NULL;
    unsigned koff = 0;
    unsigned ksiz = 0;

    p = seek_trailer(node, index);
    if (p == NULL)
        return(-1);
    koff = MYFETCH(p, koff0);
    ksiz = MYFETCH(p, ksiz0);
    if (koff > node->buf->length)
        return(node->corrupt = __LINE__, -1);
    if (ksiz > node->buf->length - koff)
        return(node->corrupt = __LINE__, -1);
    ccn_charbuf_append(dst, node->buf->buf + koff, ksiz);
    koff = MYFETCH(p, koff1);
    ksiz = MYFETCH(p, ksiz1);
    if (koff > node->buf->length)
        return(node->corrupt = __LINE__, -1);
    if (ksiz > node->buf->length - koff)
        return(node->corrupt = __LINE__, -1);
    ccn_charbuf_append(dst, node->buf->buf + koff, ksiz);
    return(0);
}

int
ccn_btree_compare(const unsigned char *key,
                  size_t size,
                  struct ccn_btree_node *node,
                  int index)
{
    struct ccn_btree_entry_trailer *p = NULL;
    size_t cmplen;
    unsigned koff = 0;
    unsigned ksiz = 0;
    int res;
    
    p = seek_trailer(node, index);
    if (p == NULL)
        return(index < 0 ? 999 : -999);
    koff = MYFETCH(p, koff0);
    ksiz = MYFETCH(p, ksiz0);
    if (koff > node->buf->length)
        return(node->corrupt = __LINE__, -1);
    if (ksiz > node->buf->length - koff)
        return(node->corrupt = __LINE__, -1);
    cmplen = size;
    if (cmplen > ksiz)
        cmplen = ksiz;
    res = memcmp(key, node->buf->buf + koff, cmplen);
    if (res != 0)
        return(res);
    if (size < ksiz)
        return(-1);
    /* Need to do the other piece in this case, but for now assume it is empty.*/
    return(size > ksiz);
}
