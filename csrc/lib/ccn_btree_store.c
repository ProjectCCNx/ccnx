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
#include <signal.h>
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
    int lfd;
};

/**
 * Create a btree storage layer from a directory.
 * 
 * In this implementation of the storage layer, each btree block is stored
 * as a separate file.
 * The files are named using the decimal representation of the nodeid.
 *
 * If msgs is not NULL, diagnostics may be recorded there.
 *
 * @param path is the name of the directory, which must exist.
 * @param msgs charbuf into which diagnostic messages will be recorded, if not NULL.
 * @returns the new ccn_btree_io handle, or sets errno and returns NULL.
 */
struct ccn_btree_io *
ccn_btree_io_from_directory(const char *path, struct ccn_charbuf *msgs)
{
    DIR *d = NULL;
    struct bts_data *md = NULL;
    struct ccn_btree_io *ans = NULL;
    struct ccn_btree_io *tans = NULL;
    struct ccn_charbuf *temp = NULL;
    char tbuf[21];
    int fd = -1;
    struct flock flk = {0};
    int pid, res;
    int maxnodeid = 0;
    
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
    md->lfd = -1;
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
    flk.l_type = F_WRLCK;
    flk.l_whence = SEEK_SET;
    md->lfd = open(ccn_charbuf_as_string(temp),
               (O_RDWR | O_CREAT | O_EXCL),
               0600);
    if (md->lfd == -1) {
        if (errno == EEXIST) {
            // try to recover by checking if the pid the lock names exists
            md->lfd = open(ccn_charbuf_as_string(temp), O_RDWR);
            if (md->lfd == -1) {
                if (msgs != NULL)
                    ccn_charbuf_append_string(msgs, "Unable to open pid file for update. ");
                goto Bail;
            }
            memset(tbuf, 0, sizeof(tbuf));
            if (read(md->lfd, tbuf, sizeof(tbuf) - 1) <= 0) {
                if (msgs != NULL)
                    ccn_charbuf_append_string(msgs, "Unable to read pid from pid file. ");
                goto Bail;
            }
            pid = strtol(tbuf, NULL, 10);
            if (pid == (int)getpid())
                goto Bail; /* locked by self; errno still EACCES */
            if (fcntl(md->lfd, F_SETLK, &flk) == -1) {
                if (errno == EACCES || errno == EAGAIN) { // it's locked
                    fcntl(md->lfd, F_GETLK, &flk);
                    if (msgs != NULL)
                        ccn_charbuf_putf(msgs, "Locked by process id %d. ", flk.l_pid);
                    goto Bail;
                }            
            }
            if (msgs != NULL)
                ccn_charbuf_putf(msgs, "Breaking stale lock by pid %d. ", pid);
            lseek(md->lfd, 0, SEEK_SET);
            if (ftruncate(md->lfd, 0) < 0) {
                if (msgs != NULL)
                    ccn_charbuf_append_string(msgs, "Unable to truncate pid file. ");
                goto Bail; /* errno per ftruncate */
            };
        }
        else {
            if (msgs != NULL)
                ccn_charbuf_append_string(msgs, "Unable to open pid file. ");
            goto Bail; /* errno per open, probably EACCES */
        }
    }
    else if (fcntl(md->lfd, F_SETLK, &flk) == -1) {
        if (errno == EACCES || errno == EAGAIN) { // it's locked
            fcntl(md->lfd, F_GETLK, &flk);
            if (msgs != NULL)
                ccn_charbuf_putf(msgs, "Locked by process id %d. ", flk.l_pid);
            goto Bail;
        }            
    }
    /* Locking succeeded - place our pid in the lockfile so humans can see it */
    temp->length = 0;
    ccn_charbuf_putf(temp, "%d", (int)getpid());
    if (write(md->lfd, temp->buf, temp->length) <= 0) {
        if (msgs != NULL)
            ccn_charbuf_append_string(msgs, "Unable to write pid file.");
        goto Bail;
    }
    /* leave the lock file descriptor open, otherwise the lock is released */
    /* Read maxnodeid */
    temp->length = 0;
    ccn_charbuf_append_charbuf(temp, md->dirpath);
    ccn_charbuf_putf(temp, "/maxnodeid");
    fd = open(ccn_charbuf_as_string(temp), O_RDWR);
    if (fd != -1) {
        memset(tbuf, 0, sizeof(tbuf));
        res = read(fd, tbuf, sizeof(tbuf) - 1);
        errno = EINVAL;
        maxnodeid = strtoul(tbuf, NULL, 10);
        if (maxnodeid == 0)
            goto Bail;
    }
    /* Everything looks good. */
    ans = tans;
    tans = NULL;
    res = md->dirpath->length;
    if (res >= sizeof(ans->clue))
        res = sizeof(ans->clue) - 1;
    memcpy(ans->clue, md->dirpath->buf + md->dirpath->length - res, res);
    ans->btopen = &bts_open;
    ans->btread = &bts_read;
    ans->btwrite = &bts_write;
    ans->btclose = &bts_close;
    ans->btdestroy = &bts_destroy;
    ans->maxnodeid = maxnodeid;
    ans->openfds = 0;
    ans->data = md;
    md->io = ans;
    md = NULL;
Bail:
    if (fd != -1) close(fd);
    if (tans != NULL) free(tans);
    if (md != NULL) {
        ccn_charbuf_destroy(&md->dirpath);
        free(md);
    }
    ccn_charbuf_destroy(&temp);
    return(ans);
}

struct bts_node_state {
    struct ccn_btree_node *node;
    int fd;
};

static int
bts_open(struct ccn_btree_io *io, struct ccn_btree_node *node)
{
    struct bts_node_state *nd = NULL;
    struct ccn_charbuf *temp = NULL;
    struct bts_data *md = io->data;
    int res, l;
    
    if (node->iodata != NULL || io != md->io) abort();
    nd = calloc(1, sizeof(*nd));
    if (nd == NULL)
        return(-1);
    temp = ccn_charbuf_create();
    if (temp == NULL)
        goto Bail;
    res = ccn_charbuf_append_charbuf(temp, md->dirpath);
    res |= ccn_charbuf_putf(temp, "/%u", (unsigned)node->nodeid);
    if (res < 0)
        goto Bail;
    nd->fd = open(ccn_charbuf_as_string(temp),
               (O_RDWR | O_CREAT),
               0640);
    if (nd->fd < 0)
        goto Bail;
    if (node->nodeid > io->maxnodeid) {
        /* Record maxnodeid in a file */
        io->maxnodeid = node->nodeid;
        temp->length = 0;
        ccn_charbuf_append_charbuf(temp, md->dirpath);
        ccn_charbuf_putf(temp, "/maxnodeid");
        res = open(ccn_charbuf_as_string(temp),
                   (O_RDWR | O_CREAT | O_TRUNC),
                   0640);
        if (res < 0)
            goto Bail;
        temp->length = 0;
        ccn_charbuf_putf(temp, "%u", (unsigned)node->nodeid);
        l = write(res, temp->buf, temp->length);
        close(res);
        if (l != temp->length)
            goto Bail;
    }
    ccn_charbuf_destroy(&temp);
    io->openfds++;
    nd->node = node;
    node->iodata = nd;
    return(nd->fd);
    
Bail:
    ccn_charbuf_destroy(&temp);
    if (nd->fd >= 0)
        close(nd->fd);
    free(nd);
    return(-1);
}

static int
bts_read(struct ccn_btree_io *io, struct ccn_btree_node *node, unsigned limit)
{
    struct bts_node_state *nd = node->iodata;
    ssize_t sres;
    off_t offset;
    off_t clean = 0;
    
    if (nd == NULL || nd->node != node) abort();
    offset = lseek(nd->fd, 0, SEEK_END);
    if (offset == (off_t)-1)
        return(-1);
    if (offset < limit)
        limit = offset;
    if (node->clean > 0 && node->clean <= node->buf->length)
        clean = node->clean;
    offset = lseek(nd->fd, clean, SEEK_SET);
    if (offset == (off_t)-1)
        return(-1);
    if (offset != clean)
        abort();
    node->buf->length = clean;  /* we know clean <= node->buf->length */
    sres = read(nd->fd, ccn_charbuf_reserve(node->buf, limit - clean), limit - clean);
    if (sres < 0)
        return(-1);
    if (sres != limit - clean) {
        abort(); // XXX - really should not happen unless someone else modified file
    }
    if (sres + node->buf->length > node->buf->limit) {
        abort(); // oooops!
    }
    node->buf->length += sres;
    return(0);
}

static int
bts_write(struct ccn_btree_io *io, struct ccn_btree_node *node)
{
    struct bts_node_state *nd = node->iodata;
    ssize_t sres;
    off_t offset;
    size_t clean = 0;
    
    if (nd == NULL || nd->node != node) abort();
    if (node->clean > 0 && node->clean <= node->buf->length)
        clean = node->clean;
    offset = lseek(nd->fd, clean, SEEK_SET);
    if (offset == (off_t)-1)
        return(-1);
    if (offset != clean)
        abort();
    sres = write(nd->fd, node->buf->buf + clean, node->buf->length - clean);
    if (sres == -1)
        return(-1);
    if (sres + clean != node->buf->length)
        abort();
    return(ftruncate(nd->fd, node->buf->length));
}

static int
bts_close(struct ccn_btree_io *io, struct ccn_btree_node *node)
{
    struct bts_node_state *nd = node->iodata;
    int res = -1;
    
    if (nd != NULL && nd->node == node) {
        res = close(nd->fd);
        if (res == -1 && errno == EINTR)
            return(res);
        io->openfds--;
        nd->node = NULL;
        node->iodata = NULL;
        free(nd);
    }
    return(res);
}

/**
 *  Remove the lock file, trusting that it is ours.
 *  @returns -1 if there were errors (but it cleans up what it can).
 */
static int
bts_remove_lockfile(struct ccn_btree_io *io)
{
    size_t sav;
    int res;
    struct flock flk = {0};
    struct bts_data *md = NULL;
    
    md = io->data;
    sav = md->dirpath->length;
    ccn_charbuf_putf(md->dirpath, "/.LCK");
    res = unlink(ccn_charbuf_as_string(md->dirpath));
    md->dirpath->length = sav;
    if (md->lfd >= 0) {
        flk.l_type = F_UNLCK;
        flk.l_whence = SEEK_SET;
        fcntl(md->lfd, F_SETLK, &flk);
        md->lfd = -1;
    }
    return(res);
}

/**
 *  Remove the lock file and free up resources.
 *  @returns -1 if there were errors (but it cleans up what it can).
 */
static int
bts_destroy(struct ccn_btree_io **pio)
{
    int res;
    struct bts_data *md = NULL;

    if (*pio == NULL)
        return(0);
    if ((*pio)->btdestroy != &bts_destroy)
        abort(); /* serious caller bug */
    res = bts_remove_lockfile(*pio);
    md = (*pio)->data;
    if (md->io != *pio) abort();
    ccn_charbuf_destroy(&md->dirpath);
    free(md);
    (*pio)->data = NULL;
    free(*pio);
    *pio = NULL;
    return(res);
}
