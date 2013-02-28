/**
 * @file nametreetest.c
 * 
 * Unit tests for nametree functions
 *
 */
/*
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
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

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/flatname.h>
#include <ccn/nametree.h>
#include <ccn/uri.h>

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

int
test_inserts_from_stdin(void)
{
    struct ccn_charbuf *c;
    int res;
    int delete;      /* Lines ending with a '!' are to be deleted instead */
    int item = 0;
    int dups = 0;
    int unique = 0;
    int deleted = 0;
    int missing = 0;
    ccn_cookie cookie = 0;
    struct ccn_nametree *ntree = NULL;
    struct ccny *node = NULL;
    
    ntree = ccn_nametree_create();
    CHKPTR(ntree);
    c = ccn_charbuf_create();
    CHKPTR(c);
    CHKPTR(ccn_charbuf_reserve(c, 8800));
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
        
        cookie = ccn_nametree_lookup(ntree, c->buf, c->length);
        if (delete) {
            if (cookie != 0) {
                node = ccny_from_cookie(ntree, cookie);
                ccny_remove(ntree, node);
                ccny_destroy(&node);
                FAILIF(node != NULL);
                deleted++;
            }
            else
                missing++;
            continue;
        }
        /* insert case */
        node = ccny_create(lrand48());
        node->flatname = ccn_charbuf_create();
        ccn_charbuf_append(node->flatname, c->buf, c->length);
        if (ntree->n >= ntree->limit) {
            res = ccn_nametree_grow(ntree);
            FAILIF(res != 0);
            fprintf(stderr, "n=%d, limit=%d\n", ntree->n, ntree->limit);
        }
        res = ccny_enroll(ntree, node);
        if (cookie != 0) {
            FAILIF(res != 1);
            ccny_destroy(&node);
            FAILIF(node != NULL);
            dups++;
        }
        else {
            FAILIF(res != 0);
            unique++;
        }
    }
    printf("%d unique, %d duplicate, %d deleted, %d missing\n",
               unique,    dups,         deleted,    missing);
    printf("Nametree nodes:");
    for (cookie = ntree->sentinel->skiplinks[0]; cookie != 0; cookie = node->skiplinks[0]) {
        printf(" %u", cookie);
        node = ccny_from_cookie(ntree, cookie);
    }
    printf("\n");
    printf("Reversed nodes:");
    for (node = ntree->sentinel->prev; node != NULL; node = node->prev)
        printf(" %u", node->cookie);
    printf("\n");
    FAILIF(unique - deleted != ntree->n);
    ccn_nametree_destroy(&ntree);
    ccn_charbuf_destroy(&c);
    return(res);
}

int
nametreetest_main(int argc, char **argv)
{
    int res;

    if (argv[1] && 0 == strcmp(argv[1], "-")) {
        res = test_inserts_from_stdin();
        CHKSYS(res);
        if (0) {
            char buf[40];
            snprintf(buf, sizeof(buf), "leaks %d", (int)getpid());
            system(buf);
        }
        exit(0);
    }
    return(-1);
}
