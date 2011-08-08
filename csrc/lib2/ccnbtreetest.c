/**
 * @file ccnbtreetest.c
 * 
 * Part of ccnr - CCNx Repository Daemon.
 *
 */

/*
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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
#include <sys/types.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <ccn/btree.h>
#include <ccn/charbuf.h>

#define FAILIF(cond) do {} while ((cond) && fatal(__func__, __LINE__))
#define CHKSYS(res) FAILIF((res) == -1)
#define CHKPTR(p)   FAILIF((p) == NULL)

static int
fatal(const char *fn, int lineno)
{
    char buf[80] = {0};
    snprintf(buf, sizeof(buf)-1, "OOPS - function %s, line %d", fn, lineno);
    perror(buf);
    exit(1);
    return(0);
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
    io = ccn_btree_io_from_directory(getenv("TEST_DIRECTORY"));
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
    check_structure_size("ccn_btree_internal_payload",
            sizeof(struct ccn_btree_internal_payload));
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

    io = ccn_btree_io_from_directory(getenv("TEST_DIRECTORY"));
    CHKPTR(io);
    /* Make sure the locking works */
    io2 = ccn_btree_io_from_directory(getenv("TEST_DIRECTORY"));
    FAILIF(io2 != NULL || errno != EEXIST);
    errno=EINVAL;
    res = io->btdestroy(&io);
    CHKSYS(res);
    FAILIF(io != NULL);
    return(res);
}

int
test_btree_key_fetch(void)
{
    int i;
    int res;
    struct ccn_charbuf *cb = NULL;
    struct ccn_btree_node *node = NULL;
    struct {
        unsigned char ss[CCN_BT_SIZE_UNITS * 2];
        struct ccn_btree_entry_trailer e[3];
    } ex = {
        "goodstuff",
        {
            {.koff0={0,0,0,3}, .ksiz0={0,1}, .index={0,0}, .entsz={2}}, // "d"
            {.koff0={0,0,0,0}, .ksiz0={0,9}, .index={0,1}, .entsz={2}}, // "goodstuff"
            {.koff0={0,0,0,2}, .ksiz0={0,2}, .index={0,2}, .entsz={2},
                .koff1={0,0,0,3}, .ksiz1={0,1}}, // "odd"
        }
    };
    
    const char *expect[3] = { "d", "goodstuff", "odd" };
    
    node = calloc(1, sizeof(*node));
    printf("sssss\n");
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
    
    ex.e[1].koff0[2] = 1; /* ding the offset in entry 1 */
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
    return(sizeof(ex));
}

int
main(int argc, char **argv)
{
    int res;

    res = test_directory_creation();
    CHKSYS(res);
    res = test_btree_io();
    CHKSYS(res);
    res = test_btree_lockfile();
    CHKSYS(res);
    res = test_structure_sizes();
    CHKSYS(res);
    res = test_btree_key_fetch();
    CHKSYS(res);
    return(0);
}
