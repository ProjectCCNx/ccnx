/**
 * @file apps/vlc/ccn.c
 * 
 * CCNx input module for vlc.
 *
 * Copyright (C) 2009-2012 Palo Alto Research Center, Inc.
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

/*****************************************************************************
 * Preamble
 *****************************************************************************/

#ifdef HAVE_CONFIG_H
# include "config.h"
#endif

#include <limits.h>
#include <poll.h>
#include <errno.h>

#include <vlc_common.h>
#include <vlc_plugin.h>
#include <vlc_access.h>
#include <vlc_url.h>
#include <vlc_threads.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>
#include <ccn/header.h>

/*****************************************************************************
 * Disable internationalization
 *****************************************************************************/
#define _(str) (str)
#define N_(str) (str)
/*****************************************************************************
 * Module descriptor
 *****************************************************************************/
#define CCN_FIFO_MAX_BLOCKS 8
#define CCN_VERSION_TIMEOUT 5000
#define CCN_HEADER_TIMEOUT 1000
#define CCN_DEFAULT_PREFETCH 12

#define CCN_PREFETCH_LIFETIME 1023
#define CCN_DATA_LIFETIME 1024

#define MAX_FIFO_TEXT N_("FIFO max blocks")
#define MAX_FIFO_LONGTEXT N_(						\
"Maximum number of blocks held in FIFO "			\
"used by content fetcher.")
#define PREFETCH_TEXT N_("Prefetch offset")
#define PREFETCH_LONGTEXT N_(                                          \
"Number of content objects prefetched, "                       \
"and offset from content object received for next interest.")
#define SEEKABLE_TEXT N_("CCN streams can seek")
#define SEEKABLE_LONGTEXT N_(               \
"Enable or disable seeking within a CCN stream.")

static int  CCNOpen(vlc_object_t *);
static void CCNClose(vlc_object_t *);
static block_t *CCNBlock(access_t *);
#if (VLCPLUGINVER >= 10100)
static int CCNSeek(access_t *, uint64_t);
#else
static int CCNSeek(access_t *, int64_t);
#endif
static int CCNControl(access_t *, int, va_list);

static void *ccn_event_thread(void *p_this);

vlc_module_begin();
set_shortname(N_("CCNx"));
set_description(N_("Access module for CCNx URIs"));
set_category(CAT_INPUT);
set_subcategory(SUBCAT_INPUT_ACCESS);
#if (VLCPLUGINVER < 10200)
add_integer("ccn-fifo-maxblocks", CCN_FIFO_MAX_BLOCKS, NULL,
            MAX_FIFO_TEXT, MAX_FIFO_LONGTEXT, true);
add_integer("ccn-prefetch", CCN_DEFAULT_PREFETCH, NULL,
            PREFETCH_TEXT, PREFETCH_LONGTEXT, true);
add_bool("ccn-streams-seekable", true, NULL,
         SEEKABLE_TEXT, SEEKABLE_LONGTEXT, true )
#else
add_integer("ccn-fifo-maxblocks", CCN_FIFO_MAX_BLOCKS,
            MAX_FIFO_TEXT, MAX_FIFO_LONGTEXT, true);
add_integer("ccn-prefetch", CCN_DEFAULT_PREFETCH,
            PREFETCH_TEXT, PREFETCH_LONGTEXT, true);
add_bool("ccn-streams-seekable", true,
         SEEKABLE_TEXT, SEEKABLE_LONGTEXT, true )
#endif
change_safe();
set_capability("access", 0);
add_shortcut("ccn");
add_shortcut("ccnx");
set_callbacks(CCNOpen, CCNClose);
vlc_module_end();

/*****************************************************************************
 * Local prototypes
 *****************************************************************************/
struct access_sys_t
{
    vlc_mutex_t lock;       /**< mutex protecting data shared between CCNSeek and incoming_content */
    vlc_cond_t cond;        /**< condition variable used to signal from the CCN thread */
    bool b_state_changed;   /**< indicates change in i_state */
    int i_state;            /**< values returned from the CCN thread */
    vlc_thread_t thread;    /**< thread that is running ccn_run loop */
    int64_t i_pos;          /**< byte offset in stream of next bytes to arrive over net */
    int i_chunksize;        /**< size of CCN ContentObject data blocks */
    int timeouts;           /**< number of timeouts without good data received */
    int i_fifo_max;         /**< maximum number of blocks in FIFO */
    int i_prefetch;         /**< offset for prefetching */
    block_fifo_t *p_fifo;   /**< FIFO for blocks delivered to VLC */
    struct ccn *ccn;        /**< CCN handle */
    struct ccn_closure *incoming;   /**< current closure for incoming content handling */
    struct ccn_closure *prefetch;   /**< closure for handling prefetch content */
    struct ccn_charbuf *p_name;     /**< base name for stream including version */
    struct ccn_charbuf *p_prefetch_template; /**< interest expression template */
    struct ccn_charbuf *p_data_template; /**< interest expression template */
};

static enum ccn_upcall_res
incoming_content(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info);
static enum ccn_upcall_res
discard_content(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info);
static struct ccn_charbuf *
sequenced_name(struct ccn_charbuf *basename, uintmax_t seq);

static struct ccn_charbuf *make_prefetch_template();
static struct ccn_charbuf *make_data_template();

/*****************************************************************************
 * CCNOpen: 
 *****************************************************************************/
static int
CCNOpen(vlc_object_t *p_this)
{
    access_t     *p_access = (access_t *)p_this;
    access_sys_t *p_sys = NULL;
    int i_ret = VLC_EGENERIC;
    bool b_threaded = false;
    
    /* Init p_access */
    access_InitFields(p_access);
    msg_Info(p_access, "CCN.Open called");
    ACCESS_SET_CALLBACKS(NULL, CCNBlock, CCNControl, CCNSeek);
    p_sys = calloc(1, sizeof(access_sys_t));
    if (p_sys == NULL) {
        msg_Err(p_access, "CCN.Open failed: no memory for p_sys");
        return (VLC_ENOMEM);
    }
    p_access->p_sys = p_sys;
    p_sys->p_fifo = block_FifoNew();
    if (p_sys->p_fifo == NULL) {
        msg_Err(p_access, "CCN.Open failed: no memory for block FIFO");
        free(p_sys);
        return (VLC_ENOMEM);
    }
    p_sys->i_chunksize = -1;
    p_sys->i_fifo_max = var_CreateGetInteger(p_access, "ccn-fifo-maxblocks");
    p_sys->i_prefetch = var_CreateGetInteger(p_access, "ccn-prefetch");
    msg_Info(p_access, "CCN.Open: ccn-fifo-maxblocks %d", p_sys->i_fifo_max);
    p_access->info.i_size = LLONG_MAX;	/* don't know yet, but bigger is better */
    vlc_mutex_init(&p_sys->lock);
    vlc_cond_init(&p_sys->cond);
    if (0 != vlc_clone(&(p_sys->thread), ccn_event_thread, p_access, VLC_THREAD_PRIORITY_INPUT)) {
        msg_Err(p_access, "CCN.Open failed: unable to vlc_clone for CCN run thread");
        goto exit;
    }
    b_threaded = true;
    msg_Info(p_access, "CCN.Open: waiting for CCN event thread start");
    vlc_mutex_lock(&p_sys->lock);
    while (!p_sys->b_state_changed)
        vlc_cond_wait(&p_sys->cond, &p_sys->lock);
    i_ret = p_sys->i_state;
    p_sys->b_state_changed = false;
    vlc_mutex_unlock(&p_sys->lock);
    
exit:
    if (i_ret != VLC_SUCCESS) {
        msg_Err(p_access, "CCN.Open: CCN event thread failed");
        if (b_threaded)
            vlc_join(p_sys->thread, NULL);
        vlc_cond_destroy(&p_sys->cond);
        vlc_mutex_destroy(&p_sys->lock);
        block_FifoRelease(p_sys->p_fifo);
        free(p_sys);
        p_access->p_sys = NULL;
    } else {
        msg_Info(p_access, "CCN.Open: CCN event thread started");
    }
    return (i_ret);
}

/*****************************************************************************
 * CCNClose: free unused data structures
 *****************************************************************************/
static void
CCNClose(vlc_object_t *p_this)
{
    access_t     *p_access = (access_t *)p_this;
    access_sys_t *p_sys = p_access->p_sys;

    msg_Info(p_access, "CCN.Close called");
    /* indicate exit required, then clear the FIFO so we will wake from
     * block_FifoPace and wait for the thread to signal it is exiting.
     */
    p_access->b_die = true;
    /* incoming_content may be blocked on the fifo with the lock held */
    if (p_sys->p_fifo)
        block_FifoEmpty(p_sys->p_fifo);
    vlc_mutex_lock(&p_sys->lock);
    while (!p_sys->b_state_changed)
        vlc_cond_wait(&p_sys->cond, &p_sys->lock);
    p_sys->b_state_changed = false;
    vlc_mutex_unlock(&p_sys->lock);
    vlc_join(p_sys->thread, NULL);

    if (p_sys->p_fifo) {
        block_FifoRelease(p_sys->p_fifo);
        p_sys->p_fifo = NULL;
    }
    if (p_sys->prefetch) {
        free(p_sys->prefetch);
        p_sys->prefetch = NULL;
    }
    ccn_charbuf_destroy(&p_sys->p_prefetch_template);
    ccn_charbuf_destroy(&p_sys->p_data_template);
    ccn_charbuf_destroy(&p_sys->p_name);
    vlc_mutex_destroy(&p_sys->lock);
    vlc_cond_destroy(&p_sys->cond);
    free(p_sys);
}

/*****************************************************************************
 * CCNBlock:
 *****************************************************************************/
static block_t *
CCNBlock(access_t *p_access)
{
    access_sys_t *p_sys = p_access->p_sys;
    block_t *p_block = NULL;

    if (p_access->info.b_eof) {
        msg_Dbg(p_access, "CCN.Block eof");
        return NULL;
    }

    if (p_sys->p_fifo == NULL)
        return NULL;
    p_block = block_FifoGet(p_sys->p_fifo);
    if (p_block == NULL || p_access->b_die)
        return NULL;
    if (p_block->i_buffer == 0) {
        p_access->info.i_size = p_access->info.i_pos;
        p_access->info.b_eof = true;
    } else {
        p_access->info.i_pos += p_block->i_buffer;
    }
    return (p_block);
}

/*****************************************************************************
 * CCNSeek:
 *****************************************************************************/
/* XXX - VLC behavior when playing an MP4 file is to seek back and forth for
 * the audio and video, which may be separated by many megabytes, so it is
 * a much better (and possibly required) that the code not discard all
 * previously buffered data when seeking, since the app is likely to seek back
 * close to where it was very quickly.
 */
#if (VLCPLUGINVER < 10100)
static int CCNSeek(access_t *p_access, int64_t i_pos)
#else
static int CCNSeek(access_t *p_access, uint64_t i_pos)
#endif
{
    access_sys_t *p_sys = p_access->p_sys;
    struct ccn_charbuf *p_name;
    int i, i_prefetch;

    /* flush the FIFO in case the incoming content handler is blocked,
     * which must be done without the lock held as the handler holds it
     * while waiting for the fifo to have space available
     */
    block_FifoEmpty(p_sys->p_fifo);
    vlc_mutex_lock(&p_sys->lock);
    if (p_access->b_die) {
        vlc_mutex_unlock(&p_sys->lock);
        return (VLC_EGENERIC);
    }
#if (VLCPLUGINVER < 10100)
    if (i_pos < 0) {
        msg_Warn(p_access, "CCN.Seek attempting to seek before the beginning %"PRId64".", i_pos);
        i_pos = 0;
    }
#endif
    /* flush the FIFO for real, and restart from the requested point */
    block_FifoEmpty(p_sys->p_fifo);
    p_sys->incoming = calloc(1, sizeof(struct ccn_closure));
    if (p_sys->incoming == NULL) {
        vlc_mutex_unlock(&p_sys->lock);
        return (VLC_EGENERIC);
    }
    msg_Dbg(p_access, "CCN.Seek to %"PRId64", closure %p", i_pos, p_sys->incoming);
    p_sys->incoming->data = p_access; /* so CCN callbacks can find p_sys */
    p_sys->incoming->p = &incoming_content; /* the CCN callback */
    p_sys->i_pos = i_pos;
    /* prefetch, but only do full amount if going forward */
    if (i_pos > p_access->info.i_pos)
      i_prefetch = p_sys->i_prefetch;
    else
      i_prefetch = p_sys->i_prefetch / 2;
    for (i = 0; i <= i_prefetch; i++) {
        p_name = sequenced_name(p_sys->p_name, i + p_sys->i_pos / p_sys->i_chunksize);
        ccn_express_interest(p_sys->ccn, p_name, p_sys->prefetch,
                                     p_sys->p_prefetch_template);
        ccn_charbuf_destroy(&p_name);        
    }
    /* and fetch */
    p_name = sequenced_name(p_sys->p_name, p_sys->i_pos / p_sys->i_chunksize);
    ccn_express_interest(p_sys->ccn, p_name, p_sys->incoming, p_sys->p_data_template);
    ccn_charbuf_destroy(&p_name);
    
    p_access->info.i_pos = i_pos;
    p_access->info.b_eof = false;
    vlc_mutex_unlock(&p_sys->lock);
    return (VLC_SUCCESS);
}
/*****************************************************************************
 * Control:
 *****************************************************************************/
static int
CCNControl(access_t *p_access, int i_query, va_list args)
{
    bool   *pb_bool;
    int64_t      *pi_64;

    switch(i_query)
    {
        case ACCESS_CAN_SEEK:
        case ACCESS_CAN_FASTSEEK:
            pb_bool = (bool*)va_arg(args, bool *);
            *pb_bool = var_CreateGetBool(p_access, "ccn-streams-seekable");
            break;
        
        case ACCESS_CAN_CONTROL_PACE:
        case ACCESS_CAN_PAUSE:
            pb_bool = (bool*)va_arg(args, bool *);
            *pb_bool = true;
            break;
        
        case ACCESS_GET_PTS_DELAY:
            pi_64 = (int64_t*)va_arg(args, int64_t *);
            *pi_64 = INT64_C(1000) *
                (int64_t) var_InheritInteger(p_access, "network-caching");
            break;
        
        case ACCESS_SET_PAUSE_STATE:
            pb_bool = (bool*)va_arg(args, bool *);
            break;
        
        case ACCESS_GET_TITLE_INFO:
        case ACCESS_GET_META:
        case ACCESS_SET_TITLE:
        case ACCESS_SET_SEEKPOINT:
        case ACCESS_SET_PRIVATE_ID_STATE:
    	case ACCESS_SET_PRIVATE_ID_CA:
        case ACCESS_GET_PRIVATE_ID_STATE:
        case ACCESS_GET_CONTENT_TYPE:
            return VLC_EGENERIC;
        
        default:
            msg_Warn(p_access, "CCN.Control unimplemented query in control - %d", i_query);
            return VLC_EGENERIC;
        
    }
    return VLC_SUCCESS;
}

#define CHECK_NOMEM(x, msg) if ((x) == NULL) {\
    i_err = VLC_ENOMEM; msg_Err(p_access, msg); goto exit; }

static void *
ccn_event_thread(void *p_this)
{
    access_t *p_access = (access_t *)p_this;
    access_sys_t *p_sys = p_access->p_sys;
    struct ccn_charbuf *p_name = NULL;
    struct ccn_charbuf *p_co = NULL;
    struct ccn_header *p_header = NULL;
    int i;
    int i_err = VLC_EGENERIC;
    struct pollfd fds[1];
    int i_ret = 0;
    
    p_sys->incoming = calloc(1, sizeof(struct ccn_closure));
    CHECK_NOMEM(p_sys->incoming, "CCN.Input failed: no memory for ccn_closure");
    p_sys->prefetch = calloc(1, sizeof(struct ccn_closure));
    CHECK_NOMEM(p_sys->prefetch, "CCN.Input failed: no memory for prefetch ccn_closure");
    p_sys->p_prefetch_template = make_prefetch_template();
    CHECK_NOMEM(p_sys->p_prefetch_template, "CCN.Input failed: no memory for prefetch template");
    p_sys->p_data_template = make_data_template();
    CHECK_NOMEM(p_sys->p_data_template, "CCN.Input failed: no memory for data template");
    
#if (VLCPLUGINVER >= 10200)
    msg_Dbg(p_access, "CCN.Input %s, closure %p",
            p_access->psz_location, p_sys->incoming);
#else
    msg_Dbg(p_access, "CCN.Input %s, closure %p",
            p_access->psz_path, p_sys->incoming);
#endif
    p_sys->incoming->data = p_access; /* so CCN callbacks can find p_sys */
    p_sys->incoming->p = &incoming_content; /* the CCN callback */
    p_sys->prefetch->data = p_access; /* so CCN callbacks can find p_sys */
    p_sys->prefetch->p = &discard_content; /* the CCN callback */
    
    p_sys->ccn = ccn_create();
    if (p_sys->ccn == NULL || ccn_connect(p_sys->ccn, NULL) == -1) {
        msg_Err(p_access, "CCN.Input failed: unable to allocate handle and connect to ccnd");
        goto exit;
    }
    
    p_name = ccn_charbuf_create();
    CHECK_NOMEM(p_name, "CCN.Input failed: no memory for name charbuf");

#if (VLCPLUGINVER >= 10200)
    i_ret = ccn_name_from_uri(p_name, p_access->psz_location);
#else
    i_ret = ccn_name_from_uri(p_name, p_access->psz_path);
#endif
    if (i_ret < 0) {
        msg_Err(p_access, "CCN.Input failed: unable to parse CCN URI");
        goto exit;
    }
    p_sys->p_name = ccn_charbuf_create_n(p_name->length + 16);
    CHECK_NOMEM(p_sys->p_name, "CCN.Input failed: no memory for global name charbuf");
    i_ret = ccn_resolve_version(p_sys->ccn, p_name,
                                CCN_V_HIGHEST, CCN_VERSION_TIMEOUT);
    ccn_charbuf_append_charbuf(p_sys->p_name, p_name);
    if (i_ret == 0) {
        /* name is versioned, so get the header to obtain the length */
        p_header = ccn_get_header(p_sys->ccn, p_name, CCN_HEADER_TIMEOUT);
        if (p_header != NULL) {
            p_access->info.i_size = p_header->length;
            ccn_header_destroy(&p_header);
        }
        msg_Dbg(p_access, "CCN.Input set length %"PRId64, p_access->info.i_size);
    }
    ccn_charbuf_destroy(&p_name);

    for (i=0; i <= p_sys->i_prefetch; i++) {
        p_name = sequenced_name(p_sys->p_name, i);
        i_ret = ccn_express_interest(p_sys->ccn, p_name, p_sys->prefetch,
                                     p_sys->p_prefetch_template);
        ccn_charbuf_destroy(&p_name);        
    }

    p_co = ccn_charbuf_create();
    CHECK_NOMEM(p_co, "CCN.Input failed: no memory for initial content");
    p_name = sequenced_name(p_sys->p_name, 0);
    i_ret = ccn_get(p_sys->ccn, p_name, p_sys->p_data_template, 5000, p_co, NULL, NULL, 0);
    ccn_charbuf_destroy(&p_co);
    if (i_ret < 0) {
        msg_Err(p_access, "CCN.Input failed: unable to locate specified input");
        goto exit;
    }
    i_ret = ccn_express_interest(p_sys->ccn, p_name, p_sys->incoming,
                                 p_sys->p_data_template);
    ccn_charbuf_destroy(&p_name);
    if (i_ret < 0) {
        msg_Err(p_access, "CCN.Input failed: unable to express interest");
        goto exit;
    }

    vlc_mutex_lock(&p_sys->lock);
    p_sys->i_state = VLC_SUCCESS;
    p_sys->b_state_changed = true;
    vlc_cond_signal(&p_sys->cond);
    vlc_mutex_unlock(&p_sys->lock);
    
    fds[0].fd = ccn_get_connection_fd(p_sys->ccn);
    fds[0].events = POLLIN;
    i_ret = 0;
    msg_Info(p_access, "CCN.Input: entering input event loop");
    while (i_ret >= 0 && !p_access->b_die) {
        i_ret = poll(fds, 1, 100);
        if (i_ret < 0 && errno != EINTR) {
            /* got a real error */
            break;
        }
        vlc_mutex_lock(&p_sys->lock);
        i_ret = ccn_run(p_sys->ccn, 0);
        vlc_mutex_unlock(&p_sys->lock);
    }
    msg_Info(p_access, "CCN.Input: exited input event loop");
    /* if we caused the exit, let others know */
    p_access->b_die = true;
    block_FifoWake(p_sys->p_fifo);
    
exit:
    ccn_destroy(&(p_sys->ccn));
    ccn_charbuf_destroy(&p_sys->p_name);
    if (p_sys->incoming) {
        free(p_sys->incoming);
        p_sys->incoming = NULL;
    }
    if (p_sys->prefetch) {
        free(p_sys->prefetch);
        p_sys->prefetch = NULL;
    }
    ccn_charbuf_destroy(&p_sys->p_prefetch_template);
    ccn_charbuf_destroy(&p_sys->p_data_template);
    p_sys->i_state = i_err;
    p_sys->b_state_changed = true;
    vlc_cond_signal(&p_sys->cond);
    return(NULL);
}
#undef CHECK_NOMEM
                    
#if (VLCPLUGINVER < 10100)
/* block_FifoPace was defined early on, but not exported until 1.1.0, so we
 * duplicate the code here if it is needed.
 */
static void
s_block_FifoPace (block_fifo_t *fifo, size_t max_depth, size_t max_size)
{
    vlc_testcancel ();

    vlc_mutex_lock (&fifo->lock);
    while ((fifo->i_depth > max_depth) || (fifo->i_size > max_size))
    {
        mutex_cleanup_push (&fifo->lock);
        vlc_cond_wait (&fifo->wait_room, &fifo->lock);
        vlc_cleanup_pop ();
    }
    vlc_mutex_unlock (&fifo->lock);
}
#endif

static enum ccn_upcall_res
incoming_content(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    access_t *p_access = (access_t *)(selfp->data);
    access_sys_t *p_sys = p_access->p_sys;
    int64_t start_offset = 0;
    block_t *p_block = NULL;
    bool b_last = false;
    struct ccn_charbuf *name = NULL;
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    const unsigned char *data = NULL;
    size_t data_size = 0;
    const unsigned char *name_comp = NULL;
    size_t name_comp_size = 0;
    int res, i;
    uint64_t i_nextpos;
    uint64_t segment;
    int result = CCN_UPCALL_RESULT_OK;

    switch (kind) {
        case CCN_UPCALL_FINAL:
            msg_Dbg(p_access, "CCN.Upcall final, closure %p", selfp);
            if (selfp == p_sys->incoming)
                p_sys->incoming = NULL;
            free(selfp);
            goto exit;
        case CCN_UPCALL_INTEREST_TIMED_OUT:
            if (selfp != p_sys->incoming) {
                msg_Dbg(p_access, "CCN.Upcall interest timed out on dead closure %p", selfp);
                goto exit;
            }
            msg_Dbg(p_access, "CCN.Upcall reexpress -- timed out, closure %p", selfp);
            if (p_sys->timeouts > 100) {
                msg_Dbg(p_access, "CCN.Upcall reexpress -- too many reexpressions");
                p_access->b_die = true;
                if (p_sys->p_fifo)
                    block_FifoWake(p_sys->p_fifo);
                goto exit;
            }
            p_sys->timeouts++;
            result = CCN_UPCALL_RESULT_REEXPRESS;
            goto exit;
        case CCN_UPCALL_CONTENT_UNVERIFIED:
            if (selfp != p_sys->incoming) {
                msg_Dbg(p_access, "CCN.Upcall unverified content on dead closure %p", selfp);
                goto exit;
            }
            result = CCN_UPCALL_RESULT_VERIFY;
            goto exit;
        
        case CCN_UPCALL_CONTENT:
            if (selfp != p_sys->incoming || p_access->b_die) {
                msg_Dbg(p_access, "CCN.Upcall content on dead closure %p", selfp);
                goto exit;
            }
            break;
        default:
            msg_Warn(p_access, "CCN.Upcall result error, closure %p", selfp);
            result = CCN_UPCALL_RESULT_ERR;
            goto exit;
    }
    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    res = ccn_content_get_value(ccnb, ccnb_size, info->pco, &data, &data_size);
    if (res < 0) abort();

    p_sys->timeouts = 0;
    /* if we did not previously know the chunk size, record it */
    if (p_sys->i_chunksize == -1)
        p_sys->i_chunksize = data_size;
    /* was this the last block? */
    if (ccn_is_final_block(info) || data_size < p_sys->i_chunksize)
        b_last = true;
    /* check that this is actually the block we expected */
    res = ccn_name_comp_get(ccnb, info->content_comps, info->pco->name_ncomps - 1,
                            &name_comp, &name_comp_size);
    for (i = 1, segment = name_comp[0]; i < name_comp_size; i++)
        segment = (segment << 8) | name_comp[i];
    if (p_sys->i_pos < segment * p_sys->i_chunksize ||
        p_sys->i_pos >= (segment + 1) * p_sys->i_chunksize)
        msg_Err(p_access, "CCN.Upcall wrong data block for position %"PRId64" :: block start %"PRIu64,
                p_sys->i_pos, segment * p_sys->i_chunksize);

    /* something to process */
    if (data_size > 0) {
        start_offset = p_sys->i_pos % p_sys->i_chunksize;
        /* Ask for next fragment as soon as possible */
        if (!b_last) {
            i_nextpos = p_sys->i_pos + (data_size - start_offset);
            name = sequenced_name(p_sys->p_name, i_nextpos / p_sys->i_chunksize);
            res = ccn_express_interest(info->h, name, selfp, p_sys->p_data_template);
            ccn_charbuf_destroy(&name);
            if (res < 0) abort();
            /* and prefetch a fragment if it's not past the end */
            if (p_sys->i_prefetch * p_sys->i_chunksize <= p_access->info.i_size - i_nextpos) {
                name = sequenced_name(p_sys->p_name, p_sys->i_prefetch + i_nextpos / p_sys->i_chunksize);
                res = ccn_express_interest(info->h, name, p_sys->prefetch, p_sys->p_prefetch_template);
                ccn_charbuf_destroy(&name);
            }
        }
#if (VLCPLUGINVER < 10100)
        /* block_FifoPace was not exported until 1.1.0, use a copy of it... */
        s_block_FifoPace(p_sys->p_fifo, p_sys->i_fifo_max, SIZE_MAX);
#else
        block_FifoPace(p_sys->p_fifo, p_sys->i_fifo_max, SIZE_MAX);
#endif
        if (start_offset > data_size) {
            msg_Err(p_access, "CCN.Upcall start_offset %"PRId64" > data_size %zu", start_offset, data_size);
        } else {
            p_block = block_New(p_access, data_size - start_offset);
            memcpy(p_block->p_buffer, data + start_offset, data_size - start_offset);
            block_FifoPut(p_sys->p_fifo, p_block);
        }
    }
    /* Advance position */
    p_sys->i_pos += (data_size - start_offset);
    /* if we're done, indicate so with a 0-byte block */
    if (b_last)
        block_FifoPut(p_sys->p_fifo, block_New(p_access, 0));

exit:
    return(result);
}

static enum ccn_upcall_res
discard_content(struct ccn_closure *selfp,
                enum ccn_upcall_kind kind,
                struct ccn_upcall_info *info)
{
    return(CCN_UPCALL_RESULT_OK);
}

static struct ccn_charbuf *
sequenced_name(struct ccn_charbuf *basename, uintmax_t seq)
{
    struct ccn_charbuf *name = NULL;
    if (basename != NULL) {
        name = ccn_charbuf_create_n(8 + basename->length);
        ccn_charbuf_append_charbuf(name, basename);
        ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, seq);
    }
    return(name);
}

static int
append_tagged_binary_number(struct ccn_charbuf *cb, enum ccn_dtag dtag, uintmax_t val) {
    unsigned char buf[sizeof(val)];
    int pos;
    int res = 0;
    for (pos = sizeof(buf); val != 0 && pos > 0; val >>= 8)
        buf[--pos] = val & 0xff;
    res |= ccnb_append_tagged_blob(cb, dtag, buf+pos, sizeof(buf)-pos);
    return(res);
}

static struct ccn_charbuf *
make_prefetch_template()
{
    struct ccn_charbuf *templ = ccn_charbuf_create_n(16);
    ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
    ccn_charbuf_append_tt(templ, CCN_DTAG_Name, CCN_DTAG);
    ccn_charbuf_append_closer(templ); /* </Name> */
    ccn_charbuf_append_tt(templ, CCN_DTAG_MaxSuffixComponents, CCN_DTAG);
    ccnb_append_number(templ, 1);
    ccn_charbuf_append_closer(templ); /* </MaxSuffixComponents> */
    append_tagged_binary_number(templ, CCN_DTAG_InterestLifetime, CCN_PREFETCH_LIFETIME);
    ccn_charbuf_append_closer(templ); /* </Interest> */
    return(templ);
}

static struct ccn_charbuf *
make_data_template()
{
    struct ccn_charbuf *templ = ccn_charbuf_create_n(16);
    ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
    ccn_charbuf_append_tt(templ, CCN_DTAG_Name, CCN_DTAG);
    ccn_charbuf_append_closer(templ); /* </Name> */
    ccn_charbuf_append_tt(templ, CCN_DTAG_MaxSuffixComponents, CCN_DTAG);
    ccnb_append_number(templ, 1);
    ccn_charbuf_append_closer(templ); /* </MaxSuffixComponents> */
    append_tagged_binary_number(templ, CCN_DTAG_InterestLifetime, CCN_DATA_LIFETIME);
    ccn_charbuf_append_closer(templ); /* </Interest> */
    return(templ);
}

