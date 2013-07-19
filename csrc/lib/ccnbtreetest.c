/**
 * @file ccnbtreetest.c
 * 
 * Unit tests for btree functions
 *
 */
/*
 * Copyright (C) 2011-2013 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */
 
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <ccn/btree.h>
#include <ccn/btree_content.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/flatname.h>
#include <ccn/indexbuf.h>
#include <ccn/hashtb.h>
#include <ccn/uri.h>

#define FAILIF(cond) do if (cond) fatal(__func__, __LINE__); while (0)
#define CHKSYS(res) FAILIF((res) == -1)
#define CHKPTR(p)   FAILIF((p) == NULL)

static void
fatal(const char *fn, int lineno)
{
    char buf[80] = {0};
    snprintf(buf, sizeof(buf)-1, "OOPS - function %s, line %d", fn, lineno);
    perror(buf);
    exit(1);
}

/**
 * Use standard mkdtemp() to create a subdirectory of the
 * current working directory, and set the TEST_DIRECTORY environment
 * variable with its name.
 */
static int
test_directory_creation(void)
{
    int res;
    struct ccn_charbuf *dirbuf;
    char *temp;
    
    dirbuf = ccn_charbuf_create();
    CHKPTR(dirbuf);
    res = ccn_charbuf_putf(dirbuf, "./%s", "_bt_XXXXXX");
    CHKSYS(res);
    temp = mkdtemp(ccn_charbuf_as_string(dirbuf));
    CHKPTR(temp);
    res = ccn_charbuf_putf(dirbuf, "/%s", "_test");
    CHKSYS(res);
    res = mkdir(ccn_charbuf_as_string(dirbuf), 0777);
    CHKSYS(res);
    printf("Created directory %s\n", ccn_charbuf_as_string(dirbuf));
    setenv("TEST_DIRECTORY", ccn_charbuf_as_string(dirbuf), 1);
    ccn_charbuf_destroy(&dirbuf);
    return(res);
}

/**
 * Basic tests of ccn_btree_io_from_directory() and its methods.
 *
 * Assumes TEST_DIRECTORY has been set.
 */
static int
test_btree_io(void)
{
    int res;
    struct ccn_btree_node nodespace = {0};
    struct ccn_btree_node *node = &nodespace;
    struct ccn_btree_io *io = NULL;

    /* Open it up. */
    io = ccn_btree_io_from_directory(getenv("TEST_DIRECTORY"), NULL);
    CHKPTR(io);
    node->buf = ccn_charbuf_create();
    CHKPTR(node->buf);
    node->nodeid = 12345;
    res = io->btopen(io, node);
    CHKSYS(res);
    FAILIF(node->iodata == NULL);
    ccn_charbuf_putf(node->buf, "smoke");
    res = io->btwrite(io, node);
    CHKSYS(res);
    node->buf->length = 0;
    ccn_charbuf_putf(node->buf, "garbage");
    res = io->btread(io, node, 500000);
    CHKSYS(res);
    FAILIF(node->buf->length != 5);
    FAILIF(node->buf->limit > 10000);
    node->clean = 5;
    ccn_charbuf_putf(node->buf, "r");
    res = io->btwrite(io, node);
    CHKSYS(res);
    node->buf->length--;
    ccn_charbuf_putf(node->buf, "d");
    res = io->btread(io, node, 1000);
    CHKSYS(res);
    FAILIF(0 != strcmp("smoker", ccn_charbuf_as_string(node->buf)));
    node->buf->length--;
    res = io->btwrite(io, node);
    CHKSYS(res);
    node->buf->length = 0;
    ccn_charbuf_putf(node->buf, "garbage");
    node->clean = 0;
    res = io->btread(io, node, 1000);
    CHKSYS(res);
    res = io->btclose(io, node);
    CHKSYS(res);
    FAILIF(node->iodata != NULL);
    FAILIF(0 != strcmp("smoke", ccn_charbuf_as_string(node->buf)));
    res = io->btdestroy(&io);
    CHKSYS(res);
    ccn_charbuf_destroy(&node->buf);
    return(res);
}

/**
 * Helper for test_structure_sizes()
 *
 * Prints out the size of the struct
 */
static void
check_structure_size(const char *what, int sz)
{
    printf("%s size is %d bytes\n", what, sz);
    errno=EINVAL;
    FAILIF(sz % CCN_BT_SIZE_UNITS != 0);
}

/**
 * Helper for test_structure_sizes()
 *
 * Prints the size of important structures, and make sure that
 * they are mutiples of CCN_BT_SIZE_UNITS.
 */
int
test_structure_sizes(void)
{
    check_structure_size("ccn_btree_entry_trailer",
            sizeof(struct ccn_btree_entry_trailer));
    check_structure_size("ccn_btree_internal_entry",
            sizeof(struct ccn_btree_internal_entry));
    check_structure_size("ccn_btree_content_entry",
            sizeof(struct ccn_btree_content_entry));
    return(0);
}

/**
 * Test that the lockfile works.
 */
int
test_btree_lockfile(void)
{
    int res;
    struct ccn_btree_io *io = NULL;
    struct ccn_btree_io *io2 = NULL;

    io = ccn_btree_io_from_directory(getenv("TEST_DIRECTORY"), NULL);
    CHKPTR(io);
    /* Make sure the locking works */
    errno = 0;
    io2 = ccn_btree_io_from_directory(getenv("TEST_DIRECTORY"), NULL);
    FAILIF(io2 != NULL || errno == 0);
    errno=EINVAL;
    res = io->btdestroy(&io);
    CHKSYS(res);
    FAILIF(io != NULL);
    return(res);
}

struct entry_example {
    unsigned char p[CCN_BT_SIZE_UNITS];
    struct ccn_btree_entry_trailer t;
};

struct node_example {
    struct ccn_btree_node_header hdr;
    unsigned char ss[64];
    struct entry_example e[3];
} ex1 = {
    {{0x05, 0x3a, 0xde, 0x78}, {1}},
    "goodstuff<------ WASTE---------->d<----><-------------- free -->",
    //                                 beauty
    {
        {.t={.koff0={0,0,0,33+8}, .ksiz0={0,1}, .entdx={0,0}, .entsz={3}}}, // "d"
        {.t={.koff0={0,0,0,0+8}, .ksiz0={0,9}, .entdx={0,1}, .entsz={3}}}, // "goodstuff"
        {.t={.koff0={0,0,0,2+8}, .ksiz0={0,2}, .entdx={0,2}, .entsz={3},
            .koff1={0,0,0,3+8}, .ksiz1={0,1}}}, // "odd"
    }
};

struct node_example ex2 = {
    {{0x05, 0x3a, 0xde, 0x78}, {1}},
    "struthiomimus",
    {
        {.t={.koff1={0,0,0,2+8}, .ksiz1={0,3}, .entdx={0,0}, .entsz={3}}}, // "rut"
        {.t={.koff0={0,0,0,0+8}, .ksiz0={0,5}, .entdx={0,1}, .entsz={3}}}, // "strut"
        {.t={.koff0={0,0,0,1+8}, .ksiz0={0,5}, .entdx={0,2}, .entsz={3}}}, // "truth"
    }
};

struct root_example {
    struct ccn_btree_node_header hdr;
    unsigned char ss[CCN_BT_SIZE_UNITS];
    struct ccn_btree_internal_entry e[2];
} rootex1 = {
    {{0x05, 0x3a, 0xde, 0x78}, {1}, {'R'}, {1}},
    "ru",
    {
        {   {.magic={0xcc}, .child={0,0,0,2}}, // ex1 at nodeid 2 as 1st child
            {.entdx={0,0}, .level={1}, .entsz={3}}}, 
        {   {.magic={0xcc}, .child={0,0,0,3}}, // ex2 at nodeid 3 as 2nd child
            {.koff1={0,0,0,0+8}, .ksiz1={0,2}, 
                .entdx={0,1}, .level={1}, .entsz={3}}},
    }
};

int
test_btree_chknode(void)
{
    int res;
    struct ccn_btree_node *node = NULL;
    struct node_example *ex = NULL;
    
    node = calloc(1, sizeof(*node));
    CHKPTR(node);
    node->buf = ccn_charbuf_create();
    CHKPTR(node->buf);
    ccn_charbuf_append(node->buf, &ex1, sizeof(ex1));
    res = ccn_btree_chknode(node);
    CHKSYS(res);
    FAILIF(node->corrupt != 0);
    FAILIF(node->freelow != 8 + 34); // header plus goodstuff<- ... ->d
    ex = (void *)node->buf->buf;
    ex->e[1].t.ksiz0[1] = 100; /* ding the size in entry 1 */
    res = ccn_btree_chknode(node);
    FAILIF(res != -1);
    FAILIF(node->corrupt == 0);
    ccn_charbuf_destroy(&node->buf);
    free(node);
    return(0);
}

int
test_btree_key_fetch(void)
{
    int i;
    int res;
    struct ccn_charbuf *cb = NULL;
    struct ccn_btree_node *node = NULL;
    struct node_example ex = ex1;
    
    const char *expect[3] = { "d", "goodstuff", "odd" };
    
    node = calloc(1, sizeof(*node));
    CHKPTR(node);
    node->buf = ccn_charbuf_create();
    CHKPTR(node->buf);
    ccn_charbuf_append(node->buf, &ex, sizeof(ex));
    
    cb = ccn_charbuf_create();
    
    for (i = 0; i < 3; i++) {
        res = ccn_btree_key_fetch(cb, node, i);
        CHKSYS(res);
        FAILIF(cb->length != strlen(expect[i]));
        FAILIF(0 != memcmp(cb->buf, expect[i], cb->length));
    }
    
    res = ccn_btree_key_fetch(cb, node, i); /* fetch past end */
    FAILIF(res != -1);
    res = ccn_btree_key_fetch(cb, node, -1); /* fetch before start */
    FAILIF(res != -1);
    FAILIF(node->corrupt); /* Those should not have flagged corruption */
    
    ex.e[1].t.koff0[2] = 1; /* ding the offset in entry 1 */
    node->buf->length = 0;
    ccn_charbuf_append(node->buf, &ex, sizeof(ex));
    
    res = ccn_btree_key_append(cb, node, 0); /* Should still be OK */
    CHKSYS(res);
    
    res = ccn_btree_key_append(cb, node, 1); /* Should fail */
    FAILIF(res != -1);
    FAILIF(!node->corrupt);
    printf("line %d code = %d\n", __LINE__, node->corrupt);
    
    ccn_charbuf_destroy(&cb);
    ccn_charbuf_destroy(&node->buf);
    free(node);
    return(0);
}

int
test_btree_compare(void)
{
    int i, j;
    int res;
    struct ccn_btree_node *node = NULL;
    struct node_example ex = ex1;
    
    const char *expect[3] = { "d", "goodstuff", "odd" };
    
    node = calloc(1, sizeof(*node));
    CHKPTR(node);
    node->buf = ccn_charbuf_create();
    CHKPTR(node->buf);
    ccn_charbuf_append(node->buf, &ex, sizeof(ex));
    
    for (i = 0; i < 3; i++) {
        for (j = 0; j < 3; j++) {
            res = ccn_btree_compare((const void *)expect[i], strlen(expect[i]),
                node, j);
            FAILIF( (i < j) != (res < 0));
            FAILIF( (i > j) != (res > 0));
            FAILIF( (i == j) != (res == 0));
        }
    }
    ccn_charbuf_destroy(&node->buf);
    free(node);
    return(0);
}

int
test_btree_searchnode(void)
{
    int i;
    int res;
    struct ccn_btree_node *node = NULL;
    struct node_example ex = ex1;
    const int yes = 1;
    const int no = 0;
    
    struct {
        const char *s;
        int expect;
    } testvec[] = {
        {"", CCN_BT_ENCRES(0, no)},
        {"c", CCN_BT_ENCRES(0, no)},
        {"d", CCN_BT_ENCRES(0, yes)},
        {"d1", CCN_BT_ENCRES(1, no)},
        {"goodstuff", CCN_BT_ENCRES(1, yes)},
        {"goodstuff1", CCN_BT_ENCRES(2, no)},
        {"odc++++++", CCN_BT_ENCRES(2, no)},
        {"odd", CCN_BT_ENCRES(2, yes)},
        {"odd1", CCN_BT_ENCRES(3, no)},
        {"ode", CCN_BT_ENCRES(3, no)}
    };
    
    node = calloc(1, sizeof(*node));
    CHKPTR(node);
    node->buf = ccn_charbuf_create();
    CHKPTR(node->buf);
    ccn_charbuf_append(node->buf, &ex, sizeof(ex));
    
    res = ccn_btree_node_nent(node);
    FAILIF(res != 3);
    
    for (i = 0; i < sizeof(testvec)/sizeof(testvec[0]); i++) {
        const char *s = testvec[i].s;
        res = ccn_btree_searchnode((const void *)s, strlen(s), node);
        printf("search %s => %d, expected %d\n", s, res, testvec[i].expect);
        FAILIF(res != testvec[i].expect);
    }
    ccn_charbuf_destroy(&node->buf);
    free(node);
    return(0);
}

int
test_btree_init(void)
{
    struct ccn_btree *btree = NULL;
    int res;
    struct ccn_btree_node *node = NULL;
    struct ccn_btree_node *node0 = NULL;
    struct ccn_btree_node *node1 = NULL;
    
    btree = ccn_btree_create();
    CHKPTR(btree);
    node0 = ccn_btree_getnode(btree, 0, 0);
    CHKPTR(node0);
    node1 = ccn_btree_getnode(btree, 1, 0);
    FAILIF(node0 == node1);
    FAILIF(hashtb_n(btree->resident) != 2);
    node = ccn_btree_rnode(btree, 0);
    FAILIF(node != node0);
    node = ccn_btree_rnode(btree, 1);
    FAILIF(node != node1);
    node = ccn_btree_rnode(btree, 2);
    FAILIF(node != NULL);
    res = ccn_btree_destroy(&btree);
    FAILIF(btree != NULL);
    return(res);
}

struct ccn_btree *
example_btree_small(void)
{
    struct ccn_btree *btree = NULL;
    struct ccn_btree_node *root = NULL;
    struct ccn_btree_node *leaf = NULL;
    int res = 0;

    btree = ccn_btree_create();
    CHKPTR(btree);
    leaf = ccn_btree_getnode(btree, 2, 0);
    CHKPTR(leaf);
    ccn_charbuf_append(leaf->buf, &ex1, sizeof(ex1));
    res = ccn_btree_chknode(leaf);
    CHKSYS(res);
    leaf = ccn_btree_getnode(btree, 3, 0);
    CHKPTR(leaf);
    ccn_charbuf_append(leaf->buf, &ex2, sizeof(ex2));
    res = ccn_btree_chknode(leaf);
    CHKSYS(res);
    root = ccn_btree_getnode(btree, 1, 0);
    CHKPTR(root);
    ccn_charbuf_append(root->buf, &rootex1, sizeof(rootex1));
    res = ccn_btree_chknode(root);
    CHKSYS(res);
    btree->nextnodeid = 4;
    return(btree);
}

int
test_btree_lookup(void)
{
    const int yes = 1;
    const int no = 0;
    struct ccn_btree *btree = NULL;
    struct ccn_btree_node *leaf = NULL;
    int i;
    int res;
    struct {
        const char *s;
        int expectnode;
        int expectres;
    } testvec[] = {
        {"d", 2, CCN_BT_ENCRES(0, yes)},
        {"goodstuff", 2, CCN_BT_ENCRES(1, yes)},
        {"odd", 2, CCN_BT_ENCRES(2, yes)},
        {"truth", 3, CCN_BT_ENCRES(2, yes)},
        {"tooth", 3, CCN_BT_ENCRES(2, no)},
    };

    btree = example_btree_small();
    CHKPTR(btree);
    /* Now we should have a 3-node btree, all resident. Do our lookups. */
    for (i = 0; i < sizeof(testvec)/sizeof(testvec[0]); i++) {
        const char *s = testvec[i].s;
        res = ccn_btree_lookup(btree, (const void *)s, strlen(s), &leaf);
        printf("lookup %s => %d, %d, expected %d, %d\n", s,
            leaf->nodeid,          res,
            testvec[i].expectnode, testvec[i].expectres);
        FAILIF(res != testvec[i].expectres);
        FAILIF(leaf->nodeid != testvec[i].expectnode);
        FAILIF(leaf->parent != 1);
        res = ccn_btree_node_level(leaf);
        FAILIF(res != 0);
    }
    res = ccn_btree_check(btree, stderr); // see how that works out
    res = ccn_btree_destroy(&btree);
    FAILIF(btree != NULL);
    return(res);
}

int
test_basic_btree_insert_entry(void)
{
    struct ccn_btree *btree = NULL;
    struct ccn_btree_node *leaf = NULL;
    int res;
    int ndx;
    const char *s = "";
    unsigned char payload[6] = "@12345";
    unsigned char *c = NULL;
    unsigned char canary = 42;
    unsigned cage = 10000;
    unsigned perch = 1000;
    
    btree = example_btree_small();
    CHKPTR(btree);
    s = "beauty";
    res = ccn_btree_lookup(btree, (const void *)s, strlen(s), &leaf);
    CHKSYS(res);
    FAILIF(CCN_BT_SRCH_FOUND(res));
    ndx = CCN_BT_SRCH_INDEX(res);
    FAILIF(ndx != 0); // beauty before d
    memset(ccn_charbuf_reserve(leaf->buf, cage), canary, cage);
    res = ccn_btree_chknode(leaf);
    CHKSYS(res);
    res = ccn_btree_insert_entry(leaf, ndx,
                                 (const void *)s, strlen(s),
                                 payload, sizeof(payload));
    CHKSYS(res);
    res = ccn_btree_chknode(leaf);
    CHKSYS(res);
    c = &leaf->buf->buf[leaf->buf->length];
    FAILIF(c[0] != canary);
    FAILIF(0 != memcmp(c, c + 1, perch - 1));
    res = ccn_btree_lookup(btree, (const void *)s, strlen(s), &leaf);
    FAILIF(res != 1);
    res = ccn_btree_lookup(btree, (const void *)"d", 1, &leaf);
    FAILIF(res != 3);
    s = "age";
    payload[0] = 'A';
    res = ccn_btree_lookup(btree, (const void *)s, strlen(s), &leaf);
    FAILIF(res != 0);
    res = ccn_btree_insert_entry(leaf, ndx,
                                 (const void *)s, strlen(s),
                                 payload, sizeof(payload));
    CHKSYS(res);
    res = ccn_btree_lookup(btree, (const void *)s, strlen(s), &leaf);
    FAILIF(res != 1); // age before beauty
    res = ccn_btree_lookup(btree, (const void *)"d", 1, &leaf);
    FAILIF(res != 5);
    c = &leaf->buf->buf[leaf->buf->length];
    FAILIF(c[0] != canary);
    FAILIF(0 != memcmp(c, c + 1, perch - 1));
    /* Try this out here while we have a handy leaf node. */
    btree->nextnodeid = 101;
    res = ccn_btree_split(btree, leaf);
    CHKSYS(res);
    FAILIF(btree->errors != 0);
    res = ccn_btree_destroy(&btree);
    FAILIF(btree != NULL);
    return(res);
}

int
test_basic_btree_delete_entry(void)
{
    struct ccn_btree *btree = NULL;
    struct ccn_btree_node *leaf = NULL;
    int res;
    int i;
    int j;
    int ndx;
    const char *s = "";
    const char *ex[4] = {"d", "goodstuff", "odd", "odder"};
    
    for (i = 0; i < 4; i++) {
         btree = example_btree_small();
         CHKPTR(btree);
         s = ex[i];
         res = ccn_btree_lookup(btree, (const void *)s, strlen(s), &leaf);
         CHKSYS(res);
         FAILIF(CCN_BT_SRCH_FOUND(res) != (i < 3));
         ndx = CCN_BT_SRCH_INDEX(res);
         FAILIF(ndx != i);
         res = ccn_btree_chknode(leaf);
         CHKSYS(res);
         res = ccn_btree_delete_entry(leaf, i);
         FAILIF((res < 0) != (i == 3));
         for (j = 0; j < 3; j++) {
            s = ex[j];
            res = ccn_btree_lookup(btree, (const void *)s, strlen(s), &leaf);
            CHKSYS(res);
            FAILIF(CCN_BT_SRCH_FOUND(res) == (i == j));
         }
         FAILIF(btree->errors != 0);
         res = ccn_btree_destroy(&btree);
         FAILIF(btree != NULL);
    }
    return(res);
}

int
test_btree_inserts_from_stdin(void)
{
    struct ccn_charbuf *c;
    char payload[8] = "TestTree";
    int res;
    int delete;      /* Lines ending with a '!' are to be deleted instead */
    int item = 0;
    int dups = 0;
    int unique = 0;
    int deleted = 0;
    int missing = 0;
    struct ccn_btree *btree = NULL;
    struct ccn_btree_node *node = NULL;
    struct ccn_btree_node *leaf = NULL;
    
    // XXX - need nice way to create a brand-new empty btree
    btree = ccn_btree_create();
    CHKPTR(btree);
    FAILIF(btree->nextnodeid != 1);
    node = ccn_btree_getnode(btree, btree->nextnodeid++, 0);
    CHKPTR(node);
    res = ccn_btree_init_node(node, 0, 'R', 0);
    CHKPTR(node);
    FAILIF(btree->nextnodeid < 2);
    res = ccn_btree_chknode(node);
    CHKSYS(res);
    btree->full = 5;
    btree->full0 = 7;
    
    c = ccn_charbuf_create();
    CHKPTR(c);
    CHKPTR(ccn_charbuf_reserve(c, CCN_MAX_MESSAGE_BYTES));
    while (fgets((char *)c->buf, c->limit, stdin)) {
        item++;
        c->length = strlen((char *)c->buf);
        if (c->length > 0 && c->buf[c->length - 1] == '\n')
            c->length--;
        // printf("%9d %s\n", item, ccn_charbuf_as_string(c));
        delete = 0;
        if (c->length > 0 && c->buf[c->length - 1] == '!') {
            delete = 1;
            c->length--;
        }
        res = ccn_btree_lookup(btree, c->buf, c->length, &leaf);
        CHKSYS(res);
        if (delete) {
            if (CCN_BT_SRCH_FOUND(res)) {
                res = ccn_btree_delete_entry(leaf, CCN_BT_SRCH_INDEX(res));
                CHKSYS(res);
                if (res < btree->full0 / 2) {
                    int limit = 20;
                    res = ccn_btree_spill(btree, leaf);
                    CHKSYS(res);
                    while (btree->nextspill != 0) {
                        node = ccn_btree_rnode(btree, btree->nextspill);
                        CHKPTR(node);
                        res = ccn_btree_spill(btree, node);
                        CHKSYS(res);
                        FAILIF(!--limit);
                    }
                    while (btree->nextsplit != 0) {
                        node = ccn_btree_rnode(btree, btree->nextsplit);
                        CHKPTR(node);
                        res = ccn_btree_split(btree, node);
                        CHKSYS(res);
                        FAILIF(!--limit);
                    }
                }
                deleted++;
            }
            else
                missing++;
            continue;
        }
        /* insert case */
        if (CCN_BT_SRCH_FOUND(res)) {
            dups++;
        }
        else {
            unique++;
            res = ccn_btree_insert_entry(leaf, CCN_BT_SRCH_INDEX(res),
                                         c->buf, c->length,
                                         payload, sizeof(payload));
            CHKSYS(res);
            if (res > btree->full0) {
                int limit = 20;
                res = ccn_btree_split(btree, leaf);
                CHKSYS(res);
                while (btree->nextsplit != 0) {
                    node = ccn_btree_rnode(btree, btree->nextsplit);
                    CHKPTR(node);
                    res = ccn_btree_split(btree, node);
                    CHKSYS(res);
                    FAILIF(!--limit);
                }
                FAILIF(btree->missedsplit);
            }
        }
    }
    res = ccn_btree_check(btree, stderr);
    CHKSYS(res);
    printf("%d unique, %d duplicate, %d deleted, %d missing, %d errors\n",
               unique,    dups,         deleted,    missing, btree->errors);
    FAILIF(btree->errors != 0);
    res = ccn_btree_lookup(btree, c->buf, 0, &leaf); /* Get the first leaf */
    CHKSYS(res);
    printf("Leaf nodes:");
    while (leaf != NULL) {
        printf(" %u", leaf->nodeid);
        node = leaf;
        res = ccn_btree_next_leaf(btree, leaf, &leaf);
        CHKSYS(res);
    }
    printf("\n");
    printf("Reversed leaf nodes:");
    for (leaf = node; leaf != NULL;) {
        printf(" %u", leaf->nodeid);
        res = ccn_btree_prev_leaf(btree, leaf, &leaf);
        CHKSYS(res);
    }
    printf("\n");
    res = ccn_btree_destroy(&btree);
    FAILIF(btree != NULL);
    return(res);
}

int
test_flatname(void)
{
    unsigned char L0[1] = { 0x00 };
    unsigned char A[2] = { 0x01, 'A' };
    unsigned char C1[128] = { 0x7F, 0xC1, '.', 'x', '~'};
    unsigned char XL[130] = { 0x81, 0x00, 0x39, ' ', 'e', 't', 'c' };
    struct {unsigned char *x; size_t l;} ex[] = {
        {L0, 0},
        {L0, sizeof(L0)},
        {A, sizeof(A)},
        {C1, sizeof(C1)},
        {XL, sizeof(XL)},
        {0,0}
    };
    struct ccn_charbuf *flat;
    struct ccn_charbuf *flatout;
    struct ccn_charbuf *ccnb;
    struct ccn_charbuf *uri;
    int i;
    int res;
    const char *expect = NULL;
    
    flat = ccn_charbuf_create();
    flatout = ccn_charbuf_create();
    ccnb = ccn_charbuf_create();
    uri = ccn_charbuf_create();
    
    res = ccn_flatname_ncomps(flat->buf, flat->length);
    FAILIF(res != 0);
    for (i = 0; ex[i].x != NULL; i++) {
        res = ccn_name_init(ccnb);
        FAILIF(res < 0);
        flat->length = 0;
        ccn_charbuf_append(flat, ex[i].x, ex[i].l);
        res = ccn_flatname_ncomps(flat->buf, flat->length);
        FAILIF(res != (i > 0));
        res = ccn_name_append_flatname(ccnb, flat->buf, flat->length, 0, -1);
        FAILIF(res < 0);
        res = ccn_flatname_from_ccnb(flatout, ccnb->buf, ccnb->length);
        FAILIF(res < 0);
        FAILIF(flatout->length != flat->length);
        FAILIF(0 != memcmp(flatout->buf, flat->buf,flat->length));
        uri->length = 0;
        res = ccn_uri_append(uri, ccnb->buf, ccnb->length, 1);
        printf("flatname %d: %s\n", i, ccn_charbuf_as_string(uri));
    }
    ccnb->length = 0;
    res = ccn_name_from_uri(ccnb, "ccnx:/10/9/8/7/6/5/4/3/2/1/...");
    FAILIF(res < 0);
    flat->length = 0;
    for (i = 12; i >= 0; i--) {
        res = ccn_flatname_append_from_ccnb(flat, ccnb->buf, ccnb->length, i, 1);
        FAILIF(res != (i < 11));
    }
    res = ccn_flatname_append_from_ccnb(flat, ccnb->buf, ccnb->length, 1, 30);
    FAILIF(res != 10);
    uri->length = 0;
    res = ccn_uri_append_flatname(uri, flat->buf, flat->length, 0);
    printf("palindrome: %s\n", ccn_charbuf_as_string(uri));
    FAILIF(res < 0);
    expect = "/.../1/2/3/4/5/6/7/8/9/10/9/8/7/6/5/4/3/2/1/...";
    FAILIF(0 != strcmp(ccn_charbuf_as_string(uri), expect));
    res = ccn_flatname_ncomps(flat->buf, flat->length);
    FAILIF(res != 21);
    res = ccn_flatname_ncomps(flat->buf, flat->length - 2);
    FAILIF(res != -1);
    ccn_charbuf_reserve(flat, 1)[0] = 0x80;
    res = ccn_flatname_ncomps(flat->buf, flat->length + 1);
    FAILIF(res != -1);
    ccn_charbuf_reserve(flat, 1)[0] = 1;
    res = ccn_flatname_ncomps(flat->buf, flat->length + 1);
    FAILIF(res != -1);
    ccn_charbuf_destroy(&flat);
    ccn_charbuf_destroy(&flatout);
    ccn_charbuf_destroy(&ccnb);
    ccn_charbuf_destroy(&uri);
    return(0);
}

/**
 * Given an Interest (or a Name), find the matching objects
 *
 * @returns count of matches, or -1 for an error.
 */
static int
testhelp_count_matches(struct ccn_btree *btree,
                       unsigned char *msg, size_t size)
{
    struct ccn_btree_node *leaf = NULL;
    struct ccn_charbuf *flat = NULL;
    struct ccn_charbuf *scratch = NULL;
    struct ccn_parsed_interest parsed_interest = {0};
    struct ccn_parsed_interest *pi = &parsed_interest;
    int cmp;
    int i;
    int matches;
    int n;
    int res;
    
    flat = ccn_charbuf_create();
    CHKPTR(flat);
    res = ccn_flatname_from_ccnb(flat, msg, size);
    if (res < 0)
        goto Bail;
    res = ccn_parse_interest(msg, size, pi, NULL);
    if (res < 0) {
        if (flat->length > 0)
            pi = NULL; /* do prefix-only match */
        else
            goto Bail;
    }
    res = ccn_btree_lookup(btree, flat->buf, flat->length, &leaf);
    CHKSYS(res);
    matches = 0;
    /* Here we only look inside one leaf. Real code has to look beyond. */
    scratch = ccn_charbuf_create();
    n = ccn_btree_node_nent(leaf);
    for (i = CCN_BT_SRCH_INDEX(res); i < n; i++) {
        cmp = ccn_btree_compare(flat->buf, flat->length, leaf, i);
        if (cmp == 0 || cmp == CCN_STRICT_PREFIX) {
            /* The prefix matches; check the rest. */
            if (pi == NULL)
                res = 0;
            else
                res = ccn_btree_match_interest(leaf, i, msg, pi, scratch);
            CHKSYS(res);
            if (res == 1) {
                /* We have a match */
                matches++;
            }
        }
        else if (cmp > 0) {
            /* This should never happen; if it does there must be a bug. */
            FAILIF(1);
        }
        else {
            /* There is no longer a prefix match with the current object */
            break;
        }
    }
    res = matches;
Bail:
    ccn_charbuf_destroy(&flat);
    return(res);
}

/**
 * Make an index from a file filled ccnb-encoded content objects
 *
 * Interspersed interests will be regarded as querys, and matches will be
 * found.
 *
 * The file is named by the environment variable TEST_CONTENT.
 */
int
test_insert_content(void)
{
    const char *filename = NULL;
    unsigned char *cb = NULL;
    unsigned char *cob = NULL;
    struct stat statbuf;
    int dres;
    int fd;
    int i;
    int res;
    size_t cob_offset;
    size_t cob_size;
    size_t size;
    struct ccn_skeleton_decoder decoder = {0};
    struct ccn_skeleton_decoder *d = &decoder;
    struct ccn_parsed_ContentObject pcobject = {0};
    struct ccn_parsed_ContentObject *pc = &pcobject;
    struct ccn_charbuf *flatname = NULL;
    struct ccn_charbuf *temp = NULL;
    struct ccn_indexbuf *comps = NULL;
    struct ccn_btree *btree = NULL;
    struct ccn_btree_node *node = NULL;
    struct ccn_btree_node *leaf = NULL;
    
    filename = getenv("TEST_CONTENT");
    if (filename == NULL || filename[0] == 0)
        return(1);
    printf("Opening %s\n", filename);
    fd = open(filename, O_RDONLY, 0);
    CHKSYS(fd);
    res = fstat(fd, &statbuf);
    CHKSYS(res);
    size = statbuf.st_size;
    printf("Mapping %zd bytes from file %s\n", size, filename);
    cb = mmap(NULL, size, PROT_READ, MAP_SHARED, fd, 0);
    FAILIF(cb == MAP_FAILED && size != 0);

    // XXX - need nice way to create a brand-new empty btree
    btree = ccn_btree_create();
    CHKPTR(btree);
    FAILIF(btree->nextnodeid != 1);
    node = ccn_btree_getnode(btree, btree->nextnodeid++, 0);
    CHKPTR(node);
    res = ccn_btree_init_node(node, 0, 'R', 0);
    CHKPTR(node);
    FAILIF(btree->nextnodeid < 2);
    res = ccn_btree_chknode(node);
    CHKSYS(res);
    btree->full = 50;

    flatname = ccn_charbuf_create();
    CHKPTR(flatname);
    temp = ccn_charbuf_create();
    CHKPTR(temp);
    comps = ccn_indexbuf_create();
    CHKPTR(comps);
    while (d->index < size) {
        dres = ccn_skeleton_decode(d, cb + d->index, size - d->index);
        if (!CCN_FINAL_DSTATE(d->state))
            break;
        cob_offset = d->index - dres;
        cob = cb + cob_offset;
        cob_size = dres;
        printf("offset %zd, size %zd\n", cob_offset, cob_size);
        res = ccn_parse_ContentObject(cob, cob_size, pc, comps);
        if (res < 0) {
            res = testhelp_count_matches(btree, cob, cob_size);
            if (res < 0) {
                printf("  . . . skipping non-ContentObject\n");
            }
            else {
                printf("  . . . interest processing res = %d\n", res);
            }
        }
        else {
            res = ccn_flatname_from_ccnb(flatname, cob, cob_size);
            FAILIF(res != comps->n - 1);
            ccn_digest_ContentObject(cob, pc);
            FAILIF(pc->digest_bytes != 32);
            res = ccn_flatname_append_component(flatname,
                                                pc->digest, pc->digest_bytes);
            CHKSYS(res);
            temp->length = 0;
            ccn_uri_append_flatname(temp, flatname->buf, flatname->length, 1);
            res = ccn_btree_lookup(btree, flatname->buf, flatname->length, &leaf);
            CHKSYS(res);
            if (CCN_BT_SRCH_FOUND(res)) {
                printf("FOUND %s\n", ccn_charbuf_as_string(temp));
            }
            else {
                i = CCN_BT_SRCH_INDEX(res);
                res = ccn_btree_insert_content(leaf, i,
                                               cob_offset + 1,
                                               cob,
                                               pc,
                                               flatname);
                CHKSYS(res);
                printf("INSERTED %s\n", ccn_charbuf_as_string(temp));
                // don't split yet, see how we cope
            }
        }
    }
    FAILIF(d->index != size);
    FAILIF(!CCN_FINAL_DSTATE(d->state));
    if (cb != MAP_FAILED) {
        res = munmap(cb, size);
        CHKSYS(res);
        cb = NULL;
        size = 0;
    }
    res = close(fd);
    CHKSYS(res);
    ccn_charbuf_destroy(&flatname);
    ccn_charbuf_destroy(&temp);
    ccn_indexbuf_destroy(&comps);
    return(0);
}

int
ccnbtreetest_main(int argc, char **argv)
{
    int res;

    if (argv[1] && 0 == strcmp(argv[1], "-")) {
        res = test_btree_inserts_from_stdin();
        CHKSYS(res);
        exit(0);
    }
    res = test_directory_creation();
    CHKSYS(res);
    res = test_btree_io();
    CHKSYS(res);
    res = test_btree_lockfile();
    CHKSYS(res);
    res = test_structure_sizes();
    CHKSYS(res);
    res = test_btree_chknode();
    CHKSYS(res);
    res = test_btree_key_fetch();
    CHKSYS(res);
    res = test_btree_compare();
    CHKSYS(res);
    res = test_btree_searchnode();
    CHKSYS(res);
    res = test_btree_init();
    CHKSYS(res);
    res = test_btree_lookup();
    CHKSYS(res);
    res = test_basic_btree_insert_entry();
    CHKSYS(res);
    test_basic_btree_delete_entry();
    CHKSYS(res);
    res = test_flatname();
    CHKSYS(res);
    res = test_insert_content();
    CHKSYS(res);
    if (res != 0)
        fprintf(stderr, "test_insert_content() => %d\n", res);
    return(0);
}
