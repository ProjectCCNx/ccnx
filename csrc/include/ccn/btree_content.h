/**
 * @file ccn/btree_content.h
 *
 * Storage of a content index in a btree
 */
/*
 * (Someday) Part of the CCNx C Library.
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
 
 
#ifndef CCN_BTREE_CONTENT_DEFINED
#define CCN_BTREE_CONTENT_DEFINED

#include <sys/types.h>
#include <ccn/btree.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>

/**
 *  Structure of the entry payload within a leaf node.
 */
struct ccn_btree_content_payload {
    unsigned char magic[1];     /**< CCN_BT_CONTENT_MAGIC */
    unsigned char ctype[3];     /**< Type */
    unsigned char cobsz[4];     /**< Size in bytes of ContentObject */
    unsigned char ncomp[2];     /**< number of name components */
    unsigned char flags[1];     /**< CCN_RCFLAG_* */
    unsigned char ttpad[1];     /**< Reserved until 20 Aug 4147 07:32:16 GMT */
    unsigned char timex[6];     /**< Timestamp from content object */
    unsigned char actim[6];     /**< Accession time, Timestamp format */
    unsigned char cobid[8];     /**< Where the actual ContentObject is */
    unsigned char ppkdg[32];    /**< PublisherPublicKeyDigest */
};
#define CCN_BT_CONTENT_MAGIC    0xC0
#define CCN_RCFLAG_LASTBLOCK    0x80
#define CCN_RCFLAG_STALE        0x01

/**
 *  Logical structure of the entry within a leaf node.
 */
struct ccn_btree_content_entry {
    struct ccn_btree_content_payload ce;
    struct ccn_btree_entry_trailer trailer;
};

/* Match an interest against a btree entry, assuming a prefix match. */
int ccn_btree_match_interest(struct ccn_btree_node *node, int ndx,
                             const unsigned char *interest_msg,
                             const struct ccn_parsed_interest *pi,
                             struct ccn_charbuf *scratch);

/* Insert a ContentObject into a btree node */
int ccn_btree_insert_content(struct ccn_btree_node *node, int ndx,
                             uint_least64_t cobid,
                             const unsigned char *content_object,
                             struct ccn_parsed_ContentObject *pc,
                             struct ccn_charbuf *flatname);

/* cobid accessor */
uint_least64_t ccn_btree_content_cobid(struct ccn_btree_node *node, int ndx);
int ccn_btree_content_set_cobid(struct ccn_btree_node *node, int ndx,
                                uint_least64_t cobid);
/* cobsz accessor */
int ccn_btree_content_cobsz(struct ccn_btree_node *node, int ndx);


/**
 * Flat name representation
 *
 * Within the btree-based index, the name is stored in a representation
 * different than the ccnb encoding that is used on the wire.
 * This encoding is designed so that simple lexical ordering on
 * flatname byte arrays corresponds precisely with ccn's CanonicalOrdering
 * of Names.
 *
 * In the flatname representation, the bytes that constitute each
 * Component are prepended by a length indicator that occupies one or
 * more bytes.  The high-order bit is used to mark the end of the length
 * indicator, with 0 marking the last byte. The low order 7 bits of each
 * of these bytes are concatenated together, in big endian order, to form
 * the length.
 *
 * For example:
 * 0x00                => the zero-length component
 * 0x01 0x41           => the component "A"
 * 0x7F 0xC1 ...       => a component 127 bytes long that starts with "%C1"
 * 0x81 0x00 0x39 ...  => a component 128 bytes long that starts with "9"
 * 0xff 0x3F 0x30 ...  => a component 16383 bytes long that starts with "0"
 *
 * Length indicators larger than this are possible in theory, but unlikely
 * to come up in practice. Nonetheless, we do allow 3-byte length indicators.
 */

/* Name flattening */
int ccn_flatname_append_component(struct ccn_charbuf *dst,
                                  const unsigned char *comp, size_t size);
int ccn_flatname_append_from_ccnb(struct ccn_charbuf *dst,
                                  const unsigned char *ccnb, size_t size,
                                  int skip, int count);
int ccn_flatname_from_ccnb(struct ccn_charbuf *dst,
                           const unsigned char *ccnb, size_t size);

/* Name unflattening */
int ccn_name_append_flatname(struct ccn_charbuf *dst,
                             const unsigned char *flatname, size_t size,
                             int skip, int count);
int ccn_uri_append_flatname(struct ccn_charbuf *uri,
                             const unsigned char *flatname, size_t size,
                             int includescheme);
/* Flatname accessors */
int ccn_flatname_ncomps(const unsigned char *flatname, size_t size);

/* Flatname comparison */
int ccn_flatname_charbuf_compare(struct ccn_charbuf *a, struct ccn_charbuf *b);
int ccn_flatname_compare(const unsigned char *a, size_t al,
                         const unsigned char *b, size_t bl);

/*
 * Parse the component delimiter from the start of a flatname
 * Returns -1 for error, 0 nothing left, or compsize * 4 + delimsize
 */
int ccn_flatname_next_comp(const unsigned char *flatname, size_t size);
/** Get delimiter size from return value of ccn_flatname_next_comp */
#define CCNFLATDELIMSZ(rnc) ((rnc) & 3)
/** Get data size from return value of ccn_flatname_next_comp */
#define CCNFLATDATASZ(rnc) ((rnc) >> 2)
/** Get total delimited size from return value of ccn_flatname_next_comp */
#define CCNFLATSKIP(rnc) (CCNFLATDELIMSZ(rnc) + CCNFLATDATASZ(rnc))

#endif
