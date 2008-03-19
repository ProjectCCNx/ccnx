#include <fcntl.h>
#include <limits.h>
#include <stddef.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <ccn/charbuf.h>
#include <ccn/coding.h>

#define ARRAY_N(arr) (sizeof(arr)/sizeof(arr[0]))
static const struct ccn_dict_entry ccn_tagdict[] = {
    {CCN_DTAG_Certificate, "Certificate"},
    {CCN_DTAG_Collection, "Collection"},
    {CCN_DTAG_CompleteName, "CompleteName"},
    {CCN_DTAG_Component, "Component"},
    {CCN_DTAG_Content, "Content"},
    {CCN_DTAG_ContentAuthenticator, "ContentAuthenticator"},
    {CCN_DTAG_ContentDigest, "ContentDigest"},
    {CCN_DTAG_ContentHash, "ContentHash"},
    {CCN_DTAG_ContentObject, "ContentObject"},
    {CCN_DTAG_Count, "Count"},
    {CCN_DTAG_Header, "Header"},
    {CCN_DTAG_Interest, "Interest"},
    {CCN_DTAG_Key, "Key"},
    {CCN_DTAG_KeyLocator, "KeyLocator"},
    {CCN_DTAG_KeyName, "KeyName"},
    {CCN_DTAG_Length, "Length"},
    {CCN_DTAG_Link, "Link"},
    {CCN_DTAG_LinkAuthenticator, "LinkAuthenticator"},
    {CCN_DTAG_Name, "Name"},
    {CCN_DTAG_NameComponentCount, "NameComponentCount"},
    {CCN_DTAG_PublisherID, "PublisherID"},
    {CCN_DTAG_PublisherKeyID, "PublisherKeyID"},
    {CCN_DTAG_RootDigest, "RootDigest"},
    {CCN_DTAG_Signature, "Signature"},
    {CCN_DTAG_Start, "Start"},
    {CCN_DTAG_Timestamp, "Timestamp"},
    {CCN_DTAG_Type, "Type"},
};

const struct ccn_dict ccn_dtag_dict = {ARRAY_N(ccn_tagdict), ccn_tagdict};

struct cons;
struct cons {
    void *car;
    struct cons *cdr;
};

static struct cons *
last_cdr(struct cons *list)
{
    if (list != NULL) {
        while (list->cdr != NULL)
            list = list->cdr;
    }
    return (list);
}

static int
memq(struct cons *list, void *elt)
{
    for (; list != NULL; list = list->cdr) {
        if (elt == list->car)
            return(1);
    }
    return(0);
}

static struct cons *
cons(void *car, struct cons *cdr) {
    struct cons *l;
    l = calloc(1, sizeof(*l));
    l->car = car;
    l->cdr = cdr;
    return(l);
}

static void
print_schema(struct ccn_schema_node *s, enum ccn_schema_node_type container, struct cons *w)
{
    if (s == NULL) { printf("<>"); return; }
    switch (s->type) {
        case CCN_SCHEMA_LABEL:
            if (s->data == NULL || s->data->ident == NULL) {
                printf("<?!!>\n");
                break;
            }
            printf("%s", s->data->ident);
            if (s->data->code >= 0) {
                printf("[%d]", s->data->code);
            }
            printf(" ::= ");
            if (s->data->tt == CCN_TAG) {
                printf("<%s> ", s->data->ident);
            }
            print_schema(s->right, CCN_SCHEMA_SEQ, w);
            if (s->data->tt == CCN_TAG) {
                printf(" </%s>", s->data->ident);
            }
            printf("\n");
            break;
        case CCN_SCHEMA_TERMINAL:
            if (s->data == NULL || s->data->ident == NULL) {
                printf("'?!!'");
                break;
            }
            printf("'%s'", s->data->ident);
            break;
        case CCN_SCHEMA_NONTERMINAL:
            if (s->data == NULL || s->data->ident == NULL) {
                printf("<?!>");
                break;
            }
            printf("%s", s->data->ident);
            if (s->data->schema != NULL && !memq(w, s->data)) {
                last_cdr(w)->cdr = cons(s->data, NULL);
            }
            break;
        case CCN_SCHEMA_ALT:
            if (container < CCN_SCHEMA_ALT)
                printf("(");
            print_schema(s->left, CCN_SCHEMA_ALT, w);
            printf(" | ");
            print_schema(s->right, CCN_SCHEMA_ALT, w);
            if (container < CCN_SCHEMA_ALT)
                printf(")");
            break;
        case CCN_SCHEMA_SEQ:
            if (container < CCN_SCHEMA_SEQ)
                printf("(");
            print_schema(s->left, CCN_SCHEMA_SEQ, w);
            printf(" ");
            print_schema(s->right, CCN_SCHEMA_SEQ, w);
            if (container < CCN_SCHEMA_SEQ)
                printf(")");
            break;
        default: printf("<?>");
    }
}

void
ccn_print_schema(struct ccn_schema_node *s) {
    struct cons *w = cons(s == NULL ? NULL : s->data, NULL); /* work list */
    struct cons *tail;
    struct ccn_schema_data *d;
    print_schema(s, CCN_SCHEMA_LABEL, w);
    if (s->type == CCN_SCHEMA_LABEL) {
        for (tail = w->cdr; tail != NULL; tail = tail->cdr) {
            d = tail->car;
            print_schema(d->schema, CCN_SCHEMA_LABEL, w);
        }
    }
    for (; w != NULL; w = tail) {
        tail = w->cdr;
        free(w);
    }
}

struct ccn_schema_node *
ccn_schema_define(struct ccn_schema_node *defs, const char *ident, int code)
{
    struct ccn_schema_node *s;
    if (ident == NULL)
        return(NULL);
    if (defs != NULL) {
        for (s = defs; s != NULL; s = s->left) {
            if (s->type != CCN_SCHEMA_LABEL || s->data == NULL)
                return(NULL); /* bad defs */
            if (0 == strcmp(ident, (const void *)(s->data->ident)))
                return(NULL); /* duplicate name */
            if (code >= 0 && code == s->data->code)
                return(NULL); /* duplicate code */
        }
    }
    s = calloc(1, sizeof(*s));
    s->type = CCN_SCHEMA_LABEL;
    s->data = calloc(1, sizeof(*s->data));
    s->data->ident = (const void *)(strdup(ident));
    s->data->schema = s;
    s->data->code = code;
    if (defs != NULL)
        defs->left = s; /* use left link to link defs together */
    return(s);
}
struct ccn_schema_node *
ccn_schema_define_elt(struct ccn_schema_node *defs, const char *ident, int code)
{
    struct ccn_schema_node *s = ccn_schema_define(defs, ident, code);
    if (s != NULL)
        s->data->tt = CCN_TAG;
    return(s);
}

struct ccn_schema_node *
ccn_schema_nonterminal(struct ccn_schema_node *label)
{
    struct ccn_schema_node *s;
    if (label == NULL || label->type != CCN_SCHEMA_LABEL ||
          label->data == NULL || label->data->schema != label)
        return(NULL);
    s = calloc(1, sizeof(*s));
    s->type = CCN_SCHEMA_NONTERMINAL;
    s->data = label->data;
    return(s);
}

struct ccn_schema_node *
ccn_schema_sanitize(struct ccn_schema_node *s) {
    if (s != NULL && s->type == CCN_SCHEMA_LABEL)
        s = ccn_schema_nonterminal(s);
    return(s);
}

struct ccn_schema_node *
ccn_schema_alt(struct ccn_schema_node *left, struct ccn_schema_node *right)
{
    struct ccn_schema_node *s;
    s = calloc(1, sizeof(*s));
    s->type = CCN_SCHEMA_ALT;
    s->left = ccn_schema_sanitize(left);
    s->right = ccn_schema_sanitize(right);
    return(s);
}

struct ccn_schema_node *
ccn_schema_seq(struct ccn_schema_node *left, struct ccn_schema_node *right)
{
    struct ccn_schema_node *s;
    left = ccn_schema_sanitize(left);
    right = ccn_schema_sanitize(right);
    if (left == NULL)
        return(right);
    if (right == NULL)
        return(left);
    s = calloc(1, sizeof(*s));
    s->type = CCN_SCHEMA_SEQ;
    s->left = left;
    s->right = right;
    return(s);
}

struct ccn_schema_node *
ccn_build_schemata(void) {
    struct ccn_schema_node *goal = ccn_schema_define(NULL, "CCN", -1);
    struct ccn_schema_node *Mapping = ccn_schema_define_elt(goal, "Mapping", 1);
    struct ccn_schema_node *Name = ccn_schema_define_elt(goal, "Name", -1);
    struct ccn_schema_node *Component = ccn_schema_define_elt(goal, "Component", -1);
    struct ccn_schema_node *Components = ccn_schema_define(goal, "Components", -1);
    struct ccn_schema_node *Interest = ccn_schema_define_elt(goal, "Interest", 2);
    struct ccn_schema_node *BLOB = ccn_schema_define(goal, "BLOB", -1);
    struct ccn_schema_node *ContentAuthenticator = ccn_schema_define_elt(goal, "ContentAuthenticator", -1);
    struct ccn_schema_node *Content = ccn_schema_define_elt(goal, "Content", -1);
    BLOB->data->tt = CCN_BLOB;
    
    goal->right = ccn_schema_alt(Interest, Mapping);
    Mapping->right = ccn_schema_seq(Name, ccn_schema_seq(ContentAuthenticator, Content));
    Name->right = ccn_schema_sanitize(Components);
    Components->right = ccn_schema_alt(ccn_schema_seq(Component, Components), NULL);
    Interest->right = ccn_schema_seq(Name, NULL);
    Component->right = ccn_schema_sanitize(BLOB);
    Content->right = ccn_schema_sanitize(BLOB);
    return(goal);
}

int ccn_schema_test_main(void) {
    struct ccn_schema_node *goal = ccn_build_schemata();
    ccn_print_schema(goal);
    return(0);
}
