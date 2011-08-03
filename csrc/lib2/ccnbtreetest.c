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

int
main(int argc, char **argv)
{
    int res;
    struct ccn_btree_io *io = NULL;
    struct ccn_btree_io *io2 = NULL;

    res = test_directory_creation();
    CHKSYS(res);
    io = ccn_btree_io_from_directory(getenv("TEST_DIRECTORY"));
    CHKPTR(io);
    /* Make sure the locking works */
    io2 = ccn_btree_io_from_directory(getenv("TEST_DIRECTORY"));
    FAILIF(io2 != NULL || errno != EEXIST);
    res = io->btdestroy(&io);
    CHKSYS(res);
    FAILIF(io != NULL);
    return(0);
}
