/**
 * B-tree for indexing ccnx content objects
 */
/* Part of the CCNx C Library.
 *
 * Copyright (C) 2011-2012 Palo Alto Research Center, Inc.
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
 
#include <stdint.h>
#include <string.h>
#include <ccn/btree.h>
#include <ccn/btree_content.h>
#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/uri.h>

#ifndef MYFETCH
#define MYFETCH(p, f) ccn_btree_fetchval(&((p)->f[0]), sizeof((p)->f))
#endif

#ifndef MYSTORE
#define MYSTORE(p, f, v) ccn_btree_storeval(&((p)->f[0]), sizeof((p)->f), (v))
#endif

#ifndef MYFETCH64
#define MYFETCH64(p, f) ccn_btree_fetchval64(&((p)->f[0]), sizeof((p)->f))
#endif
static uint_least64_t
ccn_btree_fetchval64(const unsigned char *p, int size)
{
    int i;
    uint_least64_t v;
    
    for (v = 0, i = 0; i < size; i++)
        v = (v << 8) + p[i];
    return(v);
}

#ifndef MYSTORE64
#define MYSTORE64(p, f, v) ccn_btree_storeval64(&((p)->f[0]), sizeof((p)->f), (v))
#endif
static void
ccn_btree_storeval64(unsigned char *p, int size, uint_least64_t v)
{
    int i;
    
    for (i = size; i > 0; i--, v >>= 8)
        p[i-1] = v;
}

/**
 * Insert a ContentObject into a btree node
 *
 * The caller has presumably already done a lookup and found that the
 * object is not there.
 *
 * The caller is responsible for provinding a valid content parse (pc).
 *
 * The flatname buffer should hold the correct full name, including the
 * digest.
 *
 * @returns the new entry count or, -1 for error.
 */
int
ccn_btree_insert_content(struct ccn_btree_node *node, int ndx,
                         uint_least64_t cobid,
                         const unsigned char *content_object,
                         struct ccn_parsed_ContentObject *pc,
                         struct ccn_charbuf *flatname)
{
    struct ccn_btree_content_payload payload;
    struct ccn_btree_content_payload *e = &payload;
    int ncomp;
    int res;
    unsigned size;
    unsigned flags = 0;
    const unsigned char *blob = NULL;
    size_t blob_size = 0;
    
    size = pc->offset[CCN_PCO_E];
    ncomp = ccn_flatname_ncomps(flatname->buf, flatname->length);
    if (ncomp != pc->name_ncomps + 1)
        return(-1);
    memset(e, 'U', sizeof(*e));
    MYSTORE(e, magic, CCN_BT_CONTENT_MAGIC);
    MYSTORE(e, ctype, pc->type);
    MYSTORE(e, cobsz, size);
    MYSTORE(e, ncomp, ncomp);
    MYSTORE(e, flags, flags); // XXX - need to set CCN_RCFLAG_LASTBLOCK
    MYSTORE(e, ttpad, 0);
    MYSTORE(e, timex, 0);
    res = ccn_ref_tagged_BLOB(CCN_DTAG_Timestamp, content_object,
                              pc->offset[CCN_PCO_B_Timestamp],
                              pc->offset[CCN_PCO_E_Timestamp],
                              &blob, &blob_size);
    if (res < 0 || blob_size > sizeof(e->timex))
        return(-1);
    memcpy(e->timex + sizeof(e->timex) - blob_size, blob, blob_size);
    // XXX - need to set accession time. Should we pass it in?
    MYSTORE64(e, cobid, cobid);
    res = ccn_ref_tagged_BLOB(CCN_DTAG_PublisherPublicKeyDigest, content_object,
                              pc->offset[CCN_PCO_B_PublisherPublicKeyDigest],
                              pc->offset[CCN_PCO_E_PublisherPublicKeyDigest],
                              &blob, &blob_size);
    if (res < 0 || blob_size != sizeof(e->ppkdg))
        return(-1);
    memcpy(e->ppkdg, blob, sizeof(e->ppkdg));
    /* Now actually do the insert */
    res = ccn_btree_insert_entry(node, ndx,
                                 flatname->buf, flatname->length,
                                 e, sizeof(*e));
    return(res);
}

/**
 * Test for a match between the ContentObject described by a btree 
 * index entry and an Interest, assuming that it is already known that
 * there is a prefix match.
 *
 * This does not need access to the actual ContentObject, since the index
 * entry contains everything that we know to know to do the match.
 *
 * @param node                  leaf node
 * @param ndx                   index of entry within leaf node
 * @param interest_msg          ccnb-encoded Interest
 * @param pi                    corresponding parsed interest
 * @param scratch               for scratch use
 *
 * @result 1 for match, 0 for no match, -1 for error.
 */
int
ccn_btree_match_interest(struct ccn_btree_node *node, int ndx,
                         const unsigned char *interest_msg,
                         const struct ccn_parsed_interest *pi,
                         struct ccn_charbuf *scratch)
{
    const unsigned char *blob = NULL;
    const unsigned char *nextcomp = NULL;
    int i;
    int n;
    int ncomps;
    int pubidend;
    int pubidstart;
    int res;
    int rnc;
    size_t blob_size = 0;
    size_t nextcomp_size = 0;
    size_t size;
    struct ccn_btree_content_payload *e = NULL;
    unsigned char *flatname = NULL;
    
    e = ccn_btree_node_getentry(sizeof(*e), node, ndx);
    if (e == NULL || e->magic[0] != CCN_BT_CONTENT_MAGIC)
        return(-1);
    
    ncomps = MYFETCH(e, ncomp);
    if (ncomps < pi->prefix_comps + pi->min_suffix_comps)
        return(0);
    if (ncomps > pi->prefix_comps + pi->max_suffix_comps)
        return(0);
    /* Check that the publisher id matches */
    pubidstart = pi->offset[CCN_PI_B_PublisherID];
    pubidend = pi->offset[CCN_PI_E_PublisherID];
    if (pubidstart < pubidend) {
        blob_size = 0;
        ccn_ref_tagged_BLOB(CCN_DTAG_PublisherPublicKeyDigest,
                            interest_msg,
                            pubidstart, pubidend,
                            &blob, &blob_size);
        if (blob_size != sizeof(e->ppkdg))
            return(0);
        if (0 != memcmp(blob, e->ppkdg, blob_size))
            return(0);
    }
    /* Do Exclude processing if necessary */
    if (pi->offset[CCN_PI_E_Exclude] > pi->offset[CCN_PI_B_Exclude]) {
        res = ccn_btree_key_fetch(scratch, node, ndx);
        if (res < 0)
            return(-1);
        flatname = scratch->buf;
        size = scratch->length;
        nextcomp = NULL;
        nextcomp_size = 0;
        for (i = 0, n = 0; i < size; i += CCNFLATSKIP(rnc), n++) {
            rnc = ccn_flatname_next_comp(flatname + i, size - i);
            if (rnc <= 0)
                return(-1);
            if (n == pi->prefix_comps) {
                nextcomp = flatname + i + CCNFLATDELIMSZ(rnc);
                nextcomp_size = CCNFLATDATASZ(rnc);
                break;
            }
        }
        if (nextcomp == NULL)
            return(0);
        if (ccn_excluded(interest_msg + pi->offset[CCN_PI_B_Exclude],
                         (pi->offset[CCN_PI_E_Exclude] -
                          pi->offset[CCN_PI_B_Exclude]),
                         nextcomp,
                         nextcomp_size))
            return(0);
    }
    /*
     * At this point the prefix matches and exclude-by-next-component is done.
     */
    // test any other qualifiers here
    return(1);
}

/**
 *  Get cobid from btree entry.
 *
 * @returns the cobid field of the indexed entry of node, or 0 if error.
 */
uint_least64_t
ccn_btree_content_cobid(struct ccn_btree_node *node, int ndx)
{
    struct ccn_btree_content_payload *e = NULL;
    uint_least64_t ans = 0;
    
    e = ccn_btree_node_getentry(sizeof(*e), node, ndx);
    if (e != NULL)
        ans = MYFETCH64(e, cobid);
    return(ans);
}

/**
 *  Set cobid in a btree entry.
 *
 * @returns 0 for success, -1 for failure
 */
int
ccn_btree_content_set_cobid(struct ccn_btree_node *node, int ndx,
                            uint_least64_t cobid)
{
    struct ccn_btree_content_payload *e = NULL;
    ptrdiff_t dirty;
    
    e = ccn_btree_node_getentry(sizeof(*e), node, ndx);
    if (e == NULL)
        return(-1);
    MYSTORE64(e, cobid, cobid);
    dirty = (((unsigned char *)e) - node->buf->buf);
    if (dirty >= 0 && dirty < node->clean)
        node->clean = dirty;
    return(0);
}

/**
 *  Get ContentObject size from btree entry.
 *
 * @returns the cobsz field of the indexed entry of node, or -1 if error.
 */
int
ccn_btree_content_cobsz(struct ccn_btree_node *node, int ndx)
{
    struct ccn_btree_content_payload *e = NULL;
    
    e = ccn_btree_node_getentry(sizeof(*e), node, ndx);
    if (e != NULL)
        return(MYFETCH(e, cobsz));
    return(-1);
}

/**
 *  Compare flatnames a and b
 *
 * @returns negative, 0, or positive if a < b, a == b, a > b, respectively.
 * The special return value -9999 means a < b and a is also a prefix of b.
 * Similarly 9999 means b is a strict prefix of a.                              XXX should have defines for these values.
 */
int
ccn_flatname_charbuf_compare(struct ccn_charbuf *a, struct ccn_charbuf *b)
{
    return(ccn_flatname_compare(a->buf, a->length, b->buf, b->length));
}

/**
 *  Compare flatnames a and b (raw version)
 */
int
ccn_flatname_compare(const unsigned char *a, size_t al, const unsigned char *b, size_t bl)
{
    int res;

    res = memcmp(a, b, al < bl ? al : bl);
    if (res != 0)
        return(res);
    if (al < bl)
        return(-9999);
    else if (al == bl)
        return(0);
    else
        return(9999);
}


/**
 *  Append one component to a flatname
 *
 *  @returns 0, or -1 if there is an error.
 */
int
ccn_flatname_append_component(struct ccn_charbuf *dst,
                              const unsigned char *comp, size_t size)
{
    int res;
    int s;
    size_t save;
    
    if (size >= (1 << 21))
        return(-1);
    save = dst->length;
    res = 0;
    for (s = 0; size >= (1 << (s + 7)); s += 7)
        continue;
    for (; s > 0; s -= 7)
        res |= ccn_charbuf_append_value(dst, (((size >> s) & 0x7F) | 0x80), 1);
    res |= ccn_charbuf_append_value(dst, (size & 0x7F), 1);
    res |= ccn_charbuf_append(dst, comp, size);
    if (res < 0)
        dst->length = save;
    return(res);
}

/**
 *  Append Components from a ccnb-encoded Name to a flatname
 *
 *  The ccnb encoded input may be a ContentObject, Interest, Prefix,
 *  or Component instead of simply a Name.
 *  @param dst is the destination, which should hold a ccnb-encoded Name
 *  @param ccnb points to first byte of Name
 *  @param size is the number of bytes in ccnb
 *  @param skip is the number of components at the front of flatname to skip
 *  @param count is the maximum number of componebts to append, or -1 for all
 *  @returns number of appended components, or -1 if there is an error.
 */
int
ccn_flatname_append_from_ccnb(struct ccn_charbuf *dst,
                              const unsigned char *ccnb, size_t size,
                              int skip, int count)
{
    int ans = 0;
    int ncomp = 0;
    const unsigned char *comp = NULL;
    size_t compsize = 0;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder, ccnb, size);
    int checkclose = 0;
    int res;
    
    if (ccn_buf_match_dtag(d, CCN_DTAG_Interest)    ||
        ccn_buf_match_dtag(d, CCN_DTAG_ContentObject)) {
        ccn_buf_advance(d);
        if (ccn_buf_match_dtag(d, CCN_DTAG_Signature))
            ccn_buf_advance_past_element(d);
    }
    if ((ccn_buf_match_dtag(d, CCN_DTAG_Name) ||
         ccn_buf_match_dtag(d, CCN_DTAG_Prefix))) {
        checkclose = 1;
        ccn_buf_advance(d);
    }
    else if (count != 0)
        count = 1;
    while (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
        if (ans == count)
            return(ans);
        ccn_buf_advance(d);
        compsize = 0;
        if (ccn_buf_match_blob(d, &comp, &compsize))
            ccn_buf_advance(d);
        ccn_buf_check_close(d);
        if (d->decoder.state < 0)
            return(-1);
        ncomp += 1;
        if (ncomp > skip) {
            res = ccn_flatname_append_component(dst, comp, compsize);
            if (res < 0)
                return(-1);
            ans++;
        }
    }
    if (checkclose)
        ccn_buf_check_close(d);
    if (d->decoder.state < 0)
        return (-1);
    return(ans);
}

/**
 *  Convert a ccnb-encoded Name to a flatname
 *  @returns number of components, or -1 if there is an error.
 */
int
ccn_flatname_from_ccnb(struct ccn_charbuf *dst,
                       const unsigned char *ccnb, size_t size)
{
    dst->length = 0;
    return(ccn_flatname_append_from_ccnb(dst, ccnb, size, 0, -1));
}

/**
 * Parse the component delimiter from the start of a flatname
 *
 * The delimiter size is limited to 3 bytes.
 * @returns -1 for error, 0 nothing left, or compsize * 4 + delimsize
 */
int
ccn_flatname_next_comp(const unsigned char *flatname, size_t size)
{
    unsigned i, l, m;
    
    if (size == 0)
        return(0);
    if (flatname[0] == 0x80)
        return(-1); /* Must use min number of bytes. */
    m = (size < 3) ? size : 3;
    for (i = 0, l = 0; i < m && (flatname[i] & 0x80) != 0; i++)
        l = (l | (flatname[i] & 0x7F)) << 7;
    if (i >= m)
        return(-1);
    l |= flatname[i++];
    if (i + l > size)
        return(-1);
    return(l * 4 + i);
}

/**
 *  Append Components from a flatname to a ccnb-encoded Name
 *  @param dst is the destination, which should hold a ccnb-encoded Name
 *  @param flatname points to first byte of flatname
 *  @param size is the number of bytes in flatname
 *  @param skip is the number of components at the front of flatname to skip
 *  @param count is the maximum number of components to append, or -1 for all
 *  @returns number of appended components, or -1 if there is an error.
 */
int
ccn_name_append_flatname(struct ccn_charbuf *dst,
                         const unsigned char *flatname, size_t size,
                         int skip, int count)
{
    int ans;
    int compnum;
    int i;
    int rnc;
    int res;
    const unsigned char *cp;
    size_t cs;
   
    if (skip < 0)
        return(-1);
    ans = 0;
    compnum = 0;
    for (i = 0; i < size; i += CCNFLATSKIP(rnc)) {
        if (ans == count)
            return(ans);
        rnc = ccn_flatname_next_comp(flatname + i, size - i);
        if (rnc <= 0)
            return(-1);
        cp = flatname + i + CCNFLATDELIMSZ(rnc);
        cs = CCNFLATDATASZ(rnc);
        if (compnum >= skip) {
            res = ccn_name_append(dst, cp, cs);
            if (res < 0)
                return(-1);
            ans++;
        }
        compnum++;
    }
    return(ans);
}

/**
 * Like ccn_uri_append(), but accepts a flatname instead of ccnb
 */
int
ccn_uri_append_flatname(struct ccn_charbuf *uri,
                             const unsigned char *flatname, size_t size,
                             int includescheme)
{
    struct ccn_charbuf *ccnb = NULL;
    int res;
    
    ccnb = ccn_charbuf_create();
    if (ccnb == NULL)
        return(-1);
    res = ccn_name_init(ccnb);
    if (res < 0)
        goto Bail;
    res = ccn_name_append_flatname(ccnb, flatname, size, 0, -1);
    if (res < 0)
        goto Bail;
    res = ccn_uri_append(uri, ccnb->buf, ccnb->length, includescheme);
Bail:
    ccn_charbuf_destroy(&ccnb);
    return(res);
}

/**
 * Get flatname component count
 * @returns the number of name components in the flatname, or -1 if the
 *          flatname is not well-formed
 */
int
ccn_flatname_ncomps(const unsigned char *flatname, size_t size)
{
    int ans;
    int i;
    int rnc;
    
    ans = 0;
    for (i = 0; i < size; i += CCNFLATSKIP(rnc)) {
        rnc = ccn_flatname_next_comp(flatname + i, size - i);
        if (rnc <= 0)
            return(-1);
        ans++;
    }
    return(ans);
}
