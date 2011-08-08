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
#include <stdlib.h>
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

/**
 * Number of entries
 * @returns number of entries within the node, or -1 for error
 */
int
ccn_btree_node_nent(struct ccn_btree_node *node)
{
    struct ccn_btree_entry_trailer *t;

    if (node->corrupt)
        return(-1);
    if (node->buf->length < sizeof(struct ccn_btree_entry_trailer))
        return(0);
    t = (struct ccn_btree_entry_trailer *)(node->buf->buf +
        (node->buf->length - sizeof(struct ccn_btree_entry_trailer)));
    return(MYFETCH(t, index) + 1);
}

/** 
 * Node level (leaves are at level 0)
 * @returns the node level, or -1 for error
 */
int ccn_btree_node_level(struct ccn_btree_node *node)
{
    struct ccn_btree_entry_trailer *t;

    if (node->corrupt)
        return(-1);
    if (node->buf->length < sizeof(struct ccn_btree_entry_trailer))
        return(0);
    t = (struct ccn_btree_entry_trailer *)(node->buf->buf +
        (node->buf->length - sizeof(struct ccn_btree_entry_trailer)));
    return(MYFETCH(t, level));
}

/**
 * Fetch the indexed key to dst
 * @returns -1 in case of error
 */
int
ccn_btree_key_fetch(struct ccn_charbuf *dst,
                    struct ccn_btree_node *node,
                    int index)
{
    dst->length = 0;
    return(ccn_btree_key_append(dst, node, index));
}

/**
 * Append the indexed key to dst
 * @returns -1 in case of error
 */
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

/**
 * Compare given key with the key in the indexed entry of the node
 *
 * The comparison is a standard lexicographic one on unsigned bytes; that is,
 * there is no assumption of what the bytes actually encode.
 * @returns negative, zero, or positive to indicate less, equal, or greater
 */
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
    if (res != 0 || size == ksiz)
        return(res);
    if (size < ksiz)
        return(-1);
    /* Compare the other part of the key */
    key += cmplen;
    size -= cmplen;
    koff = MYFETCH(p, koff1);
    ksiz = MYFETCH(p, ksiz1);
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
    return(size > ksiz);
}

// #include <stdio.h>
/**
 * Search for the first entry in the range [i..j) that compares >= the
 * given key.
 *
 * Assumes the keys in the node are distinct and in increasing order.
 * Uses a binary search.
 */
int
ccn_btree_searchnode(const unsigned char *key,
                     size_t size,
                     struct ccn_btree_node *node,
                     int i, int j)
{
    int res;
    int mid;
    
    if (node->corrupt)
        return(-1);
    while (i < j) {
        mid = (i + j) >> 1;
        res =  ccn_btree_compare(key, size, node, mid);
        // printf("node = %u, mid = %d, res = %d\n", node->nodeid, mid, res);
        if (res == 0)
            return(mid);
        if (res < 0)
            j = mid;
        else
            i = mid + 1;
    }
    return(i);
}

#define CCN_BTREE_MAGIC 0x53ade78

static void
finalize_node(struct hashtb_enumerator *e)
{
    struct ccn_btree *btree = hashtb_get_param(e->ht, NULL);
    struct ccn_btree_node *node = e->data;
    int res = 0;
    
    if (btree->magic != CCN_BTREE_MAGIC)
        abort();
    if (node->iodata != NULL && btree->io != NULL) {
        struct ccn_btree_io *io = btree->io;
        if (node->corrupt == 0)
            res = io->btwrite(io, node);
        else
            res = -1;
        node->clean = node->buf->length;
        res |= io->btclose(io, node);
        ccn_charbuf_destroy(&node->buf);
        if (res < 0)
            btree->errors += 1;
    }
}

/**
 * Create a new btree handle, not attached to any external files
 * @returns new handle, or NULL in case of error.
 */
struct ccn_btree *
ccn_btree_create(void)
{
    struct ccn_btree *ans;
    struct hashtb_param param = {0};
    
    ans = calloc(1, sizeof(*ans));
    if (ans != NULL) {
        ans->magic = CCN_BTREE_MAGIC;
        param.finalize_data = ans;
        param.finalize = &finalize_node;
        ans->resident = hashtb_create(sizeof(struct ccn_btree_node), &param);
        if (ans->resident == NULL) {
            free(ans);
            return(NULL);
        }
        ans->errors = 0;
        ans->io = NULL;
        ans->nextnodeid = 0;
    }
    return(ans);
}

/**
 * Destroys a btree handle, shutting things down cleanly.
 * @returns a negative value in case of error.
 */
int
ccn_btree_destroy(struct ccn_btree **pbt)
{
    struct ccn_btree *bt = *pbt;
    int res = 0;
    
    if (bt == NULL)
        return(0);
    *pbt = NULL;
    if (bt->magic != CCN_BTREE_MAGIC)
        abort();
    hashtb_destroy(&bt->resident);
    if (bt->errors != 0)
        res = -1;
    if (bt->io != NULL)
        res |= bt->io->btdestroy(&bt->io);
    free(bt);
    return(res);
}
