

#define CCN_TT_BITS 4
#define CCN_TT_MASK ((1 << CCN_TT_BITS) - 1)
#define CCN_MAX_TINY ((1 << (7-CCN_TT_BITS)) - 1)

enum ccn_tt {
    CCN_BUILTIN,    /* predefined builtin encoding - numval is vocab index */
    CCN_TAG,        /* starts composite - numval is tagnamelen-1 */ 
    CCN_ATTR,       /* starts attribute - numval is attrnamelen-1 */
    CCN_INTVAL,     /* non-negative integer of any magnitude - numval is value */
    CCN_BLOB,       /* opaque binary data - numval is byte count */
    CCN_UDATA,      /* UTF-8 encoded character data - numval is byte count */
};

#define CCN_CLOSE ((unsigned char)(1 << 7))

enum ccn_vocab {
    CCN_UNKNOWN_BUILTIN = -2,
    CCN_NO_SCHEMA = -1,
    CCN_PROCESSING_INSTRUCTIONS = 16, /* <?name:U value:U?> */
    
};