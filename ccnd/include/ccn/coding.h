
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

struct ccn_schema_node;
struct ccn_schema_data {
    enum ccn_tt tt;
    const unsigned char *ident; /* UTF-8 nul-terminated */
    int code;
    struct ccn_schema_node *schema;
};

enum ccn_schema_node_type {
    CCN_SCHEMA_TERMINAL,
    CCN_SCHEMA_NONTERMINAL,
    CCN_SCHEMA_ALT,
    CCN_SCHEMA_SEQ,
    CCN_SCHEMA_LABEL,
};

struct ccn_schema_node {
    enum ccn_schema_node_type type;
    struct ccn_schema_data *data;
    struct ccn_schema_node *left;
    struct ccn_schema_node *right;
};

struct ccn_schema_node *ccn_build_schemata(void);
void ccn_print_schema(struct ccn_schema_node *s);
