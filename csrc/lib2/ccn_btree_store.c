/**
 * File-based btree index storage
 */
/* (Will be) Part of the CCNx C Library.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */
 
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <ccn/btree.h>
#include <ccn/charbuf.h>

static int bts_open(struct ccn_btree_io *, struct ccn_btree_node *);
static int bts_read(struct ccn_btree_io *, struct ccn_btree_node *, unsigned);
static int bts_write(struct ccn_btree_io *, struct ccn_btree_node *);
static int bts_close(struct ccn_btree_io *, struct ccn_btree_node *);
static int bts_destroy(struct ccn_btree_io **);

struct bts_data {
    struct ccn_btree_io *io;
    struct ccn_charbuf *dirpath;
};

/**
 * Create a btree storage layer from a directory.
 * 
 * In this implementationof the storage layer, each btree block is stored
 * as a separate file.
 * The files are named using the decimal representation of the nodeid.
 *
 * @param path is the name of the directory, which must exist.
 * @returns the new ccn_btree_io handle, or sets errno and returns NULL.
 */
struct ccn_btree_io *
ccn_btree_io_from_directory(const char *path)
{
    DIR *d = NULL;
    struct bts_data *md = NULL;
    struct ccn_btree_io *ans = NULL;
    struct ccn_btree_io *tans = NULL;
    struct ccn_charbuf *temp = NULL;
    int res;
    
    /* Make sure we were handed a directory */
    d = opendir(path);
    if (d == NULL)
        goto Bail; /* errno per opendir */
    closedir(d);
    d = NULL;
    
    /* Allocate private data area */
    md = calloc(1, sizeof(*md));
    if (md == NULL)
        goto Bail; /* errno per calloc */
    md->dirpath = ccn_charbuf_create();
    if (md->dirpath == NULL) goto Bail; /* errno per calloc */
    res = ccn_charbuf_putf(md->dirpath, "%s", path);
    if (res < 0) goto Bail; /* errno per calloc or snprintf */
    tans = calloc(1, sizeof(*tans));
    if (tans == NULL) goto Bail; /* errno per calloc */
    
    /* Try to create a lock file */
    temp = ccn_charbuf_create();
    if (temp == NULL) goto Bail; /* errno per calloc */
    res = ccn_charbuf_append_charbuf(temp, md->dirpath);
    if (res < 0) goto Bail; /* errno per calloc */
    res = ccn_charbuf_putf(temp, "/.LCK");
    if (res < 0) goto Bail; /* errno per calloc or snprintf */
    res = open(ccn_charbuf_as_string(temp),
               (O_RDWR | O_CREAT | O_EXCL),
               0700);
    if (res == -1) {
        if (errno == EEXIST) {
            // XXX - we might be able to recover by breaking the lock if
            // the pid it names no longer exists.  But this can be done upstairs.
        }
        goto Bail; /* errno per open, probably EACCES or EEXIST */
    }
    /* Place our pid in the lockfile so we know who to blame */
    temp->length = 0;
    ccn_charbuf_putf(temp, "%d", (int)getpid());
    if (write(res, temp->buf, temp->length) < 0) {
        close(res);
        goto Bail;
    }
    close(res);
    /* Everything looks good. */
    ans = tans;
    tans = NULL;
    res = md->dirpath->length;
    if (res >= sizeof(ans->clue))
        res = sizeof(ans->clue) - 1;
    memcpy(ans->clue, md->dirpath->buf + md->dirpath->length - res, res);
    ans->btopen = &bts_open;
    ans->btread = &bts_read;
    ans->bwrite = &bts_write;
    ans->btclose = &bts_close;
    ans->btdestroy = &bts_destroy;
    ans->data = md;
    md->io = ans;
    md = NULL;
Bail:
    if (tans != NULL) free(tans);
    if (md != NULL) {
        ccn_charbuf_destroy(&md->dirpath);
        free(md);
    }
    ccn_charbuf_destroy(&temp);
    return(ans);
}

static int
bts_open(struct ccn_btree_io *io, struct ccn_btree_node *node)
{return -1;}

static int
bts_read(struct ccn_btree_io *io, struct ccn_btree_node *node, unsigned limit)
{return -1;}

static int
bts_write(struct ccn_btree_io *io, struct ccn_btree_node *node)
{return -1;}

static int
bts_close(struct ccn_btree_io *io, struct ccn_btree_node *node)
{return -1;}

static int
bts_destroy(struct ccn_btree_io **pio)
{return -1;}
