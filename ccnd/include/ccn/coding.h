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

#include <sys/types.h>
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
    CCN_NO_TOKEN    /* should not occur in encoding */
};

/* CCN_CLOSE terminates composites */
#define CCN_CLOSE ((unsigned char)(0))

enum ccn_ext_subtype {
    /* skip smallest values for now */
    CCN_PROCESSING_INSTRUCTIONS = 16 /* <?name:U value:U?> */
};

enum ccn_dtag {
    CCN_DTAG_Name = 14,
    CCN_DTAG_Component = 15,
    CCN_DTAG_Certificate = 16,
    CCN_DTAG_Collection = 17,
    CCN_DTAG_CompleteName = 18,
    CCN_DTAG_Content = 19,
    CCN_DTAG_SignedInfo = 20,
    CCN_DTAG_ContentDigest = 21,
    CCN_DTAG_ContentHash = 22,
    CCN_DTAG_ContentObjectV20080711 = 23, /* Deprecated */
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
    CCN_DTAG_PublisherID = 34,  	/* Deprecated */
    CCN_DTAG_PublisherKeyID = 35,	/* Deprecated */
    CCN_DTAG_RootDigest = 36,
    CCN_DTAG_Signature = 37,
    CCN_DTAG_Start = 38,
    CCN_DTAG_Timestamp = 39,
    CCN_DTAG_Type = 40,
    CCN_DTAG_Nonce = 41,
    CCN_DTAG_Scope = 42,
    CCN_DTAG_Exclude = 43,
    CCN_DTAG_Bloom = 44,
    CCN_DTAG_BloomSeed = 45,
    CCN_DTAG_OrderPreference = 46,
    CCN_DTAG_AnswerOriginKind = 47,
    CCN_DTAG_MatchFirstAvailableDescendant = 48,/* Obsolete */
    CCN_DTAG_MatchLastAvailableDescendant = 49,	/* Obsolete */
    CCN_DTAG_MatchNextAvailableSibling = 50,	/* Obsolete */
    CCN_DTAG_MatchLastAvailableSibling = 51,	/* Obsolete */
    CCN_DTAG_MatchEntirePrefix = 52,        	/* Obsolete */
    CCN_DTAG_Witness = 53,
    CCN_DTAG_SignatureBits = 54,
    CCN_DTAG_DigestAlgorithm = 55,
    CCN_DTAG_BlockSize = 56,
    CCN_DTAG_AdditionalNameComponents = 57,
    CCN_DTAG_FreshnessSeconds = 58,
    CCN_DTAG_FinalBlockID = 59,
    CCN_DTAG_PublisherPublicKeyDigest = 60,
    CCN_DTAG_PublisherCertificateDigest = 61,
    CCN_DTAG_PublisherIssuerKeyDigest = 62,
    CCN_DTAG_PublisherIssuerCertificateDigest = 63,
    CCN_DTAG_ContentObject = 64,	/* V20080415 */
    CCN_DTAG_CCNProtocolDataUnit = 17702112,
    CCN_DTAG_ExperimentalResponseFilter = 23204960
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

struct ccn_skeleton_decoder { /* initialize to all 0 */
    ssize_t index;          /* Number of bytes processed */
    int state;              /* Decoder state */
    int nest;               /* Element nesting */
    size_t numval;          /* Current numval, meaning depends on state */
    size_t token_index;     /* Starting index of most-recent token */
    size_t element_index;   /* Starting index of most-recent element */
};

/*
 * The decoder state is one of these, possibly with some
 * additional bits set for internal use.  A complete parse
 * ends up in state 0 or an error state.
 */
enum ccn_decoder_state {
    CCN_DSTATE_INITIAL = 0,
    CCN_DSTATE_NEWTOKEN,
    CCN_DSTATE_NUMVAL,
    CCN_DSTATE_UDATA,
    CCN_DSTATE_TAGNAME,
    CCN_DSTATE_ATTRNAME,
    CCN_DSTATE_BLOB,
    /* All error states are negative */
    CCN_DSTATE_ERR_OVERFLOW = -1,
    CCN_DSTATE_ERR_ATTR     = -2,       
    CCN_DSTATE_ERR_CODING   = -3,
    CCN_DSTATE_ERR_NEST     = -4, 
    CCN_DSTATE_ERR_BUG      = -5
};

/*
 * If the CCN_DSTATE_PAUSE bit is set in the decoder state,
 * the decoder will return just after recognizing each token.
 * In this instance, use CCN_GET_TT_FROM_DSTATE to extract
 * the token type from the decoder state;
 * CCN_CLOSE will be reported as CCN_NO_TOKEN.
 * The pause bit persists, so the end test should take that into account
 * by using the CCN_FINAL_DSTATE macro instead of testing for state 0.
 */
#define CCN_DSTATE_PAUSE (1 << 15)
#define CCN_GET_TT_FROM_DSTATE(state) (CCN_TT_MASK & ((state) >> 16))
#define CCN_FINAL_DSTATE(state) (((state) & (CCN_DSTATE_PAUSE-1)) == 0)

ssize_t ccn_skeleton_decode(
    struct ccn_skeleton_decoder *d,
    const unsigned char *p,
    size_t n);

#endif
