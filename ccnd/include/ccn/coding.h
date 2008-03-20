
#define CCN_TT_BITS 3
#define CCN_TT_MASK ((1 << CCN_TT_BITS) - 1)
#define CCN_MAX_TINY ((1 << (7-CCN_TT_BITS)) - 1)

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
#define CCN_CLOSE ((unsigned char)(1 << 7))

enum ccn_ext_subtype {
    /* skip smallest values for now */
    CCN_PROCESSING_INSTRUCTIONS = 16, /* <?name:U value:U?> */
};

enum ccn_dtag {
    CCN_DTAG_Certificate = 3333,
    CCN_DTAG_Collection = 3334,
    CCN_DTAG_CompleteName = 3335,
    CCN_DTAG_Component = 3336,
    CCN_DTAG_Content = 3337,
    CCN_DTAG_ContentAuthenticator = 3338,
    CCN_DTAG_ContentDigest = 3339,
    CCN_DTAG_ContentHash = 3340,
    CCN_DTAG_ContentObject = 3341,
    CCN_DTAG_Count = 3342,
    CCN_DTAG_Header = 3343,
    CCN_DTAG_Interest = 3344,
    CCN_DTAG_Key = 3345,
    CCN_DTAG_KeyLocator = 3346,
    CCN_DTAG_KeyName = 3347,
    CCN_DTAG_Length = 3348,
    CCN_DTAG_Link = 3349,
    CCN_DTAG_LinkAuthenticator = 3350,
    CCN_DTAG_Name = 3351,
    CCN_DTAG_NameComponentCount = 3352,
    CCN_DTAG_PublisherID = 3353,
    CCN_DTAG_PublisherKeyID = 3354,
    CCN_DTAG_RootDigest = 3355,
    CCN_DTAG_Signature = 3356,
    CCN_DTAG_Start = 3357,
    CCN_DTAG_Timestamp = 3358,
    CCN_DTAG_Type = 3359,
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
