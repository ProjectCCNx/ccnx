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

#define MYSTORE(p, f, v) storeval(&((p)->f[0]), sizeof((p)->f), (v))
static void
storeval(unsigned char *p, int size, unsigned v)
{
    int i;
    
    for (i = size; i > 0; i--, v >>= 8)
        p[i-1] = v;
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
 *  Minimum size of a non-empty node
 */
#define MIN_NODE_BYTES (sizeof(struct ccn_btree_node_header) + sizeof(struct ccn_btree_entry_trailer))

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
    
    if (node->corrupt || node->buf->length < MIN_NODE_BYTES)
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
    t = (struct ccn_btree_entry_trailer *)(node->buf->buf + node->buf->length
        - (ent * (last - i))
        - sizeof(struct ccn_btree_entry_trailer));
    if (MYFETCH(t, index) != i)
        return(node->corrupt = __LINE__, NULL);
    return(t);
}

static struct ccn_btree_internal_entry *
seek_internal_entry(struct ccn_btree_node *node, int i)
{
    struct ccn_btree_internal_entry *ans = NULL;
    struct ccn_btree_entry_trailer *t;
    
    t = seek_trailer(node, i);
    if (t == NULL)
        return(NULL);
    if (MYFETCH(t, entsz) * CCN_BT_SIZE_UNITS != sizeof(*ans))
        return(node->corrupt = __LINE__, NULL);
    /* awful pointer arithmetic ... */
    ans = ((struct ccn_btree_internal_entry *)(t + 1)) - 1;
    if (MYFETCH(ans, magic) != CCN_BT_INTERNAL_MAGIC)
        return(node->corrupt = __LINE__, NULL);
    return(ans);
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
    if (node->buf->length < MIN_NODE_BYTES)
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
    struct ccn_btree_node_header *hdr = NULL;

    if (node->corrupt || node->buf->length < sizeof(struct ccn_btree_node_header))
        return(-1);
    hdr = (struct ccn_btree_node_header *)(node->buf->buf);
    return(MYFETCH(hdr, level));
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
 * Search the node for the given key
 *
 * The return value is encoded as 2 * index + success; that is, a successful
 * search returns an odd number and an unsuccessful search returns an even
 * number.  In the case of an unsuccessful search, the index indicates
 * the where the item would go if it were to be inserted.
 *
 * Uses a binary search, so the keys must be sorted and unique.
 *
 * @returns CCN_BT_ENCRES(index, success) indication, or -1 for an error.
 */
int
ccn_btree_searchnode(const unsigned char *key,
                     size_t size,
                     struct ccn_btree_node *node)
{
    int i, j, mid, res;
    
    if (node->corrupt)
        return(-1);
    i = 0;
    j = ccn_btree_node_nent(node);
    while (i < j) {
        mid = (i + j) >> 1;
        res =  ccn_btree_compare(key, size, node, mid);
        // printf("node = %u, i = %d, j = %d, mid = %d, res = %d\n", node->nodeid, i, j, mid, res);
        if (res == 0)
            return(CCN_BT_ENCRES(mid, 1));
        if (res < 0)
            j = mid;
        else
            i = mid + 1;
    }
    if (i != j) {
        abort();
    }
    return(CCN_BT_ENCRES(i, 0));
}

/**
 * Do a btree lookup, starting from the root.
 */
int
ccn_btree_lookup(struct ccn_btree *btree,
                 const unsigned char *key, size_t size,
                 struct ccn_btree_node **nodep)
{
    struct ccn_btree_node *node = NULL;
    struct ccn_btree_node *child = NULL;
    struct ccn_btree_internal_entry *e = NULL;
    unsigned childid;
    unsigned parent;
    int index;
    int level;
    int newlevel;
    int srchres;
    
    node = ccn_btree_getnode(btree, 1);
    if (node == NULL || node->corrupt)
        return(-1);
    parent = node->nodeid;
    level = ccn_btree_node_level(node);
    srchres = ccn_btree_searchnode(key, size, node);
    if (srchres < 0)
        return(-1);
    while (level > 0) {
        index = CCN_BT_SRC_INDEX(srchres) - 1;
        if (index < 0)
            index = 0;
        e = seek_internal_entry(node, index);
        if (e == NULL)
            return(-1);
        childid = MYFETCH(e, child);
        child = ccn_btree_getnode(btree, childid);
        if (child == NULL)
            return(-1);
        newlevel = ccn_btree_node_level(child);
        if (newlevel != level - 1) {
            btree->errors++;
            node->corrupt = __LINE__;
            return(-1);
        }
        child->parent = node->nodeid;
        node = child;
        level = newlevel;
        srchres = ccn_btree_searchnode(key, size, node);
    }
    if (nodep != NULL)
        *nodep = node;
    return(srchres);
}

#define CCN_BTREE_MAGIC 0x53ade78
#define CCN_BTREE_VERSION 1

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

/**
 *  Initialize the btree node
 *
 * It is the caller's responsibility to be sure that the node does not
 * contain any useful information.
 *
 * @returns -1 for error, 0 for success
 */
int
ccn_btree_init_node(struct ccn_btree_node *node,
                    int level, int nodetype)
{
    struct ccn_btree_node_header *hdr = NULL;
    
    if (node->corrupt)
        return(-1);
    node->clean = 0;
    node->buf->length = 0;
    hdr = (struct ccn_btree_node_header *)ccn_charbuf_reserve(node->buf, sizeof(*hdr));
    if (hdr == NULL) return(-1);
    MYSTORE(hdr, magic, CCN_BTREE_MAGIC);
    MYSTORE(hdr, version, CCN_BTREE_VERSION);
    MYSTORE(hdr, nodetype, nodetype);
    MYSTORE(hdr, level, level);
    MYSTORE(hdr, extbytes, 0);
    node->buf->length += sizeof(*hdr);
    return(0);
}

#define CCN_BTREE_MAX_NODE_BYTES (1U<<20)

/**
 * Access a btree node, creating or reading it if necessary
 *
 * Care should be taken to not store the node handle in data structures,
 * since it will become invalid when the node gets flushed from the
 * resident cache.
 *
 * @returns node handle
 */
struct ccn_btree_node *
ccn_btree_getnode(struct ccn_btree *bt, unsigned nodeid)
{
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct ccn_btree_node *node = NULL;
    int res;

    if (bt->magic != CCN_BTREE_MAGIC)
        abort();
    hashtb_start(bt->resident, e);
    res = hashtb_seek(e, &nodeid, sizeof(nodeid), 0);
    node = e->data;
    if (res == HT_NEW_ENTRY) {
        node->nodeid = nodeid;
        node->buf = ccn_charbuf_create();
        if (node->buf == NULL) {
            bt->errors++;
            node->corrupt = __LINE__;
        }
        if (bt->io != NULL) {
            res = bt->io->btopen(bt->io, node);
            if (res < 0) {
                bt->errors++;
                node->corrupt = __LINE__;
            }
            else {
                res = bt->io->btread(bt->io, node, CCN_BTREE_MAX_NODE_BYTES);
                if (res < 0)
                    bt->errors++;
                else
                    node->clean = node->buf->length;
            }
        }
    }
    if (node != NULL && node->nodeid != nodeid)
        abort();
    hashtb_end(e);
    return(node);
}

/**
 * Access a btree node that is already resident
 *
 * Care should be taken to not store the node handle in data structures,
 * since it will become invalid when the node gets flushed from the
 * resident cache.
 *
 * @returns node handle, or NULL if the node is not currently resident.
 */
struct ccn_btree_node *
ccn_btree_rnode(struct ccn_btree *bt, unsigned nodeid)
{
    return(hashtb_lookup(bt->resident, &nodeid, sizeof(nodeid)));
}

/**
 * Check a node for internal consistency
 *
 * Sets or clears node->corrupt as appropriate.
 * In case of success, sets the correct value for node->freelow
 * If picky, checks the order of the keys within the node as well as the basics.
 *
 * @returns old value of node->corrupt if the node looks OK, otherwise -1
 */
int
ccn_btree_chknode(struct ccn_btree_node *node, int picky)
{
    unsigned freelow = 0;
    unsigned freemax = 0;
    unsigned strbase = sizeof(struct ccn_btree_node_header);
    struct ccn_btree_node_header *hdr = NULL;
    unsigned lev = 0;
    unsigned entsz = 0;
    unsigned saved_corrupt;
    struct ccn_btree_entry_trailer *p = NULL;
    int i;
    int nent;
    unsigned koff;
    unsigned ksiz;
    
    if (node == NULL)
        return(-1);
    saved_corrupt = node->corrupt;
    node->corrupt = 0;
    if (node->buf == NULL)
        return(node->corrupt = __LINE__, -1);
    if (node->buf->length == 0)
        return(node->freelow = 0, node->corrupt = 0, 0);
    if (node->buf->length < sizeof(struct ccn_btree_node_header))
        return(node->corrupt = __LINE__, -1);
    hdr = (struct ccn_btree_node_header *)node->buf->buf;
    if (MYFETCH(hdr, magic) != CCN_BTREE_MAGIC)
        return(node->corrupt = __LINE__, -1);
    if (MYFETCH(hdr, version) != CCN_BTREE_VERSION)
        return(node->corrupt = __LINE__, -1);
    /* nodetype values are not checked at present */
    lev = MYFETCH(hdr, level);
    strbase += MYFETCH(hdr, extbytes);
    if (strbase > node->buf->length)
        return(node->corrupt = __LINE__, -1);
    if (strbase == node->buf->length)
        return(node->freelow = strbase, saved_corrupt); /* no entries */
    nent = ccn_btree_node_nent(node);
    for (i = 0; i < nent; i++) {
        unsigned e;
        p = seek_trailer(node, i);
        if (p == NULL)
            return(-1);
        e = MYFETCH(p, entsz);
        if (i == 0) {
            freemax = ((unsigned char *)p) - node->buf->buf;
            entsz = e;
        }
        if (e != entsz)
            return(node->corrupt = __LINE__, -1);
        if (MYFETCH(p, level) != lev)
            return(node->corrupt = __LINE__, -1);
        koff = MYFETCH(p, koff0);
        ksiz = MYFETCH(p, ksiz0);
        if (koff < strbase && ksiz != 0)
            return(node->corrupt = __LINE__, -1);
        if (koff > freemax)
            return(node->corrupt = __LINE__, -1);
        if (ksiz > freemax - koff)
            return(node->corrupt = __LINE__, -1);
        if (koff + ksiz > freelow)
            freelow = koff + ksiz;
        koff = MYFETCH(p, koff1);
        ksiz = MYFETCH(p, ksiz1);
        if (koff < strbase && ksiz != 0)
            return(node->corrupt = __LINE__, -1);
        if (koff > freemax)
            return(node->corrupt = __LINE__, -1);
        if (ksiz > freemax - koff)
            return(node->corrupt = __LINE__, -1);
        if (koff + ksiz > freelow)
            freelow = koff + ksiz;
    }
    if (picky) {
        abort(); // NYI
    }
    if (node->freelow != freelow)
        node->freelow = freelow; /* set a break here to check for fixups */
    return(saved_corrupt);
}

