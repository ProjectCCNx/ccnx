/**
 * @file sync/SyncNode.c
 *  
 * Part of CCNx Sync.
 */
/*
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


#include "SyncNode.h"
#include "SyncRoot.h"
#include "SyncUtil.h"

#include <stdlib.h>
#include <string.h>
#include <strings.h>

extern int
SyncSetCompErr(struct SyncNodeComposite *nc, int val) {
    SyncNoteErr("setErr");
    if (nc->err >= 0) nc->err = val;
    return val;
}

extern int
SyncCheckCompErr(struct SyncNodeComposite *nc) {
    return nc->err < 0;
}

extern struct ccn_buf_decoder *
SyncInitDecoderFromOffset(struct ccn_buf_decoder *d,
                          struct SyncNodeComposite *nc,
                          ssize_t start, ssize_t stop) {
    d = SyncInitDecoderFromCharbufRange(d, nc->cb, start, stop);
    return d;
}

/**
 * Makes a decoder from an element.
 */
extern struct ccn_buf_decoder *
SyncInitDecoderFromElem(struct ccn_buf_decoder *d,
                        struct SyncNodeComposite *nc,
                        struct SyncNodeElem *ep) {
    d = SyncInitDecoderFromCharbufRange(d, nc->cb, ep->start, ep->stop);
    return d;
}


extern void
SyncNodeIncRC(struct SyncNodeComposite *nc) {
    int rc = nc->rc+1;
    if (rc > 0) nc->rc = rc;
}

extern struct SyncNodeComposite *
SyncNodeDecRC(struct SyncNodeComposite *nc) {
    int rc = nc->rc;
    if (rc > 1) {
        nc->rc = rc-1;
        return nc;
    }
    nc->rc = 0;
    SyncFreeComposite(nc);
    return NULL;
}

////////////////////////////////////////
// Routines for comparison support
////////////////////////////////////////

extern enum SyncCompareResult
SyncNodeCompareMinMax(struct SyncNodeComposite *nc, struct ccn_charbuf *name) {
    ssize_t cmp = 0;
    
    cmp = SyncCmpNames(name, nc->minName);
    if (cmp < 0) return SCR_before;
    if (cmp == 0) return SCR_min;
    
    cmp = SyncCmpNames(name, nc->maxName);
    if (cmp < 0) return SCR_inside;
    if (cmp == 0) return SCR_max;
    return SCR_after;
}


extern enum SyncCompareResult
SyncNodeCompareLeaf(struct SyncNodeComposite *nc,
                    struct SyncNodeElem *ep,
                    struct ccn_charbuf *name) {
    struct ccn_buf_decoder cmpDec;
    struct ccn_buf_decoder *cmpD = NULL;
    struct ccn_buf_decoder nameDec;
    struct ccn_buf_decoder *nameD = NULL;
    if (ep->kind & SyncElemKind_leaf) {
        // a leaf, as it should be
        cmpD = SyncInitDecoderFromOffset(&cmpDec, nc, ep->start, ep->stop);
        nameD = SyncInitDecoderFromCharbuf(&nameDec, name, 0);
        int cmp = SyncCmpNamesInner(nameD, cmpD);
        if (cmp == 0) return SCR_min;
        if (cmp == SYNC_BAD_CMP) return SCR_error;
        if (cmp < 0) return SCR_before;
        return SCR_after;
    } else {
        return SCR_inside;
    }
}

////////////////////////////////////////
// Routines for building CompositeNodes
////////////////////////////////////////

// resetComposite resets a composite node to its initial state
// except that it retains any allocated storage
extern void
SyncResetComposite(struct SyncNodeComposite *nc) {
    struct ccn_charbuf *cb = nc->cb;
    if (nc->minName != NULL) ccn_charbuf_destroy(&nc->minName);
    nc->minName = NULL;
    if (nc->maxName != NULL) ccn_charbuf_destroy(&nc->maxName);
    nc->maxName = NULL;
    if (nc->content != NULL) ccn_charbuf_destroy(&nc->content);
    nc->content = NULL;
    if (nc->hash != NULL) ccn_charbuf_destroy(&nc->hash);
    nc->hash = NULL;
    if (cb == NULL) {
        cb = ccn_charbuf_create();
        nc->cb = cb;
    }
    cb->length = 0;
    ccnb_element_begin(cb, CCN_DTAG_SyncNode);
    SyncAppendTaggedNumber(cb, CCN_DTAG_SyncVersion, SYNC_VERSION);
    ccnb_element_begin(cb, CCN_DTAG_SyncNodeElements);
    nc->longHash.pos = MAX_HASH_BYTES;
    nc->rc = 0;
    nc->refLen = 0;
    nc->err = 0;
    nc->leafCount = 0;
    nc->treeDepth = 1;
    nc->byteCount = 0;
}

// SyncAllocComposite allocates a new, empty, composite object
extern struct SyncNodeComposite *
SyncAllocComposite(struct SyncBaseStruct *base) {
    struct SyncNodeComposite *nc = NEW_STRUCT(1, SyncNodeComposite);
    nc->base = base;
    SyncResetComposite(nc);
    return nc;
}

// SyncExtendComposite extends the references section of a composite object
// with a new offset
// extending the encoding and obtaining the offset is handled separately
extern void
SyncExtendComposite(struct SyncNodeComposite *nc,
                    SyncElemKind kind,
                    ssize_t start, ssize_t stop) {
    int oldLen = nc->refLen;
    int newLen = oldLen+1;
    struct SyncNodeElem *refs = nc->refs;
    if (newLen >= nc->refLim) {
        // need to extend
        int newLim = newLen + newLen / 2 + 4;
        struct SyncNodeElem *lag = refs;
        refs = NEW_STRUCT(newLim, SyncNodeElem);
        if (lag != NULL) {
            if (oldLen > 0)
                memmove(refs, lag, oldLen*sizeof(struct SyncNodeElem));
            free(lag);
        }
        nc->refLim = newLim;
        nc->refs = refs;
    }
    refs[oldLen].kind = kind;
    refs[oldLen].start = start;
    refs[oldLen].stop = stop;
    nc->refLen = newLen;
}

extern void
SyncNodeMaintainMinMax(struct SyncNodeComposite *nc,
                       const struct ccn_charbuf *name) {
    struct ccn_charbuf *x = nc->minName;
    if (x == NULL) {
        x = ccn_charbuf_create();
        ccn_charbuf_append_charbuf(x, name);
    } else if (SyncCmpNames(name, x) < 0) {
        ccn_charbuf_reset(x);
        ccn_charbuf_append_charbuf(x, name);
    }
    nc->minName = x;
    x = nc->maxName;
    if (x == NULL) {
        x = ccn_charbuf_create();
        ccn_charbuf_append_charbuf(x, name);
    } else if (SyncCmpNames(name, x) > 0) {
        ccn_charbuf_reset(x);
        ccn_charbuf_append_charbuf(x, name);
    }
    nc->maxName = x;
}

extern void
SyncNodeAddName(struct SyncNodeComposite *nc,
                const struct ccn_charbuf *name) {
    struct ccn_charbuf *cb = nc->cb;
    ssize_t start = cb->length;
    SyncAppendElement(cb, name);
    ssize_t stop = cb->length;
    nc->leafCount++;
    SyncNodeMaintainMinMax(nc, name);
    SyncExtendComposite(nc, SyncElemKind_leaf, start, stop);
    SyncAccumHash(&nc->longHash, name);
}

extern void
SyncNodeAddNode(struct SyncNodeComposite *nc,
                struct SyncNodeComposite *node) {
    struct ccn_charbuf *cb = nc->cb;
    ssize_t start = cb->length;
    SyncNodeAppendLongHash(cb, node);
    ssize_t stop = cb->length;
    struct ccn_buf_decoder xds;
    struct ccn_buf_decoder *xd = SyncInitDecoderFromCharbufRange(&xds, cb, start, stop);
    SyncAccumHashInner(&nc->longHash, xd);
    SyncExtendComposite(nc, SyncElemKind_node, start, stop);
    unsigned nDepth = node->treeDepth+1;
    if (nDepth > nc->treeDepth) nc->treeDepth = nDepth;
    nc->byteCount = nc->byteCount + node->byteCount + node->cb->length;
    nc->leafCount = nc->leafCount + node->leafCount;
    SyncNodeMaintainMinMax(nc, node->minName);
    SyncNodeMaintainMinMax(nc, node->maxName);
}

extern int
SyncNodeAppendLongHash(struct ccn_charbuf *cb, struct SyncNodeComposite *nc) {
    int pos = nc->longHash.pos;
    int len = MAX_HASH_BYTES-pos;
    int res = -1;
    if (len > 0) res = ccnb_append_tagged_blob(cb, CCN_DTAG_SyncContentHash,
                                               nc->longHash.bytes+pos,
                                               len);
    return res;
}

// SyncEndComposite finishes up the encoding
// we can get into parsing problems for names and hashes
extern void
SyncEndComposite(struct SyncNodeComposite *nc) {
    if (!SyncCheckCompErr(nc) && nc->hash == NULL) {
        int res = 0;
        struct ccn_charbuf *cb = nc->cb;
        
        // terminate the references
        res |= ccnb_element_end(cb);
        
        // output the hash
        struct SyncLongHashStruct *hp = &nc->longHash;
        SyncNodeAppendLongHash(cb, nc);
        nc->hash = SyncLongHashToBuf(hp);
        
        // output the minName and maxName
        
        SyncAppendElement(cb, nc->minName);
        SyncAppendElement(cb, nc->maxName);
        
        res |= SyncAppendTaggedNumber(cb, CCN_DTAG_SyncNodeKind, nc->kind);
        res |= SyncAppendTaggedNumber(cb, CCN_DTAG_SyncLeafCount, nc->leafCount);
        res |= SyncAppendTaggedNumber(cb, CCN_DTAG_SyncTreeDepth, nc->treeDepth);
        res |= SyncAppendTaggedNumber(cb, CCN_DTAG_SyncByteCount, nc->byteCount);
        res |= ccnb_element_end(cb);
        if (res != 0) SyncSetCompErr(nc, -__LINE__);
    }
}

// freeComposite returns the storage for the composite object
extern void
SyncFreeComposite(struct SyncNodeComposite *nc) {
    if (nc == NULL) return;
    SyncResetComposite(nc);
    if (nc->cb != NULL)
        ccn_charbuf_destroy(&nc->cb);
    if (nc->refs != NULL) {
        free(nc->refs);
        nc->refs = NULL;
    }
    free(nc);
}

extern void
SyncWriteComposite(struct SyncNodeComposite *nc, FILE *f) {
    fwrite(nc->cb->buf, sizeof(unsigned char), nc->cb->length, f);
    fflush(f);
}

extern int
SyncParseComposite(struct SyncNodeComposite *nc, struct ccn_buf_decoder *d) {
    ssize_t startOff = d->decoder.token_index;
    unsigned char *base = ((unsigned char *) d->buf)+startOff;
    SyncResetComposite(nc);
    while (ccn_buf_match_dtag(d, CCN_DTAG_SyncNode)) {
        // the while loop is only present to let us break out early on error
        ccn_buf_advance(d);
        // first, get the version stamp
        uintmax_t vers = SyncParseUnsigned(d, CCN_DTAG_SyncVersion);
        if (SyncCheckDecodeErr(d) || vers != SYNC_VERSION) {
            SyncSetCompErr(nc, -__LINE__);
            break;
        }
        
        if (SyncCheckCompErr(nc) == 0 && ccn_buf_match_dtag(d, CCN_DTAG_SyncNodeElements)) {
            // we have a refs section
            ccn_buf_advance(d);
            
            for(;;) {
                SyncElemKind kind = SyncElemKind_node;
                ssize_t start = 0;
                if (ccn_buf_match_dtag(d, CCN_DTAG_Name)) {
                    // a name, so it's a leaf
                    start = SyncParseName(d);
                    kind = SyncElemKind_leaf;
                } else if (ccn_buf_match_dtag(d, CCN_DTAG_SyncContentHash)) {
                    // a hash, so it's a composite
                    start = SyncParseHash(d);
                } else  {
                    ccn_buf_check_close(d);
                    break;
                }
                if (SyncCheckDecodeErr(d))  {
                    SyncSetCompErr(nc, -__LINE__);
                    break;
                }
                ssize_t stop = d->decoder.token_index;
                SyncExtendComposite(nc, kind, start, stop);
            }
            
        }
        if (SyncCheckCompErr(nc)) break;
        
        if (ccn_buf_match_dtag(d, CCN_DTAG_SyncContentHash)) {
            const unsigned char * xp = NULL;
            size_t xs = 0;
            ccn_buf_advance(d);
            if (ccn_buf_match_blob(d, &xp, &xs)) {
                ccn_buf_advance(d);
                ccn_buf_check_close(d);
            } else {
                nc->longHash.pos = 0;
                SyncSetCompErr(nc, -__LINE__);
            }
            if (xp != NULL && xs > 0 && xs <= (ssize_t) MAX_HASH_BYTES) {
                // fill the long hash from the raw hash bytes
                // also fill the hash charbuf
                int pos = MAX_HASH_BYTES-xs;
                memcpy(&nc->longHash.bytes[pos], xp, xs);
                nc->longHash.pos = pos;
                nc->hash = SyncLongHashToBuf(&nc->longHash);
            } else {
                // not so good
                nc->longHash.pos = 0;
                SyncSetCompErr(nc, -__LINE__);
            }
        } else {
            // we are supposed to have the hash code here
            SyncSetCompErr(nc, -__LINE__);
            break;
        }
        
        nc->minName = SyncExtractName(d);
        if (SyncCheckDecodeErr(d)) {
            SyncSetCompErr(nc, -__LINE__);
            break;
        }
        nc->maxName = SyncExtractName(d);
        if (SyncCheckDecodeErr(d)) {
            SyncSetCompErr(nc, -__LINE__);
            break;
        }
        
        nc->kind = (SyncNodeKind) SyncParseUnsigned(d, CCN_DTAG_SyncNodeKind);
        if (SyncCheckDecodeErr(d)) {
            SyncSetCompErr(nc, -__LINE__);
            break;
        }
        nc->leafCount = SyncParseUnsigned(d, CCN_DTAG_SyncLeafCount);
        if (SyncCheckDecodeErr(d)) {
            SyncSetCompErr(nc, -__LINE__);
            break;
        }
        nc->treeDepth = SyncParseUnsigned(d, CCN_DTAG_SyncTreeDepth);
        if (SyncCheckDecodeErr(d)) {
            SyncSetCompErr(nc, -__LINE__);
            break;
        }
        nc->byteCount = SyncParseUnsigned(d, CCN_DTAG_SyncByteCount);
        if (SyncCheckDecodeErr(d)) {
            SyncSetCompErr(nc, -__LINE__);
            break;
        }
        ccn_buf_check_close(d);
        if (SyncCheckDecodeErr(d)) {
            SyncSetCompErr(nc, -__LINE__);
        }
        break;
    }
    if (!SyncCheckCompErr(nc)) {
        // copy the needed bytes of the encoding
        // (note: clobbers anything in nc->cb)
        // use d->decoder.index instead of token_index here (no token at end)
        int len = d->decoder.index - startOff;
        if (len <= 0) {
            // should NOT happen!
            SyncSetCompErr(nc, -__LINE__);
        } else {
            struct ccn_charbuf *cb = nc->cb;
            cb->length = 0;
            ccn_charbuf_reserve(cb, len);
            unsigned char *dst = cb->buf;
            memcpy(dst, base, len);
            cb->length = len;
        }
    }
    return nc->err;
}

extern struct SyncNodeComposite *
SyncNodeFromBytes(struct SyncRootStruct *root, const unsigned char *cp, size_t cs) {
    struct SyncNodeComposite *nc = SyncAllocComposite(root->base);
    struct ccn_buf_decoder ds;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&ds, cp, cs);
    int res = SyncParseComposite(nc, d);
    if (res < 0) {
        // failed, so back out of the allocations
        SyncFreeComposite(nc);
        nc = NULL;
    }
    return nc;
}

extern struct SyncNodeComposite *
SyncNodeFromParsedObject(struct SyncRootStruct *root,
                         const unsigned char *msg,
                         struct ccn_parsed_ContentObject *pco) {
    const unsigned char *cp = NULL;
    size_t cs = 0;
    int res = ccn_content_get_value(msg, pco->offset[CCN_PCO_E], pco,
                                    &cp, &cs);
    if (res >= 0 && cs > DEFAULT_HASH_BYTES) {
        // may be a node
        return SyncNodeFromBytes(root, cp, cs);
    }
    return NULL;
}

extern struct SyncNodeComposite *
SyncNodeFromInfo(struct SyncRootStruct *root,
                 struct ccn_upcall_info *info) {
    return SyncNodeFromParsedObject(root, info->content_ccnb, info->pco);
}


