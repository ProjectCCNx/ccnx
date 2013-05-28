/**
 * Flattened representation of a name
 */
/* Part of the CCNx C Library.
 *
 * Copyright (C) 2011-2013 Palo Alto Research Center, Inc.
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

#include <string.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/flatname.h>
#include <ccn/uri.h>

/**
 *  Compare flatnames a and b
 *
 * @returns negative, 0, or positive if a < b, a == b, a > b, respectively.
 * The special return value CCN_STRICT_PREFIX means a < b and a is also a prefix of b.
 * Similarly CCN_STRICT_REV_PREFIX means b is a strict prefix of a.
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
        return(CCN_STRICT_PREFIX);
    else if (al == bl)
        return(0);
    else
        return(CCN_STRICT_REV_PREFIX);
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
