/**
 * @file ccnr_store.c
 * 
 * Part of ccnr -  CCNx Repository Daemon.
 *
 */

/*
 * Copyright (C) 2011, 2013 Palo Alto Research Center, Inc.
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
#include <ccn/flatname.h>
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
const ccnr_accession r_store_mark_repoFile1 = ((ccnr_accession)1) << 48;

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
    unsigned char buf[CCN_MAX_MESSAGE_BYTES];
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
    cob = ccn_charbuf_create_n(content->size);
    if (cob == NULL)
        goto Bail;
    if (content->size > 0) {
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
        rres = pread(fd, buf, CCN_MAX_MESSAGE_BYTES, offset); // XXX - should be symbolic
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
    int checklimit;
    unsigned before;
    unsigned rover;
    unsigned mask;
    
    r_store_index_needs_cleaning(h);
    before = h->cob_count;
    if (before <= limit)
        return;
    checklimit = h->cookie_limit;
    mask = h->cookie_limit - 1;
    for (rover = (h->trim_rover & mask);
         checklimit > 0 && h->cob_count > limit;
         checklimit--, rover = (rover + 1) & mask) {
        content = h->content_by_cookie[rover];
        if (content != NULL)
            r_store_content_trim(h, content);
    }
    h->trim_rover = rover;
    if (CCNSHOULDLOG(h, sdf, CCNL_FINER))
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
    int fd, res;
    
    path = ccn_charbuf_create();
    cb = ccn_charbuf_create();
    if (path == NULL || cb == NULL) {
        ccnr_msg(h, "memory allocation failure writing stable mark");
        goto Bail;
    }
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
        res = write(fd, cb->buf, cb->length);
        close(fd);
        if (res != cb->length) {
            unlink(ccn_charbuf_as_string(path));
            ccnr_msg(h, "cannot write stable mark %s: unexpected write result %d",
                     ccn_charbuf_as_string(path), res);
        }
        if (CCNSHOULDLOG(h, dfsdf, CCNL_INFO))
            ccnr_msg(h, "Index marked stable - %s", ccn_charbuf_as_string(cb));
    }
Bail:
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

/**
 * Log a bit if we are taking a while to re-index.
 */
static int
r_store_reindexing(struct ccn_schedule *sched,
                   void *clienth,
                   struct ccn_scheduled_event *ev,
                   int flags)
{
    struct ccnr_handle *h = clienth;
    struct fdholder *in = NULL;
    unsigned pct;
    
    if ((flags & CCN_SCHEDULE_CANCEL) != 0)
        return(0);
    in = r_io_fdholder_from_fd(h, h->active_in_fd);
    if (in == NULL)
        return(0);
    pct = ccnr_meter_total(in->meter[FM_BYTI]) / ((h->startupbytes / 100) + 1);
    if (pct >= 100)
        return(0);
    ccnr_msg(h, "indexing %u%% complete", pct);
    return(2000000);
}

/**
 * Select power of 2 between l and m + 1 (if possible).
 */
static unsigned
choose_limit(unsigned l, unsigned m)
{
    unsigned k;
    
    for (k = 0; k < l; k = 2 * k + 1)
        continue;
    while (k > (m | 1) || k + 1 < k)
        k >>= 1;
    return(k + 1);
}

static void
cleanup_content_entry(struct ccnr_handle *h, struct content_entry *content)
{
    unsigned i;
    
    if ((content->flags & CCN_CONTENT_ENTRY_STALE) != 0)
        h->n_stale--;
    if (CCNSHOULDLOG(h, LM_4, CCNL_FINER))
        ccnr_debug_content(h, __LINE__, "remove", NULL, content);
    /* Remove the cookie reference */
    i = content->cookie & (h->cookie_limit - 1);
    if (h->content_by_cookie[i] == content)
        h->content_by_cookie[i] = NULL;
    content->cookie = 0;
    ccn_charbuf_destroy(&content->flatname);
    if (content->cob != NULL) {
        h->cob_count--;
        ccn_charbuf_destroy(&content->cob);
    }
    free(content);    
}
static void
finalize_accession(struct hashtb_enumerator *e)
{
    struct ccnr_handle *h = hashtb_get_param(e->ht, NULL);
    struct content_by_accession_entry *entry = e->data;
    
    cleanup_content_entry(h, entry->content);
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
    param.finalize = &finalize_accession;
    
    h->cob_limit = r_init_confval(h, "CCNR_CONTENT_CACHE", 16, 2000000, 4201);
    h->cookie_limit = choose_limit(h->cob_limit, (ccnr_cookie)(~0U));
    h->content_by_cookie = calloc(h->cookie_limit, sizeof(h->content_by_cookie[0]));
    CHKPTR(h->content_by_cookie);
    h->content_by_accession_tab = hashtb_create(sizeof(struct content_by_accession_entry), &param);
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
    h->startupbytes = offset;
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
        ccn_charbuf_destroy(&path);
        if (CCNSHOULDLOG(h, dfds, CCNL_INFO))
            ccn_schedule_event(h->sched, 50000, r_store_reindexing, NULL, 0);
    }
    if (CCNSHOULDLOG(h, weuyg, CCNL_FINEST)) {
        FILE *dumpfile = NULL;
        
        path = ccn_charbuf_create();
        ccn_charbuf_putf(path, "%s/index/btree_check.out", h->directory);
        dumpfile = fopen(ccn_charbuf_as_string(path), "w");
        res = ccn_btree_check(btree, dumpfile);
        if (dumpfile != NULL) {
            fclose(dumpfile);
            dumpfile = NULL;
        }
        else
            path->length = 0;
        ccnr_msg(h, "ccn_btree_check returned %d (%s)",
                    res, ccn_charbuf_as_string(path));
        ccn_charbuf_destroy(&path);
        if (res < 0)
            r_init_fail(h, __LINE__, "index is corrupt", res);
    }
    btree->full = r_init_confval(h, "CCNR_BTREE_MAX_FANOUT", 4, 9999, 1999);
    btree->full0 = r_init_confval(h, "CCNR_BTREE_MAX_LEAF_ENTRIES", 4, 9999, 1999);
    btree->nodebytes = r_init_confval(h, "CCNR_BTREE_MAX_NODE_BYTES", 1024, 8388608, 2097152);
    btree->nodepool = r_init_confval(h, "CCNR_BTREE_NODE_POOL", 16, 2000000, 512);
    if (h->running != -1)
        r_store_index_needs_cleaning(h);
}

PUBLIC int
r_store_final(struct ccnr_handle *h, int stable) {
    int res;
    
    res = ccn_btree_destroy(&h->btree);
    if (res < 0)
        ccnr_msg(h, "r_store_final.%d-%d Errors while closing index", __LINE__, res);
    if (res >= 0 && stable)
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
    
    ans = h->content_by_cookie[cookie & (h->cookie_limit - 1)];
    if (ans != NULL && ans->cookie != cookie)
        ans = NULL;
    return(ans);
}

/**
 * This makes a cookie for content, and, if it has an accession number already,
 * enters it into the content_by_accession_tab.  Does not index by name.
 */
PUBLIC ccnr_cookie
r_store_enroll_content(struct ccnr_handle *h, struct content_entry *content)
{
    ccnr_cookie cookie;
    unsigned mask;
    
    mask = h->cookie_limit - 1;
    cookie = ++(h->cookie);
    if (cookie == 0)
        cookie = ++(h->cookie); /* Cookie numbers may wrap */
    // XXX - check for persistence here, if we add that
    r_store_forget_content(h, &(h->content_by_cookie[cookie & mask]));
    content->cookie = cookie;
    h->content_by_cookie[cookie & mask] = content;
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
    return(cookie);
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
        res = ccn_btree_prepare_for_update(h->btree, leaf);
        if (res < 0)
            return(-1);
        res = ccn_btree_insert_content(leaf, i,
                                       ccnr_accession_encode(h, content->accession),
                                       content_base,
                                       pco,
                                       content->flatname);
        if (res < 0)
            return(-1);
        if (ccn_btree_oversize(btree, leaf)) {
            res = ccn_btree_split(btree, leaf);
            for (limit = 100; res >= 0 && btree->nextsplit != 0; limit--) {
                if (limit == 0) abort();
                node = ccn_btree_getnode(btree, btree->nextsplit, 0);
                if (node == NULL)
                    return(-1);
                res = ccn_btree_split(btree, node);
            }
        }
        r_store_index_needs_cleaning(h);
        
        *accp = content->accession;
        return(2);
    }
}

/**
 *  Remove internal representation of a content object
 */
PUBLIC void
r_store_forget_content(struct ccnr_handle *h, struct content_entry **pentry)
{
    struct content_entry *entry = *pentry;
    
    if (entry == NULL)
        return;
    *pentry = NULL;
    /* Remove the accession reference */
    /* more cleanup, including the content_by_cookie cleanup,
     * is done by the finalizer for the accession hash table
     */
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
        entry->accession = CCNR_NULL_ACCESSION;
        hashtb_delete(e);
        hashtb_end(e);
    } else {
        if (CCNSHOULDLOG(h, sdf, CCNL_FINER)) {
              ccnr_debug_content(h, __LINE__, "removing unenrolled content", NULL, entry);
        }
        cleanup_content_entry(h, entry);
    }
}

/**
 *  Get a handle on the content object that matches key, or if there is
 * no match, the one that would come just after it.
 *
 * The key is in flatname format.
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

/**
 * Extract the flatname representations of the bounds for the
 * next component after the name prefix of the interest.
 * These are exclusive bounds.  The results are appended to
 * lower and upper (when not NULL).  If there is
 * no lower bound, lower will be unchanged.
 * If there is no upper bound, a sentinel value is appended to upper.
 *
 * @returns on success the number of Components in Exclude.
 *          A negative value indicates an error.
 */
static int
ccn_append_interest_bounds(const unsigned char *interest_msg,
                           const struct ccn_parsed_interest *pi,
                           struct ccn_charbuf *lower,
                           struct ccn_charbuf *upper)
{
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = NULL;
    size_t xstart = 0;
    size_t xend = 0;
    int atlower = 0;
    int atupper = 0;
    int res = 0;
    int nexcl = 0;
    
    if (pi->offset[CCN_PI_B_Exclude] < pi->offset[CCN_PI_E_Exclude]) {
        d = ccn_buf_decoder_start(&decoder,
                                  interest_msg + pi->offset[CCN_PI_B_Exclude],
                                  pi->offset[CCN_PI_E_Exclude] -
                                  pi->offset[CCN_PI_B_Exclude]);
        ccn_buf_advance(d);
        if (ccn_buf_match_dtag(d, CCN_DTAG_Any)) {
            ccn_buf_advance(d);
            ccn_buf_check_close(d);
            atlower = 1; /* look for <Exclude><Any/><Component>... case */
        }
        else if (ccn_buf_match_dtag(d, CCN_DTAG_Bloom))
            ccn_buf_advance_past_element(d);
        while (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
            nexcl++;
            xstart = pi->offset[CCN_PI_B_Exclude] + d->decoder.token_index;
            ccn_buf_advance_past_element(d);
            xend = pi->offset[CCN_PI_B_Exclude] + d->decoder.token_index;
            if (atlower && lower != NULL && d->decoder.state >= 0) {
                res = ccn_flatname_append_from_ccnb(lower,
                        interest_msg + xstart, xend - xstart, 0, 1);
                if (res < 0)
                    d->decoder.state = - __LINE__;
            }
            atlower = 0;
            atupper = 0;
            if (ccn_buf_match_dtag(d, CCN_DTAG_Any)) {
                atupper = 1; /* look for ...</Component><Any/></Exclude> case */
                ccn_buf_advance(d);
                ccn_buf_check_close(d);
            }
            else if (ccn_buf_match_dtag(d, CCN_DTAG_Bloom))
                ccn_buf_advance_past_element(d);
        }
        ccn_buf_check_close(d);
        res = d->decoder.state;
    }
    if (upper != NULL) {
        if (atupper && res >= 0)
            res = ccn_flatname_append_from_ccnb(upper,
                     interest_msg + xstart, xend - xstart, 0, 1);
        else
            ccn_charbuf_append(upper, "\377\377\377", 3);
    }
    return (res < 0 ? res : 0);
}

static struct content_entry *
r_store_lookup_backwards(struct ccnr_handle *h,
                         const unsigned char *interest_msg,
                         const struct ccn_parsed_interest *pi,
                         struct ccn_indexbuf *comps)
{
    struct content_entry *content = NULL;
    struct ccn_btree_node *leaf = NULL;
    struct ccn_charbuf *lower = NULL;
    struct ccn_charbuf *f = NULL;
    size_t size;
    size_t fsz;
    int errline = 0;
    int try = 0;
    int ndx;
    int res;
    int rnc;
    int backing;
    
    size = pi->offset[CCN_PI_E];
    f = ccn_charbuf_create_n(pi->offset[CCN_PI_E_Name]);
    lower = ccn_charbuf_create();
    if (f == NULL || lower == NULL) { errline = __LINE__; goto Done; };
    rnc = ccn_flatname_from_ccnb(f, interest_msg, size);
    fsz = f->length;
    res = ccn_charbuf_append_charbuf(lower, f);
    if (rnc < 0 || res < 0) { errline = __LINE__; goto Done; };
    res = ccn_append_interest_bounds(interest_msg, pi, lower, f);
    if (res < 0) { errline = __LINE__; goto Done; };
    /* Now f is beyond any we care about */
    res = ccn_btree_lookup(h->btree, f->buf, f->length, &leaf);
    if (res < 0) { errline = __LINE__; goto Done; };
    ndx = CCN_BT_SRCH_INDEX(res);
    for (try = 1, backing = 1; backing; try++) {
        if (ndx == 0) {
            res = ccn_btree_prev_leaf(h->btree, leaf, &leaf);
            if (res != 1) goto Done;
            ndx = ccn_btree_node_nent(leaf);
            if (ndx <= 0) goto Done;
        }
        ndx -= 1;
        res = ccn_btree_compare(lower->buf, lower->length, leaf, ndx);
        if (res > 0 || (res == 0 && lower->length > fsz))
            goto Done;
        f->length = 0;
        res = ccn_btree_key_fetch(f, leaf, ndx);
        if (res < 0) { errline = __LINE__; goto Done; }
        if (f->length > fsz) {
            rnc = ccn_flatname_next_comp(f->buf + fsz, f->length - fsz);
            if (rnc < 0) { errline = __LINE__; goto Done; };
            f->length = fsz + CCNFLATDELIMSZ(rnc) + CCNFLATDATASZ(rnc);
            res = ccn_btree_lookup(h->btree, f->buf, f->length, &leaf);
            if (res < 0) { errline = __LINE__; goto Done; };
            ndx = CCN_BT_SRCH_INDEX(res);
            if (ndx >= ccn_btree_node_nent(leaf)) {
                res = ccn_btree_next_leaf(h->btree, leaf, &leaf);
                if (res != 1) { errline = __LINE__; goto Done; }
                ndx = 0;
                /* avoid backing up from here as we just took a step forward */
                backing = 0;
            }
        }
        else if (f->length < fsz) { errline = __LINE__; goto Done; }
        res = ccn_btree_match_interest(leaf, ndx, interest_msg, pi, f);
        if (res == 1) {
            res = ccn_btree_key_fetch(f, leaf, ndx);
            if (res < 0) { errline = __LINE__; goto Done; }
            content = r_store_look(h, f->buf, f->length);
            goto Done;
        }
        else if (res != 0) { errline = __LINE__; goto Done; }
    }
Done:
    if (errline != 0)
        ccnr_debug_ccnb(h, errline, "match_error", NULL, interest_msg, size);
    else {
        if (content != NULL) {
            h->count_rmc_found += 1;
            h->count_rmc_found_iters += try;
        }
        else {
            h->count_rmc_notfound += 1;
            h->count_rmc_notfound_iters += try;
        }
    }
    ccn_charbuf_destroy(&lower);
    ccn_charbuf_destroy(&f);
    return(content);
}

PUBLIC struct content_entry *
r_store_find_first_match_candidate(struct ccnr_handle *h,
                                   const unsigned char *interest_msg,
                                   const struct ccn_parsed_interest *pi)
{
    struct ccn_charbuf *flatname = NULL;
    struct content_entry *content = NULL;
    
    flatname = ccn_charbuf_create_n(pi->offset[CCN_PI_E]);
    ccn_flatname_from_ccnb(flatname, interest_msg, pi->offset[CCN_PI_E]);
    ccn_append_interest_bounds(interest_msg, pi, flatname, NULL);
    content = r_store_look(h, flatname->buf, flatname->length);
    ccn_charbuf_destroy(&flatname);
    return(content);
}

PUBLIC int
r_store_content_matches_interest_prefix(struct ccnr_handle *h,
                                struct content_entry *content,
                                const unsigned char *interest_msg,
                                size_t interest_size)
{
    struct ccn_charbuf *flatname = ccn_charbuf_create_n(interest_size);
    int ans;
    int cmp;

    ccn_flatname_from_ccnb(flatname, interest_msg, interest_size);
    cmp = ccn_flatname_charbuf_compare(flatname, content->flatname);
    ans = (cmp == 0 || cmp == CCN_STRICT_PREFIX);
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
    struct ccn_btree_node *leaf = NULL;
    ccnr_cookie last_match = 0;
    ccnr_accession last_match_acc = CCNR_NULL_ACCESSION;
    struct ccn_charbuf *scratch = NULL;
    size_t size = pi->offset[CCN_PI_E];
    int ndx;
    int res;
    int try;
    
    if ((pi->orderpref & 1) == 1) {
        content = r_store_lookup_backwards(h, msg, pi, comps);
        return(content);
    }
    
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
    scratch = ccn_charbuf_create();
    for (try = 1; content != NULL; try++) {
        res = ccn_btree_lookup(h->btree,
                               content->flatname->buf,
                               content->flatname->length,
                               &leaf);
        if (CCN_BT_SRCH_FOUND(res) == 0) {
            ccnr_debug_content(h, __LINE__, "impossible", NULL, content);
            content = NULL;
            break;
        }
        ndx = CCN_BT_SRCH_INDEX(res);
        res = ccn_btree_match_interest(leaf, ndx, msg, pi, scratch);
        if (res == -1) {
            ccnr_debug_ccnb(h, __LINE__, "match_error", NULL, msg, size);
            content = NULL;
            break;
        }
        if (res == 1) {
            if ((pi->orderpref & 1) == 0) // XXX - should be symbolic
                break;
            last_match = content->cookie;
            last_match_acc = content->accession;
            content = r_store_next_child_at_level(h, content, comps->n - 1);
        }
        else
            content = r_store_content_next(h, content);
        if (content != NULL &&
            !r_store_content_matches_interest_prefix(h, content, msg, size))
                content = NULL;
    }
    if (last_match != 0) {
        content = r_store_content_from_cookie(h, last_match);
        if (content == NULL)
            content = r_store_content_from_accession(h, last_match_acc);
    }
    ccn_charbuf_destroy(&scratch);
    if (content != NULL) {
        h->count_lmc_found += 1;
        h->count_lmc_found_iters += try;
    }
    else {
        h->count_lmc_notfound += 1;
        h->count_lmc_notfound_iters += try;
    }
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
        if (res == 0 || res == CCN_STRICT_PREFIX) {
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

/**
 *  Get the flatname associated with content
 *
 * @returns flatname in a charbuf, which should be treated as read-only.
 */
PUBLIC struct ccn_charbuf *
r_store_content_flatname(struct ccnr_handle *h, struct content_entry *content)
{
    return(content->flatname);
}

PUBLIC struct content_entry *
process_incoming_content(struct ccnr_handle *h, struct fdholder *fdholder,
                         unsigned char *msg, size_t size, off_t *offsetp)
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
    if (fdholder->filedesc == h->active_in_fd && offsetp != NULL) {
        // if we are reading from repoFile1 to rebuild the index we already know
        // the accession number
        content->accession = ((ccnr_accession)*offsetp) | r_store_mark_repoFile1;
    }
    r_store_enroll_content(h, content);
    if (CCNSHOULDLOG(h, LM_4, CCNL_FINE))
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


PUBLIC int
r_store_set_accession_from_offset(struct ccnr_handle *h,
                                  struct content_entry *content,
                                  struct fdholder *fdholder, off_t offset)
{
    struct ccn_btree_node *leaf = NULL;
    uint_least64_t cobid;
    int ndx;
    int res = -1;
    
    if (offset != (off_t)-1 && content->accession == CCNR_NULL_ACCESSION) {
        struct hashtb_enumerator ee;
        struct hashtb_enumerator *e = &ee;
        struct content_by_accession_entry *entry = NULL;
        
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
                ccn_btree_prepare_for_update(h->btree, leaf);
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

    content_msg = r_store_content_base(h, content);
    if (content_msg == NULL) {
        ccnr_debug_content(h, __LINE__, "content_missing", fdholder, content);
        return;        
    }
    if (CCNSHOULDLOG(h, LM_4, CCNL_FINE))
        ccnr_debug_content(h, __LINE__, "content_to", fdholder, content);
    r_link_stuff_and_send(h, fdholder, content_msg, content->size, NULL, 0, &offset);
    if (offset != (off_t)-1 && content->accession == CCNR_NULL_ACCESSION) {
        int res;
        res = r_store_set_accession_from_offset(h, content, fdholder, offset);
        if (res == 0)
            if (CCNSHOULDLOG(h, LM_4, CCNL_FINE))
                ccnr_debug_content(h, __LINE__, "content_stored",
                                   r_io_fdholder_from_fd(h, h->active_out_fd),
                                   content);
    }
}

PUBLIC int
r_store_commit_content(struct ccnr_handle *h, struct content_entry *content)
{
    struct fdholder *fdholder = r_io_fdholder_from_fd(h, h->active_out_fd);
    // XXX - here we need to check if this is something we *should* be storing, according to our policy
    if ((r_store_content_flags(content) & CCN_CONTENT_ENTRY_STABLE) == 0) {
        if (fdholder == NULL)
        {
            ccnr_msg(h, "Repository shutting down due to error storing content.");
            h->running = 0;
            return(-1);
        }
        r_store_send_content(h, r_io_fdholder_from_fd(h, h->active_out_fd), content);
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
    struct ccn_charbuf *c = ccn_charbuf_create();
    struct ccn_charbuf *flat = content->flatname;
    
    if (c == NULL)
        return;
    ccn_charbuf_putf(c, "debug.%d %s ", lineno, msg);
    if (fdholder != NULL)
        ccn_charbuf_putf(c, "%u ", fdholder->filedesc);
    if (flat != NULL)
        ccn_uri_append_flatname(c, flat->buf, flat->length, 1);
    ccn_charbuf_putf(c, " (%d bytes)", content->size);
    ccnr_msg(h, "%s", ccn_charbuf_as_string(c));
    ccn_charbuf_destroy(&c);
}

/** Number of btree index writes to do in a batch */
#define CCN_BT_CLEAN_BATCH 3
/** Approximate delay between batches of btree index writes */
#define CCN_BT_CLEAN_TICK_MICROS 65536
static int
r_store_index_cleaner(struct ccn_schedule *sched,
    void *clienth,
    struct ccn_scheduled_event *ev,
    int flags)
{
    struct ccnr_handle *h = clienth;
    struct hashtb_enumerator ee;
    struct hashtb_enumerator *e = &ee;
    struct ccn_btree_node *node = NULL;
    int k;
    int res;
    int overquota;
    
    (void)(sched);
    (void)(ev);
    if ((flags & CCN_SCHEDULE_CANCEL) != 0 ||
         h->btree == NULL || h->btree->io == NULL) {
        h->index_cleaner = NULL;
        ccn_indexbuf_destroy(&h->toclean);
        return(0);
    }
    /* First, work on cleaning the things we already know need cleaning */
    if (h->toclean != NULL) {
        for (k = 0; k < CCN_BT_CLEAN_BATCH && h->toclean->n > 0; k++) {
            node = ccn_btree_rnode(h->btree, h->toclean->buf[--h->toclean->n]);
            if (node != NULL && node->iodata != NULL) {
                res = ccn_btree_chknode(node); /* paranoia */
                if (res < 0 || CCNSHOULDLOG(h, sdfsdffd, CCNL_FINER))
                    ccnr_msg(h, "write index node %u (err %d)",
                             (unsigned)node->nodeid, node->corrupt);
                if (res >= 0) {
                    if (node->clean != node->buf->length)
                        res = h->btree->io->btwrite(h->btree->io, node);
                    if (res < 0)
                        ccnr_msg(h, "failed to write index node %u",
                                 (unsigned)node->nodeid);
                    else
                        node->clean = node->buf->length;
                }
                if (res >= 0 && node->iodata != NULL && node->activity == 0) {
                    if (CCNSHOULDLOG(h, sdfsdffd, CCNL_FINER))
                        ccnr_msg(h, "close index node %u",
                                 (unsigned)node->nodeid);
                    res = ccn_btree_close_node(h->btree, node);
                }
            }
        }
        if (h->toclean->n > 0)
            return(nrand48(h->seed) % (2U * CCN_BT_CLEAN_TICK_MICROS) + 500);
    }
    /* Sweep though and find the nodes that still need cleaning */
    overquota = 0;
    if (h->btree->nodepool >= 16)
        overquota = hashtb_n(h->btree->resident) - h->btree->nodepool;
    hashtb_start(h->btree->resident, e);
    for (node = e->data; node != NULL; node = e->data) {
        if (overquota > 0 &&
              node->activity == 0 &&
              node->iodata == NULL &&
              node->clean == node->buf->length) {
            overquota -= 1;
            if (CCNSHOULDLOG(h, sdfsdffd, CCNL_FINEST))
                ccnr_msg(h, "prune index node %u",
                         (unsigned)node->nodeid);
            hashtb_delete(e);
            continue;
        }
        node->activity /= 2; /* Age the node's activity */
        if (node->clean != node->buf->length ||
            (node->iodata != NULL && node->activity == 0)) {
            if (h->toclean == NULL) {
                h->toclean = ccn_indexbuf_create();
                if (h->toclean == NULL)
                    break;
            }
            ccn_indexbuf_append_element(h->toclean, node->nodeid);
        }
        hashtb_next(e);
    }
    hashtb_end(e);
    /* If nothing to do, shut down cleaner */
    if ((h->toclean == NULL || h->toclean->n == 0) && overquota <= 0 &&
        h->btree->io->openfds <= CCN_BT_OPEN_NODES_IDLE) {
        h->btree->cleanreq = 0;
        h->index_cleaner = NULL;
        ccn_indexbuf_destroy(&h->toclean);
        if (CCNSHOULDLOG(h, sdfsdffd, CCNL_FINE))
            ccnr_msg(h, "index btree nodes all clean");
        
        return(0);
    }
    return(nrand48(h->seed) % (2U * CCN_BT_CLEAN_TICK_MICROS) + 500);
}

PUBLIC void
r_store_index_needs_cleaning(struct ccnr_handle *h)
{
    int k;
    if (h->btree != NULL && h->btree->io != NULL && h->btree->cleanreq > 0) {
        if (h->index_cleaner == NULL) {
            h->index_cleaner = ccn_schedule_event(h->sched,
                                                  CCN_BT_CLEAN_TICK_MICROS,
                                                  r_store_index_cleaner, NULL, 0);
            if (CCNSHOULDLOG(h, sdfsdffd, CCNL_FINER))
                ccnr_msg(h, "index cleaner started");
        }
        /* If necessary, clean in a hurry. */
        for (k = 30; /* Backstop to make sure we do not loop here */
             k > 0 && h->index_cleaner != NULL &&
             h->btree->io->openfds > CCN_BT_OPEN_NODES_LIMIT - 2; k--)
            r_store_index_cleaner(h->sched, h, h->index_cleaner, 0);
        if (k == 0)
            ccnr_msg(h, "index cleaner is in trouble");
    }
}

#undef FAILIF
#undef CHKSYS
#undef CHKRES
#undef CHKPTR
