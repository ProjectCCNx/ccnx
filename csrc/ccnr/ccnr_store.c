/**
 * @file ccnr_store.c
 * 
 * Part of ccnr -  CCNx Repository Daemon.
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
#include <fcntl.h>
#include <limits.h>
#include <netdb.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <netinet/in.h>

#include <ccn/bloom.h>
#include <ccn/btree_content.h>
#include <ccn/ccn.h>
#include <ccn/ccn_private.h>
#include <ccn/charbuf.h>
#include <ccn/face_mgmt.h>
#include <ccn/hashtb.h>
#include <ccn/indexbuf.h>
#include <ccn/schedule.h>
#include <ccn/reg_mgmt.h>
#include <ccn/uri.h>

#include "ccnr_private.h"

#include "ccnr_stats.h"
#include "ccnr_store.h"
#include "ccnr_init.h"
#include "ccnr_link.h"
#include "ccnr_util.h"
#include "ccnr_proto.h"
#include "ccnr_msg.h"
#include "ccnr_sync.h"
#include "ccnr_match.h"
#include "ccnr_sendq.h"
#include "ccnr_io.h"

struct content_entry {
    ccnr_accession accession;   /**< permanent repository id */
    ccnr_cookie cookie;         /**< for in-memory references */
    int flags;                  /**< see below - use accessor functions */
    int size;                   /**< size of ContentObject */
    struct ccn_charbuf *flatname; /**< for skiplist, et. al. */
    struct ccn_charbuf *cob;    /**< may contain ContentObject, or be NULL */
};

static const unsigned char *bogon = NULL;

static int
r_store_set_flatname(struct ccnr_handle *h, struct content_entry *content,
                     struct ccn_parsed_ContentObject *pco);
static int
r_store_content_btree_insert(struct ccnr_handle *h,
                             struct content_entry *content,
                             struct ccn_parsed_ContentObject *pco,
                             ccnr_accession *accession);

#define FAILIF(cond) do {} while ((cond) && r_store_fatal(h, __func__, __LINE__))
#define CHKSYS(res) FAILIF((res) == -1)
#define CHKRES(res) FAILIF((res) < 0)
#define CHKPTR(p)   FAILIF((p) == NULL)

static int
r_store_fatal(struct ccnr_handle *h, const char *fn, int lineno)
{
    if (h != NULL) {
        ccnr_msg(h,
                 "fatal error in %s, line %d, errno %d%s",
                 fn, lineno, errno, strerror(errno));
    }
    abort();
    return(0);
}

PUBLIC ccnr_accession
r_store_content_accession(struct ccnr_handle *h, struct content_entry *content)
{
    return(content->accession);
}

PUBLIC ccnr_cookie
r_store_content_cookie(struct ccnr_handle *h, struct content_entry *content)
{
    return(content->cookie);
}

PUBLIC size_t
r_store_content_size(struct ccnr_handle *h, struct content_entry *content)
{
    return(content->size);
}

static off_t
r_store_offset_from_accession(struct ccnr_handle *h, ccnr_accession a)
{
    return(a & ((((ccnr_accession)1) << 48) - 1));
}

static unsigned
r_store_repofile_from_accession(struct ccnr_handle *h, ccnr_accession a)
{
    /* Initially this should always be 1 */
    return(a >> 48);
}


static const unsigned char *
r_store_content_mapped(struct ccnr_handle *h, struct content_entry *content)
{
    return(NULL);
}

static const unsigned char *
r_store_content_read(struct ccnr_handle *h, struct content_entry *content)
{
    unsigned repofile;
    off_t offset;
    struct ccn_charbuf *cob = NULL;
    ssize_t rres = 0;
    int fd = -1;
    unsigned char buf[8800];
    struct ccn_skeleton_decoder decoder = {0};
    struct ccn_skeleton_decoder *d = &decoder;
    ssize_t dres;
    
    repofile = r_store_repofile_from_accession(h, content->accession);
    offset = r_store_offset_from_accession(h, content->accession);
    if (repofile != 1)
        goto Bail;
    if (content->cob != NULL)
        goto Bail;
    fd = r_io_repo_data_file_fd(h, repofile, 0);
    if (fd == -1)
        goto Bail;
    cob = ccn_charbuf_create();
    if (cob == NULL)
        goto Bail;
    if (content->size > 0) {
        if (ccn_charbuf_reserve(cob, content->size) == NULL)
            goto Bail;
        rres = pread(fd, cob->buf, content->size, offset);
        if (rres == content->size) {
            cob->length = content->size;
            content->cob = cob;
            h->cob_count++;
            return(cob->buf);
        }
        if (rres == -1)
            ccnr_msg(h, "r_store_content_read %u :%s (errno = %d)",
                     fd, strerror(errno), errno);
        else
            ccnr_msg(h, "r_store_content_read %u expected %d bytes, but got %d",
                     fd, (int)content->size, (int)rres);
    } else {
        rres = pread(fd, buf, 8800, offset); // XXX - should be symbolic
        if (rres == -1) {
            ccnr_msg(h, "r_store_content_read %u :%s (errno = %d)",
                     fd, strerror(errno), errno);
            goto Bail;
        }
        dres = ccn_skeleton_decode(d, buf, rres);
        if (d->state != 0) {
            ccnr_msg(h, "r_store_content_read %u : error parsing cob", fd);
            goto Bail;
        }
        content->size = dres;
        if (ccn_charbuf_append(cob, buf, dres) < 0)
            goto Bail;
        content->cob = cob;
        h->cob_count++;
        return(cob->buf);        
    }
Bail:
    ccn_charbuf_destroy(&cob);
    return(NULL);
}

/**
 *  If the content appears to be safely stored in the repository,
 *  removes any buffered copy.
 * @returns 0 if buffer was removed, -1 if not.
 */
PUBLIC int
r_store_content_trim(struct ccnr_handle *h, struct content_entry *content)
{
    if (content->accession != CCNR_NULL_ACCESSION && content->cob != NULL) {
        ccn_charbuf_destroy(&content->cob);
        h->cob_count--;
        return(0);
    }
    return(-1);
}

/**
 *  Evict recoverable content from in-memory buffers
 */
PUBLIC void
r_store_trim(struct ccnr_handle *h, unsigned long limit)
{
    struct content_entry *content = NULL;
    int pass;
    unsigned before;
    
    before = h->cob_count;
    if (before <= limit)
        return;
    for (pass = 0; h->cob_count > limit && pass < 2;) {
        content = r_store_content_from_cookie(h, ++h->trim_rover);
        if (content == NULL) {
            pass++;
            h->trim_rover = h->cookie_base;
            content = r_store_content_from_cookie(h, h->trim_rover);
            if (content == NULL)
                break;
        }
        r_store_content_trim(h, content);
    }
    if (CCNSHOULDLOG(h, sdf, CCNL_FINEST))
        ccnr_msg(h, "trimmed %u cobs", before - h->cob_count);
}

/**
 *  Get the base address of the content object
 *
 * This may involve reading the object in.  Caller should not assume that
 * the address will stay valid after it relinquishes control, either by
 * returning or by calling routines that might invalidate objects.
 *
 */
PUBLIC const unsigned char *
r_store_content_base(struct ccnr_handle *h, struct content_entry *content)
{
    const unsigned char *ans = NULL;
    
    if (content->cob != NULL && content->cob->length == content->size) {
        ans = content->cob->buf;
        goto Finish;
    }
    if (content->accession == CCNR_NULL_ACCESSION)
        goto Finish;
    ans = r_store_content_mapped(h, content);
    if (ans != NULL)
        goto Finish;
    ans = r_store_content_read(h, content);
Finish:
    if (ans != NULL) {
        /* Sanity check - make sure first 2 and last 2 bytes are good */
        if (content->size < 5 || ans[0] != 0x04 || ans[1] != 0x82 ||
            ans[content->size - 1] != 0 || ans[content->size - 2] != 0) {
            bogon = ans; /* for debugger */
            ans = NULL;
        }
    }
    if (ans == NULL || CCNSHOULDLOG(h, xxxx, CCNL_FINEST))
        ccnr_msg(h, "r_store_content_base.%d returning %p (acc=0x%jx, cookie=%u)",
                 __LINE__,
                 ans,
                 ccnr_accession_encode(h, content->accession),
                 (unsigned)content->cookie);
    return(ans);
}

PUBLIC int
r_store_name_append_components(struct ccn_charbuf *dst,
                               struct ccnr_handle *h,
                               struct content_entry *content,
                               int skip,
                               int count)
{
    int res;
    
    res = ccn_name_append_flatname(dst,
                                   content->flatname->buf,
                                   content->flatname->length, skip, count);
    return(res);
}

PUBLIC int
r_store_content_flags(struct content_entry *content)
{
    return(content->flags);
}

PUBLIC int
r_store_content_change_flags(struct content_entry *content, int set, int clear)
{
    int old = content->flags;
    content->flags |= set;
    content->flags &= ~clear;
    return(old);
}

/**
 * Write a file named index/stable that contains the size of
 * repoFile1 when the repository is shut down.
 */
static int
r_store_write_stable_point(struct ccnr_handle *h)
{
    struct ccn_charbuf *path = NULL;
    struct ccn_charbuf *cb = NULL;
    int fd;
    
    path = ccn_charbuf_create();
    cb = ccn_charbuf_create();
    ccn_charbuf_putf(path, "%s/index/stable", h->directory);
    unlink(ccn_charbuf_as_string(path)); /* Should not exist, but just in case. */
    fd = open(ccn_charbuf_as_string(path),
              O_CREAT | O_EXCL | O_WRONLY | O_TRUNC, 0666);
    if (fd == -1) {
        ccnr_msg(h, "cannot write stable mark %s: %s",
                 ccn_charbuf_as_string(path), strerror(errno));
        unlink(ccn_charbuf_as_string(path));
    }
    else {
        ccn_charbuf_putf(cb, "%ju", (uintmax_t)(h->stable));
        write(fd, cb->buf, cb->length);
        close(fd);
        if (CCNSHOULDLOG(h, dfsdf, CCNL_INFO))
            ccnr_msg(h, "Index marked stable - %s", ccn_charbuf_as_string(cb));
    }
    ccn_charbuf_destroy(&path);
    ccn_charbuf_destroy(&cb);
    return(0);
}

/**
 * Read the former size of repoFile1 from index/stable, and remove
 * the latter.
 */
static void
r_store_read_stable_point(struct ccnr_handle *h)
{
    struct ccn_charbuf *path = NULL;
    struct ccn_charbuf *cb = NULL;
    int fd;
    int i;
    ssize_t rres;
    uintmax_t val;
    unsigned char c;
    
    path = ccn_charbuf_create();
    cb = ccn_charbuf_create();
    ccn_charbuf_putf(path, "%s/index/stable", h->directory);
    fd = open(ccn_charbuf_as_string(path), O_RDONLY, 0666);
    if (fd != -1) {
        rres = read(fd, ccn_charbuf_reserve(cb, 80), 80);
        if (rres > 0)
            cb->length = rres;
        close(fd);
        if (CCNSHOULDLOG(h, dfsdf, CCNL_INFO))
            ccnr_msg(h, "Last stable at %s", ccn_charbuf_as_string(cb));
    }
    for (val = 0, i = 0; i < cb->length; i++) {
        c = cb->buf[i];
        if ('0' <= c && c <= '9')
            val = val * 10 + (c - '0');
        else
            break;
    }
    if (i == 0 || i < cb->length) {
        ccnr_msg(h, "Bad stable mark - %s", ccn_charbuf_as_string(cb));
        h->stable = 0;
    }
    else {
        h->stable = val;
        unlink(ccn_charbuf_as_string(path));
    }
    ccn_charbuf_destroy(&path);
    ccn_charbuf_destroy(&cb);
}

PUBLIC void
r_store_init(struct ccnr_handle *h)
{
    struct ccn_btree *btree = NULL;
    struct ccn_btree_node *node = NULL;
    struct hashtb_param param = {0};
    int i;
    int j;
    int res;
    struct ccn_charbuf *path = NULL;
    struct ccn_charbuf *msgs = NULL;
    off_t offset;
    
    path = ccn_charbuf_create();
    param.finalize_data = h;
    param.finalize = 0;
    h->content_by_accession_tab = hashtb_create(sizeof(struct content_by_accession_entry), NULL);
    CHKPTR(h->content_by_accession_tab);
    h->btree = btree = ccn_btree_create();
    CHKPTR(btree);
    FAILIF(btree->nextnodeid != 1);
    ccn_charbuf_putf(path, "%s/index", h->directory);
    res = mkdir(ccn_charbuf_as_string(path), 0700);
    if (res != 0 && errno != EEXIST)
        r_init_fail(h, __LINE__, ccn_charbuf_as_string(path), errno);
    else {
        msgs = ccn_charbuf_create();
        btree->io = ccn_btree_io_from_directory(ccn_charbuf_as_string(path), msgs);
        if (btree->io == NULL)
            res = errno;
        if (msgs->length != 0 && CCNSHOULDLOG(h, sffdsdf, CCNL_WARNING)) {
            ccnr_msg(h, "while initializing %s - %s",
                     ccn_charbuf_as_string(path),
                     ccn_charbuf_as_string(msgs));
        }
        ccn_charbuf_destroy(&msgs);
        if (btree->io == NULL)
            r_init_fail(h, __LINE__, ccn_charbuf_as_string(path), res);
    }
    node = ccn_btree_getnode(btree, 1, 0);
    if (btree->io != NULL)
        btree->nextnodeid = btree->io->maxnodeid + 1;
    CHKPTR(node);
    if (node->buf->length == 0) {
        res = ccn_btree_init_node(node, 0, 'R', 0);
        CHKSYS(res);
    }
    ccn_charbuf_destroy(&path);
    if (h->running == -1)
        return;
    r_store_read_stable_point(h);
    h->active_in_fd = -1;
    h->active_out_fd = r_io_open_repo_data_file(h, "repoFile1", 1); /* output */
    offset = lseek(h->active_out_fd, 0, SEEK_END);
    if (offset != h->stable || node->corrupt != 0) {
        ccnr_msg(h, "Index not current - resetting");
        ccn_btree_init_node(node, 0, 'R', 0);
        node = NULL;
        ccn_btree_destroy(&h->btree);
        path = ccn_charbuf_create();
        /* Remove old index files to avoid confusion */
        for (i = 1, j = 0; i > 0 && j < 3; i++) {
            path->length = 0;
            res = ccn_charbuf_putf(path, "%s/index/%d", h->directory, i);
            if (res >= 0)
                res = unlink(ccn_charbuf_as_string(path));
            if (res < 0)
                j++;
        }
        h->btree = btree = ccn_btree_create();
        path->length = 0;
        ccn_charbuf_putf(path, "%s/index", h->directory);
        btree->io = ccn_btree_io_from_directory(ccn_charbuf_as_string(path), msgs);
        CHKPTR(btree->io);
        btree->io->maxnodeid = 0;
        btree->nextnodeid = 1;
        node = ccn_btree_getnode(btree, 1, 0);
        btree->nextnodeid = btree->io->maxnodeid + 1;
        ccn_btree_init_node(node, 0, 'R', 0);
        h->stable = 0;
        h->active_in_fd = r_io_open_repo_data_file(h, "repoFile1", 0); /* input */
    }
    if (CCNSHOULDLOG(h, weuyg, CCNL_FINEST)) {
        res = ccn_btree_check(btree, NULL);
        ccnr_msg(h, "ccn_btree_check returned %d", res);
        if (res < 0)
            r_init_fail(h, __LINE__, "index is corrupt", res);
    }
    btree->full = 1999;
}

PUBLIC int
r_store_final(struct ccnr_handle *h) {
    int res;
    
    res = ccn_btree_destroy(&h->btree);
    if (res < 0)
        ccnr_msg(h, "r_store_final.%d-%d Errors while closing index", __LINE__, res);
    if (res >= 0)
        res = r_store_write_stable_point(h);
    return(res);
}
    
PUBLIC struct content_entry *
r_store_content_from_accession(struct ccnr_handle *h, ccnr_accession accession)
{
    struct ccn_parsed_ContentObject obj = {0};
    struct content_entry *content = NULL;
    struct content_by_accession_entry *entry;
    const unsigned char *content_base = NULL;
    int res;
    ccnr_accession acc;
    
    if (accession == CCNR_NULL_ACCESSION)
        return(NULL);
    entry = hashtb_lookup(h->content_by_accession_tab,
                          &accession, sizeof(accession));
    if (entry != NULL) {
        h->content_from_accession_hits++;
        return(entry->content);
    }
    h->content_from_accession_misses++;
    content = calloc(1, sizeof(*content));
    CHKPTR(content);
    content->cookie = 0;
    content->accession = accession;
    content->cob = NULL;
    content->size = 0;
    content_base = r_store_content_base(h, content);
    if (content_base == NULL || content->size == 0)
        goto Bail;
    res = r_store_set_flatname(h, content, &obj);
    if (res < 0) goto Bail;
    r_store_enroll_content(h, content);
    res = r_store_content_btree_insert(h, content, &obj, &acc);
    if (res < 0) goto Bail;
    if (res == 1 || CCNSHOULDLOG(h, sdf, CCNL_FINEST))
        ccnr_debug_content(h, __LINE__, "content/accession", NULL, content);
    return(content);
Bail:
    ccnr_msg(h, "r_store_content_from_accession.%d failed 0x%jx",
             __LINE__, ccnr_accession_encode(h, accession));
    r_store_forget_content(h, &content);
    return(content);
}

PUBLIC struct content_entry *
r_store_content_from_cookie(struct ccnr_handle *h, ccnr_cookie cookie)
{
    struct content_entry *ans = NULL;
    
    if (cookie < h->cookie_base)
        ans = NULL;
    else if (cookie < h->cookie_base + h->content_by_cookie_window) {
        ans = h->content_by_cookie[cookie - h->cookie_base];
        if (ans != NULL && ans->cookie != cookie)
            ans = NULL;
    }
    return(ans);
}

static void
cleanout_stragglers(struct ccnr_handle *h)
{
    struct content_entry **a = h->content_by_cookie;
    unsigned n_direct;
    unsigned n_occupied;
    unsigned window;
    unsigned i;
    
    if (1) {
        /* do not use when skiplist-based */
        return;
    }
    if (h->cookie <= h->cookie_base || a[0] == NULL)
        return;
    n_direct = h->cookie - h->cookie_base;
    if (n_direct < 1000)
        return;
    n_occupied = hashtb_n(h->content_by_accession_tab); // XXX - wrong
    if (n_occupied >= (n_direct / 8))
        return;
    /* The direct lookup table is too sparse, so toss the stragglers */
    window = h->content_by_cookie_window;
    for (i = 0; i < window; i++) {
        if (a[i] != NULL) {
            if (n_occupied >= ((window - i) / 8))
                break;
            r_store_forget_content(h, &(a[i]));
            n_occupied -= 1;
        }
    }
}

static int
cleanout_empties(struct ccnr_handle *h)
{
    unsigned i = 0;
    unsigned j = 0;
    struct content_entry **a = h->content_by_cookie;
    unsigned window = h->content_by_cookie_window;
    if (a == NULL)
        return(-1);
    cleanout_stragglers(h);
    while (i < window && a[i] == NULL)
        i++;
    if (i == 0)
        return(-1);
    h->cookie_base += i;
    while (i < window)
        a[j++] = a[i++];
    while (j < window)
        a[j++] = NULL;
    return(0);
}

/**
 * This makes a cookie for content, and, if it has an accession number already,
 * enters it into the content_by_accession_tab.  Does not index by name.
 */
PUBLIC ccnr_cookie
r_store_enroll_content(struct ccnr_handle *h, struct content_entry *content)
{
    unsigned new_window;
    struct content_entry **new_array;
    struct content_entry **old_array;
    unsigned i = 0;
    unsigned j = 0;
    unsigned window;
    
    window = h->content_by_cookie_window;
    content->cookie = ++(h->cookie);
    if ((content->cookie - h->cookie_base) >= window &&
        cleanout_empties(h) < 0) {
        if (content->cookie < h->cookie_base) {
            /* Did we wrap? */
            return(0);
        }
        window = h->content_by_cookie_window;
        old_array = h->content_by_cookie;
        new_window = ((window + 20) * 3 / 2);
        if (new_window < window)
            return(0);
        new_array = calloc(new_window, sizeof(new_array[0]));
        if (new_array == NULL)
            return(0);
        while (i < h->content_by_cookie_window && old_array[i] == NULL)
            i++;
        h->cookie_base += i;
        h->content_by_cookie = new_array;
        while (i < h->content_by_cookie_window)
            new_array[j++] = old_array[i++];
        h->content_by_cookie_window = new_window;
        free(old_array);
    }
    h->content_by_cookie[content->cookie - h->cookie_base] = content;
    
    if (content->accession != CCNR_NULL_ACCESSION) {
        struct hashtb_enumerator ee;
        struct hashtb_enumerator *e = &ee;
        ccnr_accession accession = content->accession;
        struct content_by_accession_entry *entry = NULL;
        hashtb_start(h->content_by_accession_tab, e);
        hashtb_seek(e, &accession, sizeof(accession), 0);
        entry = e->data;
        if (entry != NULL)
            entry->content = content;
        hashtb_end(e);
        content->flags |= CCN_CONTENT_ENTRY_STABLE;
    }
    return(content->cookie);
}

/** @returns 2 if content was added to index, 1 if it was there but had no accession, 0 if it was already there, -1 for error */
static int
r_store_content_btree_insert(struct ccnr_handle *h,
                             struct content_entry *content,
                             struct ccn_parsed_ContentObject *pco,
                             ccnr_accession *accp)
{
    const unsigned char *content_base = NULL;
    struct ccn_btree *btree = NULL;
    struct ccn_btree_node *leaf = NULL;
    struct ccn_btree_node *node = NULL;
    struct ccn_charbuf *flat = NULL;
    int i;
    int limit;
    int res;

    btree = h->btree;
    if (btree == NULL)
        return(-1);
    flat = content->flatname;
    if (flat == NULL)
        return(-1);
    res = ccn_btree_lookup(h->btree, flat->buf, flat->length, &leaf);
    if (res < 0)
        return(-1);
    i = CCN_BT_SRCH_INDEX(res);
    if (CCN_BT_SRCH_FOUND(res)) {
        *accp = ccnr_accession_decode(h, ccn_btree_content_cobid(leaf, i));
        return(*accp == CCNR_NULL_ACCESSION);
    }
    else {
        content_base = r_store_content_base(h, content);
        if (content_base == NULL)
            return(-1);
        res = ccn_btree_insert_content(leaf, i,
                                       ccnr_accession_encode(h, content->accession),
                                       content_base,
                                       pco,
                                       content->flatname);
        if (res < 0)
            return(-1);
        if (res > btree->full) {
            res = ccn_btree_split(btree, leaf);
            for (limit = 100; res >= 0 && btree->nextsplit != 0; limit--) {
                if (limit == 0) abort();
                node = ccn_btree_getnode(btree, btree->nextsplit, 0);
                if (node == NULL)
                    return(-1);
                res = ccn_btree_split(btree, node);
            }
        }
        
        *accp = content->accession;
        return(2);
    }
}

static void
content_skiplist_remove(struct ccnr_handle *h, struct content_entry *content)
{
//    int i;
//    int d;
//    struct ccn_indexbuf *pred[CCN_SKIPLIST_MAX_DEPTH] = {NULL};
//    if (content->skiplinks == NULL)
//        return;
//    content_skiplist_findbefore(h, content->flatname, content, pred);
//    d = content->skiplinks->n;
//    if (h->skiplinks->n < d) abort();
//    for (i = 0; i < d; i++)
//        pred[i]->buf[i] = content->skiplinks->buf[i];
//    ccn_indexbuf_destroy(&content->skiplinks);
}

PUBLIC void
r_store_forget_content(struct ccnr_handle *h, struct content_entry **pentry)
{
    unsigned i;
    struct content_entry *entry = *pentry;
    
    if (entry == NULL)
        return;
    *pentry = NULL;
    if ((entry->flags & CCN_CONTENT_ENTRY_STALE) != 0)
        h->n_stale--;
    if (CCNSHOULDLOG(h, LM_4, CCNL_INFO))
        ccnr_debug_content(h, __LINE__, "remove", NULL, entry);
    /* Unlink from skiplist, if it is there */
    content_skiplist_remove(h, entry);
    /* Remove the cookie reference */
    i = entry->cookie - h->cookie_base;
    if (i < h->content_by_cookie_window && h->content_by_cookie[i] == entry)
        h->content_by_cookie[i] = NULL;
    entry->cookie = 0;
    /* Remove the accession reference */
    if (entry->accession != CCNR_NULL_ACCESSION) {
        struct hashtb_enumerator ee;
        struct hashtb_enumerator *e = &ee;
        hashtb_start(h->content_by_accession_tab, e);
        if (hashtb_seek(e, &entry->accession, sizeof(entry->accession), 0) ==
            HT_NEW_ENTRY) {
            ccnr_msg(h, "orphaned content %llu",
                     (unsigned long long)(entry->accession));
            hashtb_delete(e);
            hashtb_end(e);
            return;
        }
        hashtb_delete(e);
        hashtb_end(e);
        entry->accession = CCNR_NULL_ACCESSION;
        if (entry->cob != NULL)
            h->cob_count--;
    }
    /* Clean up allocated subfields */
    ccn_charbuf_destroy(&entry->flatname);
    ccn_charbuf_destroy(&entry->cob);
    free(entry);
}

/**
 *  Get a handle on the content object that matches key, or if there is
 * no match, the one that would come just after it.
 */
static struct content_entry *    
r_store_look(struct ccnr_handle *h, const unsigned char *key, size_t size)
{
    struct content_entry *content = NULL;
    struct ccn_btree_node *leaf = NULL;
    ccnr_accession accession;
    int ndx;
    int res;

    res = ccn_btree_lookup(h->btree, key, size, &leaf);
    if (res >= 0) {
        ndx = CCN_BT_SRCH_INDEX(res);
        if (ndx == ccn_btree_node_nent(leaf)) {
            res = ccn_btree_next_leaf(h->btree, leaf, &leaf);
            if (res <= 0)
                return(NULL);
            ndx = 0;
        }
        accession = ccnr_accession_decode(h, ccn_btree_content_cobid(leaf, ndx));
        if (accession != CCNR_NULL_ACCESSION) {
            struct content_by_accession_entry *entry;
            entry = hashtb_lookup(h->content_by_accession_tab,
                                    &accession, sizeof(accession));
            if (entry != NULL)
                content = entry->content;
            if (content == NULL) {
                /* Construct handle without actually reading the cob */
                res = ccn_btree_content_cobsz(leaf, ndx);
                content = calloc(1, sizeof(*content));
                if (res > 0 && content != NULL) {
                    content->accession = accession;
                    content->cob = NULL;
                    content->size = res;
                    content->flatname = ccn_charbuf_create();
                    CHKPTR(content->flatname);
                    res = ccn_btree_key_fetch(content->flatname, leaf, ndx);
                    CHKRES(res);
                    r_store_enroll_content(h, content);
                }
            }
        }
    }
    return(content);
}

PUBLIC struct content_entry *
r_store_find_first_match_candidate(struct ccnr_handle *h,
                                   const unsigned char *interest_msg,
                                   const struct ccn_parsed_interest *pi)
{
    int res;
    size_t start = pi->offset[CCN_PI_B_Name];
    size_t end = pi->offset[CCN_PI_E_Name];
    struct ccn_charbuf *namebuf = NULL;
    struct ccn_charbuf *flatname = NULL;
    struct content_entry *content = NULL;
    
    flatname = ccn_charbuf_create();
    ccn_flatname_from_ccnb(flatname, interest_msg, pi->offset[CCN_PI_E]);
    if (pi->offset[CCN_PI_B_Exclude] < pi->offset[CCN_PI_E_Exclude]) {
        /* Check for <Exclude><Any/><Component>... fast case */
        struct ccn_buf_decoder decoder;
        struct ccn_buf_decoder *d;
        size_t ex1start;
        size_t ex1end;
        d = ccn_buf_decoder_start(&decoder,
                                  interest_msg + pi->offset[CCN_PI_B_Exclude],
                                  pi->offset[CCN_PI_E_Exclude] -
                                  pi->offset[CCN_PI_B_Exclude]);
        ccn_buf_advance(d);
        if (ccn_buf_match_dtag(d, CCN_DTAG_Any)) {
            ccn_buf_advance(d);
            ccn_buf_check_close(d);
            if (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
                ex1start = pi->offset[CCN_PI_B_Exclude] + d->decoder.token_index;
                ccn_buf_advance_past_element(d);
                ex1end = pi->offset[CCN_PI_B_Exclude] + d->decoder.token_index;
                if (d->decoder.state >= 0) {
                    namebuf = ccn_charbuf_create();
                    ccn_charbuf_append(namebuf,
                                       interest_msg + start,
                                       end - start);
                    namebuf->length--;
                    ccn_charbuf_append(namebuf,
                                       interest_msg + ex1start,
                                       ex1end - ex1start);
                    ccn_charbuf_append_closer(namebuf);
                    res = ccn_flatname_append_from_ccnb(flatname,
                                                        interest_msg + ex1start,
                                                        ex1end - ex1start,
                                                        0, 1);
                    if (res != 1)
                        ccnr_debug_ccnb(h, __LINE__, "fastex_bug", NULL,
                                        namebuf->buf, namebuf->length);
                    if (CCNSHOULDLOG(h, LM_8, CCNL_FINER))
                        ccnr_debug_ccnb(h, __LINE__, "fastex", NULL,
                                        namebuf->buf, namebuf->length);
                }
            }
        }
    }
    content = r_store_look(h, flatname->buf, flatname->length);
    ccn_charbuf_destroy(&namebuf);
    ccn_charbuf_destroy(&flatname);
    return(content);
}

PUBLIC int
r_store_content_matches_interest_prefix(struct ccnr_handle *h,
                                struct content_entry *content,
                                const unsigned char *interest_msg,
                                size_t interest_size)
{
    struct ccn_charbuf *flatname = ccn_charbuf_create();
    int ans;
    int cmp;

    ccn_flatname_from_ccnb(flatname, interest_msg, interest_size);
    cmp = ccn_flatname_charbuf_compare(flatname, content->flatname);
    ans = (cmp == 0 || cmp == -9999);
    ccn_charbuf_destroy(&flatname);
    return(ans);
}

PUBLIC struct content_entry *
r_store_content_next(struct ccnr_handle *h, struct content_entry *content)
{
    if (content == NULL)
        return(0);
    /* We need to go past the current name, so make sure there is a 0 byte */
    ccn_charbuf_as_string(content->flatname);
    content = r_store_look(h, content->flatname->buf, content->flatname->length + 1);
    return(content);
}

PUBLIC struct content_entry *
r_store_next_child_at_level(struct ccnr_handle *h,
                    struct content_entry *content, int level)
{
    struct content_entry *next = NULL;
    struct ccn_charbuf *name;
    struct ccn_charbuf *flatname = NULL;
    int res;
    
    if (content == NULL)
        return(NULL);
    name = ccn_charbuf_create();
    ccn_name_init(name);
    res = ccn_name_append_flatname(name,
                                   content->flatname->buf,
                                   content->flatname->length, 0, level + 1);
    if (res < level)
        goto Bail;
    if (res == level)
        res = ccn_name_append(name, NULL, 0);
    else if (res == level + 1)
        res = ccn_name_next_sibling(name); // XXX - would be nice to have a flatname version of this
    if (res < 0)
        goto Bail;
    if (CCNSHOULDLOG(h, LM_8, CCNL_FINER))
        ccnr_debug_ccnb(h, __LINE__, "child_successor", NULL,
                        name->buf, name->length);
    flatname = ccn_charbuf_create();
    ccn_flatname_from_ccnb(flatname, name->buf, name->length);
    next = r_store_look(h, flatname->buf, flatname->length);
    if (next == content) {
        // XXX - I think this case should not occur, but just in case, avoid a loop.
        ccnr_debug_content(h, __LINE__, "urp", NULL, next);
        next = NULL;
    }
Bail:
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&flatname);
    return(next);
}

PUBLIC struct content_entry *
r_store_lookup(struct ccnr_handle *h,
               const unsigned char *msg,
               const struct ccn_parsed_interest *pi,
               struct ccn_indexbuf *comps)
{
    struct content_entry *content = NULL;
    struct content_entry *last_match = NULL;
    int try;
    size_t size = pi->offset[CCN_PI_E];
    const unsigned char *content_msg = NULL;
    
    content = r_store_find_first_match_candidate(h, msg, pi);
    if (content != NULL && CCNSHOULDLOG(h, LM_8, CCNL_FINER))
        ccnr_debug_content(h, __LINE__, "first_candidate", NULL,
                           content);
    if (content != NULL &&
        !r_store_content_matches_interest_prefix(h, content, msg, size)) {
            if (CCNSHOULDLOG(h, LM_8, CCNL_FINER))
                ccnr_debug_ccnb(h, __LINE__, "prefix_mismatch", NULL,
                                msg, size);
            content = NULL;
        }
    for (try = 0; content != NULL; try++) {
        content_msg = r_store_content_base(h, content);
        if (ccn_content_matches_interest(content_msg,
                                         content->size,
                                         1, NULL, msg, size, pi)) {
                if (CCNSHOULDLOG(h, LM_8, CCNL_FINEST))
                    ccnr_debug_content(h, __LINE__, "matches", NULL, content);
                if ((pi->orderpref & 1) == 0) // XXX - should be symbolic
                    break;
                last_match = content;
                content = r_store_next_child_at_level(h, content, comps->n - 1);
                goto check_next_prefix;
            }
        content = r_store_content_next(h, content);
    check_next_prefix:
        if (content != NULL &&
            !r_store_content_matches_interest_prefix(h, content, msg, size))
                content = NULL;
    }
    if (last_match != NULL)
        content = last_match;
    return(content);
}

/**
 * Find the first content handle that matches the prefix given by the namish,
 * which may be a Name, Interest, ContentObject, ...
 *
 * Does not check the other parts of namish, in particular, does not generate
 * the digest component of a ContentObject.
 */
PUBLIC struct content_entry *
r_store_lookup_ccnb(struct ccnr_handle *h,
                    const unsigned char *namish, size_t size)
{
    struct content_entry *content = NULL;
    struct ccn_charbuf *flatname = NULL;
    int res;
    
    flatname = ccn_charbuf_create();
    if (flatname == NULL)
        goto Bail;
    res = ccn_flatname_from_ccnb(flatname, namish, size);
    if (res < 0)
        goto Bail;
    content = r_store_look(h, flatname->buf, flatname->length);
    if (content != NULL) {
        res = ccn_flatname_charbuf_compare(flatname, content->flatname);
        if (res == 0 || res == -9999) {
            /* prefix matches */
        }
        else
            content = NULL;
    }
Bail:
    ccn_charbuf_destroy(&flatname);
    return(content);
}

/**
 * Mark content as stale
 */
PUBLIC void
r_store_mark_stale(struct ccnr_handle *h, struct content_entry *content)
{
    ccnr_cookie cookie = content->cookie;
    if ((content->flags & CCN_CONTENT_ENTRY_STALE) != 0)
        return;
    if (CCNSHOULDLOG(h, LM_4, CCNL_INFO))
            ccnr_debug_content(h, __LINE__, "stale", NULL, content);
    content->flags |= CCN_CONTENT_ENTRY_STALE;
    h->n_stale++;
    if (cookie < h->min_stale)
        h->min_stale = cookie;
    if (cookie > h->max_stale)
        h->max_stale = cookie;
}

/**
 * Scheduled event that makes content stale when its FreshnessSeconds
 * has expired.
 */
static int
expire_content(struct ccn_schedule *sched,
               void *clienth,
               struct ccn_scheduled_event *ev,
               int flags)
{
    struct ccnr_handle *h = clienth;
    ccnr_cookie cookie = ev->evint;
    struct content_entry *content = NULL;
    if ((flags & CCN_SCHEDULE_CANCEL) != 0)
        return(0);
    content = r_store_content_from_cookie(h, cookie);
    if (content != NULL)
        r_store_mark_stale(h, content);
    return(0);
}

/**
 * Schedules content expiration based on its FreshnessSeconds.
 *
 */
PUBLIC void
r_store_set_content_timer(struct ccnr_handle *h, struct content_entry *content,
                  struct ccn_parsed_ContentObject *pco)
{
    int seconds = 0;
    int microseconds = 0;
    size_t start = pco->offset[CCN_PCO_B_FreshnessSeconds];
    size_t stop  = pco->offset[CCN_PCO_E_FreshnessSeconds];
    const unsigned char *content_msg = NULL;
    if (start == stop)
        return;
    content_msg = r_store_content_base(h, content);
    seconds = ccn_fetch_tagged_nonNegativeInteger(
                CCN_DTAG_FreshnessSeconds,
                content_msg,
                start, stop);
    if (seconds <= 0)
        return;
    if (seconds > ((1U<<31) / 1000000)) {
        ccnr_debug_content(h, __LINE__, "FreshnessSeconds_too_large", NULL,
                           content);
        return;
    }
    microseconds = seconds * 1000000;
    ccn_schedule_event(h->sched, microseconds,
                       &expire_content, NULL, content->cookie);
}

/**
 * Parses content object and sets content->flatname
 */
static int
r_store_set_flatname(struct ccnr_handle *h, struct content_entry *content,
                     struct ccn_parsed_ContentObject *pco)
{
    int res;
    struct ccn_charbuf *flatname = NULL;
    const unsigned char *msg = NULL;
    size_t size;
    
    msg = r_store_content_base(h, content);
    size = content->size;
    if (msg == NULL)
        goto Bail;
    flatname = ccn_charbuf_create();
    if (flatname == NULL)
        goto Bail;    
    res = ccn_parse_ContentObject(msg, size, pco, NULL);
    if (res < 0) {
        ccnr_msg(h, "error parsing ContentObject - code %d", res);
        goto Bail;
    }
    ccn_digest_ContentObject(msg, pco);
    if (pco->digest_bytes != 32)
        goto Bail;
    res = ccn_flatname_from_ccnb(flatname, msg, size);
    if (res < 0) goto Bail;
    res = ccn_flatname_append_component(flatname, pco->digest, pco->digest_bytes);
    if (res < 0) goto Bail;
    content->flatname = flatname;
    flatname = NULL;
    return(0);
Bail:
    ccn_charbuf_destroy(&flatname);
    return(-1);
}

PUBLIC struct content_entry *
process_incoming_content(struct ccnr_handle *h, struct fdholder *fdholder,
                         unsigned char *msg, size_t size)
{
    struct ccn_parsed_ContentObject obj = {0};
    int res;
    struct content_entry *content = NULL;
    ccnr_accession accession = CCNR_NULL_ACCESSION;
    
    content = calloc(1, sizeof(*content));
    if (content == NULL)
        goto Bail;    
    content->cob = ccn_charbuf_create();
    if (content->cob == NULL)
        goto Bail;    
    res = ccn_charbuf_append(content->cob, msg, size);
    if (res < 0) goto Bail;
    content->size = size;
    res = r_store_set_flatname(h, content, &obj);
    if (res < 0) goto Bail;
    ccnr_meter_bump(h, fdholder->meter[FM_DATI], 1);
    content->accession = CCNR_NULL_ACCESSION;
    r_store_enroll_content(h, content);
    if (CCNSHOULDLOG(h, LM_4, CCNL_INFO))
        ccnr_debug_content(h, __LINE__, "content_from", fdholder, content);
        res = r_store_content_btree_insert(h, content, &obj, &accession);
        if (res < 0) goto Bail;
        if (res == 0) {
            /* Content was there, with an accession */
            if (CCNSHOULDLOG(h, LM_4, CCNL_FINER))
                ccnr_debug_content(h, __LINE__, "content_duplicate",
                                   fdholder, content);
            h->content_dups_recvd++;
            r_store_forget_content(h, &content);
            content = r_store_content_from_accession(h, accession);
            if (content == NULL)
                goto Bail;
        }
        r_store_set_content_timer(h, content, &obj);
        if ((fdholder->flags & CCNR_FACE_REPODATA) == 0)
            r_proto_initiate_key_fetch(h, msg, &obj, 0, content->cookie);
    r_match_match_interests(h, content, &obj, NULL, fdholder);
    return(content);
Bail:
    r_store_forget_content(h, &content);
    return(content);
}

PUBLIC int
r_store_content_field_access(struct ccnr_handle *h,
                             struct content_entry *content,
                             enum ccn_dtag dtag,
                             const unsigned char **bufp, size_t *sizep)
{
    int res = -1;
    const unsigned char *content_msg;
    struct ccn_parsed_ContentObject pco = {0};
    
    content_msg = r_store_content_base(h, content);
    if (content_msg == NULL)
        return(-1);
    res = ccn_parse_ContentObject(content_msg, content->size, &pco, NULL);
    if (res < 0)
        return(-1);
    if (dtag == CCN_DTAG_Content)
        res = ccn_ref_tagged_BLOB(CCN_DTAG_Content, content_msg,
                                  pco.offset[CCN_PCO_B_Content],
                                  pco.offset[CCN_PCO_E_Content],
                                  bufp, sizep);
    return(res);
}

const ccnr_accession r_store_mark_repoFile1 = ((ccnr_accession)1) << 48;

PUBLIC int
r_store_set_accession_from_offset(struct ccnr_handle *h,
                                  struct content_entry *content,
                                  struct fdholder *fdholder, off_t offset)
{
    struct ccn_btree_node *leaf = NULL;
    uint_least64_t cobid;
    int ndx;
    int res;
    
    if (offset != (off_t)-1 && content->accession == CCNR_NULL_ACCESSION) {
        struct hashtb_enumerator ee;
        struct hashtb_enumerator *e = &ee;
        struct content_by_accession_entry *entry = NULL;
        int res = -1;
        
        content->flags |= CCN_CONTENT_ENTRY_STABLE;
        content->accession = ((ccnr_accession)offset) | r_store_mark_repoFile1;
        hashtb_start(h->content_by_accession_tab, e);
        hashtb_seek(e, &content->accession, sizeof(content->accession), 0);
        entry = e->data;
        if (entry != NULL) {
            entry->content = content;
            if (content->cob != NULL)
                h->cob_count++;
        }
        hashtb_end(e);
        if (content->flatname != NULL) {
            res = ccn_btree_lookup(h->btree,
                                   content->flatname->buf,
                                   content->flatname->length, &leaf);
            if (res >= 0 && CCN_BT_SRCH_FOUND(res)) {
                ndx = CCN_BT_SRCH_INDEX(res);
                cobid = ccnr_accession_encode(h, content->accession);
                res = ccn_btree_content_set_cobid(leaf, ndx, cobid);
            }
            else
                res = -1;
        }
        if (res >= 0 && content->accession >= h->notify_after) 
            r_sync_notify_content(h, 0, content);
    }
    return(res);
}

PUBLIC void
r_store_send_content(struct ccnr_handle *h, struct fdholder *fdholder, struct content_entry *content)
{
    const unsigned char *content_msg = NULL;
    off_t offset;

    if (CCNSHOULDLOG(h, LM_4, CCNL_INFO))
        ccnr_debug_content(h, __LINE__, "content_to", fdholder, content);
    content_msg = r_store_content_base(h, content);
    r_link_stuff_and_send(h, fdholder, content_msg, content->size, NULL, 0, &offset);
    if (offset != (off_t)-1 && content->accession == CCNR_NULL_ACCESSION) {
        int res;
        res = r_store_set_accession_from_offset(h, content, fdholder, offset);
        if (res == 0)
            ccnr_debug_content(h, __LINE__, "content_stored",
                               r_io_fdholder_from_fd(h, h->active_out_fd),
                               content);
    }
}

PUBLIC int
r_store_commit_content(struct ccnr_handle *h, struct content_entry *content)
{
    // XXX - here we need to check if this is something we *should* be storing, according to our policy
    if ((r_store_content_flags(content) & CCN_CONTENT_ENTRY_STABLE) == 0) {
        // Need to actually append to the active repo data file
        r_sendq_face_send_queue_insert(h, r_io_fdholder_from_fd(h, h->active_out_fd), content);
        // XXX - it would be better to do this after the write succeeds
        r_store_content_change_flags(content, CCN_CONTENT_ENTRY_STABLE, 0);
    }
    return(0);
}

PUBLIC void
ccnr_debug_content(struct ccnr_handle *h,
                   int lineno,
                   const char *msg,
                   struct fdholder *fdholder,
                   struct content_entry *content)
{
    const unsigned char *content_msg = r_store_content_base(h, content);
    ccnr_debug_ccnb(h, lineno, msg, fdholder, content_msg, content->size);
}

#undef FAILIF
#undef CHKSYS
#undef CHKRES
#undef CHKPTR
