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
    struct ccn_charbuf *c = NULL;
    struct ccn_charbuf *f = NULL;
    int delete;      /* Lines ending with a '!' are to be deleted instead */
    int i;
    int item = 0;
    int dups = 0;
    int unique = 0;
    int deleted = 0;
    int missing = 0;
    ccn_cookie cookie = 0;
    ccn_cookie ocookie = 0;
    struct ccn_nametree *ntree = NULL;
    struct ccny *node = NULL;
    unsigned char *p = NULL;
    
    ntree = ccn_nametree_create(42);
    CHKPTR(ntree);
    ccn_nametree_check(ntree);
    c = ccn_charbuf_create();
    CHKPTR(c);
    f = ccn_charbuf_create();
    CHKPTR(f);
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
        /* Turn the string into a valid flatname, one byte per component */
        ccn_charbuf_reset(f);
        p = ccn_charbuf_reserve(f, 2 * c->length);
        for (i = 0; i < c->length; i++) {
            p[2 * i] = 1;
            p[2 * i + 1] = c->buf[i];
        }
        f->length = 2 * c->length;
        node = ccn_nametree_lookup(ntree, f->buf, f->length);
        cookie = ccny_cookie(node);
        if (0) ccn_nametree_check(ntree);
        if (delete) {
            if (cookie != 0) {
                ccny_remove(ntree, node);
                ccny_destroy(ntree, &node);
                FAILIF(node != NULL);
                deleted++;
            }
            else
                missing++;
            continue;
        }
        /* insert case */
        node = ccny_create(lrand48(), 0);
        ccny_set_key(node, f->buf, f->length);
        if (ntree->n >= ntree->limit) {
            int res = ccn_nametree_grow(ntree);
            FAILIF(res != 0);
            fprintf(stderr, "n=%d, limit=%d\n", ntree->n, ntree->limit);
        }
        ocookie = ccny_enroll(ntree, node);
        if (cookie != 0) {
            FAILIF(ocookie != cookie);
            ccny_destroy(ntree, &node);
            FAILIF(node != NULL);
            dups++;
        }
        else {
            FAILIF(ccny_cookie(node) == 0);
            unique++;
        }
    }
    ccn_nametree_check(ntree);
    printf("%d unique, %d duplicate, %d deleted, %d missing\n",
               unique,    dups,         deleted,    missing);
    printf("Nametree nodes:");
    for (node = ccn_nametree_first(ntree); node != NULL; node = ccny_next(node))
        printf(" %u", ccny_cookie(node));
    printf("\n");
    printf("Reversed nodes:");
    for (node = ccn_nametree_last(ntree); node != NULL; node = ccny_prev(node))
        printf(" %u", ccny_cookie(node));
    printf("\n");
    FAILIF(unique - deleted != ntree->n);
    ccn_nametree_destroy(&ntree);
    ccn_charbuf_destroy(&c);
    ccn_charbuf_destroy(&f);
    return(0);
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
