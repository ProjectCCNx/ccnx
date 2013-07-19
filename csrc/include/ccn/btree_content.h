/**
 * @file ccn/btree_content.h
 *
 * Storage of a content index in a btree
 */
/*
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

#endif
