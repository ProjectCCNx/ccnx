
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
int ccn_schemaexperiment_main(void);
