#include <fcntl.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <expat.h>

#include <ccn/coding.h>
#include <ccn/charbuf.h>
#include <ccn/schemaexperiment.h>

/*
 * This is a sketch of a schema-driven recursive-descent parser,
 * prior to conversion to an explicit stack.
 */

struct pair;
struct pair {
    char *car;
    struct pair *cdr;
};

static struct pair *
cons(char *car, struct pair *cdr) {
    struct pair *l;
    l = calloc(1, sizeof(*l));
    l->car = car;
    l->cdr = cdr;
    return(l);
};

int
MatchOpenTag(const unsigned char *name, const char *thing) {
    int namelen = strlen((const char *)name);
    int thinglen = strlen(thing);
    if (namelen + 2 != thinglen)
        return 0;
    if (thing[0] != '<' || thing[thinglen-1] != '>')
        return 0;
    return (0==memcmp(name, thing+1, namelen));
}

int
MatchCloseTag(const unsigned char *name, const char *thing) {
    int namelen = strlen((const char *)name);
    int thinglen = strlen(thing);
    if (namelen + 3 != thinglen)
        return 0;
    if (thing[0] != '<' || thing[1] != '/' || thing[thinglen-1] != '>')
        return 0;
    return (0==memcmp(name, thing+2, namelen));
}

int
MatchUData(const char *thing) {
    return(thing[0] != '<');
}

struct source {
    struct pair *tail;
};

int
ccn_rd_parse(struct ccn_schema_node *s, struct source *source)
{
    int res = 0;
    struct pair *save = NULL;
    if (s == NULL)
        return (1);
    switch (s->type) {
        case CCN_SCHEMA_TERMINAL:
            abort();
            break;
        case CCN_SCHEMA_NONTERMINAL:
            return (ccn_rd_parse(s->data->schema, source));
            break;
        case CCN_SCHEMA_ALT:
            save = source->tail;
            res = ccn_rd_parse(s->left, source);
            if (res)
                return(res);
            // Backtrack a little bit
            if (save != NULL && save->cdr == source->tail)
                source->tail = save;
            if (source->tail != save)
                return(0);
            res = ccn_rd_parse(s->right, source);
            return(res);
            break;
        case CCN_SCHEMA_SEQ:
            res = ccn_rd_parse(s->left, source);
            if (res) {
                res = ccn_rd_parse(s->right, source);
            }
            return(res);
            break;
        case CCN_SCHEMA_LABEL:
            if (s->data->tt == CCN_TAG) {
                if (source->tail == NULL)
                    return(0);
                if (MatchOpenTag(s->data->ident, source->tail->car)) {
                    source->tail = source->tail->cdr;
                    res = ccn_rd_parse(s->data->schema->right, source);
                    if (!res)
                        return(res);
                    if (MatchCloseTag(s->data->ident, source->tail->car)) {
                        source->tail = source->tail->cdr;
                        return(1);
                    }
                    return(0);
                }
            }
            else if (s->data->tt == CCN_BLOB) {
                //look for base64 encoded stuff, convert to binary
                if (MatchUData(source->tail->car)) {
                    source->tail = source->tail->cdr;
                    return(1);
                }
                return(0);
            }
            else {
                return(ccn_rd_parse(s->data->schema->right, source));
            }
            break;
        default: abort();
    }
    return(res);
}

char *testdata1[] = {
    "<Interest>", 
      "<Name>", 
        "<Component>", "dGVzdA==", "</Component>", 
        "<Component>", "YnJpZ2dz", "</Component>", 
        "<Component>", "dGVzdC50eHQ=", "</Component>", 
        "<Component>",
            "AQIDBAUGBwgJCgsMDQ4PHxscHR4fLjxKXG1+Dw==",
        "</Component>", 
      "</Name>", 
    "</Interest>",
    NULL
};

struct pair *
ListFromArray(char **x) {
    if (*x == NULL)
        return(NULL);
    return(cons(*x, ListFromArray(x+1)));
}

int main (int argc, char **argv) {
    int res;
    struct source *source;
    struct ccn_schema_node *goal = ccn_build_schemata();
    char **testdata = argv + 1;
    
    ccn_print_schema(goal);
    source = calloc(1, sizeof(*source));
    if (testdata[0] == NULL) {
        fprintf(stderr, "using testdata1\n");
        testdata = testdata1;
    }
    source->tail = ListFromArray(testdata);
    res = ccn_rd_parse(goal, source);
    if (!res)
        printf("parse failed\n");
    if (source->tail != NULL)
        printf("There is leftover input: %s ...\n", source->tail->car);
    return(!res);
}
