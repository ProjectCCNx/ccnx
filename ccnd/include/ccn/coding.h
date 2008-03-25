/*
 * ccn/coding.h
 * 
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 *
 * Details of the ccn binary wire encoding
 *
 * $Id$
 */

#ifndef CCN_CODING_DEFINED
#define CCN_CODING_DEFINED

#include <stddef.h>

#define CCN_TT_BITS 3
#define CCN_TT_MASK ((1 << CCN_TT_BITS) - 1)
#define CCN_MAX_TINY ((1 << (7-CCN_TT_BITS)) - 1)
#define CCN_TT_HBIT ((unsigned char)(1 << 7))

enum ccn_tt {
    CCN_EXT,        /* starts composite extension - numval is subtype */
    CCN_TAG,        /* starts composite - numval is tagnamelen-1 */ 
    CCN_DTAG,       /* starts composite - numval is tagdict index */
    CCN_ATTR,       /* attribute - numval is attrnamelen-1, value follows */
    CCN_DATTR,      /* attribute numval is attrdict index */
    CCN_BLOB,       /* opaque binary data - numval is byte count */
    CCN_UDATA,      /* UTF-8 encoded character data - numval is byte count */
};

/* CCN_CLOSE terminates composites */
#define CCN_CLOSE ((unsigned char)(0))

enum ccn_ext_subtype {
    /* skip smallest values for now */
    CCN_PROCESSING_INSTRUCTIONS = 16, /* <?name:U value:U?> */
};

enum ccn_dtag {
    CCN_DTAG_Name = 14,
    CCN_DTAG_Component = 15,
    CCN_DTAG_Certificate = 16,
    CCN_DTAG_Collection = 17,
    CCN_DTAG_CompleteName = 18,
    CCN_DTAG_Content = 19,
    CCN_DTAG_ContentAuthenticator = 20,
    CCN_DTAG_ContentDigest = 21,
    CCN_DTAG_ContentHash = 22,
    CCN_DTAG_ContentObject = 23,
    CCN_DTAG_Count = 24,
    CCN_DTAG_Header = 25,
    CCN_DTAG_Interest = 26,
    CCN_DTAG_Key = 27,
    CCN_DTAG_KeyLocator = 28,
    CCN_DTAG_KeyName = 29,
    CCN_DTAG_Length = 30,
    CCN_DTAG_Link = 31,
    CCN_DTAG_LinkAuthenticator = 32,
    CCN_DTAG_NameComponentCount = 33,
    CCN_DTAG_PublisherID = 34,
    CCN_DTAG_PublisherKeyID = 35,
    CCN_DTAG_RootDigest = 36,
    CCN_DTAG_Signature = 37,
    CCN_DTAG_Start = 38,
    CCN_DTAG_Timestamp = 39,
    CCN_DTAG_Type = 40,
    CCN_DTAG_CCNProtocolDataUnit = 17702112,
};

struct ccn_dict_entry {
    int index;
    const char *name;
};

struct ccn_dict {
    int count;
    const struct ccn_dict_entry *dict;
};
extern const struct ccn_dict ccn_dtag_dict; /* matches enum ccn_dtag above */

enum ccn_decoder_state;

struct ccn_skeleton_decoder { /* initialize to all 0 */
    ssize_t index;
    int state;
    int tagstate;
    size_t numval;
    int nest;
};

ssize_t ccn_skeleton_decode(
    struct ccn_skeleton_decoder *d,
    unsigned char p[],
    size_t n);

#endif
