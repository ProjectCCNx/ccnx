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
#define CCN_VERSION_TIMEOUT 5000
#define CCN_HEADER_TIMEOUT 1000
#define CCN_DEFAULT_PREFETCH 12

#define CCN_PREFETCH_LIFETIME 1023
#define CCN_DATA_LIFETIME 1024
#define PREFETCH_TEXT N_("Prefetch offset")
#define PREFETCH_LONGTEXT N_(                                          \
"Number of content objects prefetched, "                       \
"and offset from content object received for next interest.")
#define SEEKABLE_TEXT N_("CCN streams can seek")
#define SEEKABLE_LONGTEXT N_(               \
"Enable or disable seeking within a CCN stream.")
#define VERSION_TIMEOUT_TEXT N_("Version timeout (ms)")
#define VERSION_TIMEOUT_LONGTEXT N_(                                          \
"Maximum number of milliseconds to wait for resolving latest version of media.")
#define HEADER_TIMEOUT_TEXT N_("Header timeout (ms)")
#define HEADER_TIMEOUT_LONGTEXT N_(                                          \
"Maximum number of milliseconds to wait for resolving latest version of header.")
#define TCP_CONNECT_TEXT N_("Connect to ccnd with TCP")
#define TCP_CONNECT_LONGTEXT N_(                                        \
"Connect to ccnd with TCP instead of Unix domain socket")


static int  CCNOpen(vlc_object_t *);
static void CCNClose(vlc_object_t *);
static block_t *CCNBlock(access_t *);
#if (VLCPLUGINVER >= 10100)
static int CCNSeek(access_t *, uint64_t);
#else
static int CCNSeek(access_t *, int64_t);
#endif
static int CCNControl(access_t *, int, va_list);

vlc_module_begin();
set_shortname(N_("CCNx"));
set_description(N_("Access streams via CCNx"));
set_category(CAT_INPUT);
set_subcategory(SUBCAT_INPUT_ACCESS);
#if (VLCPLUGINVER < 10200)
add_integer("ccn-prefetch", CCN_DEFAULT_PREFETCH, NULL,
            PREFETCH_TEXT, PREFETCH_LONGTEXT, true);
add_bool("ccn-streams-seekable", true, NULL,
         SEEKABLE_TEXT, SEEKABLE_LONGTEXT, true )
#else
add_integer("ccn-prefetch", CCN_DEFAULT_PREFETCH,
            PREFETCH_TEXT, PREFETCH_LONGTEXT, true);
add_integer("ccn-version-timeout", CCN_VERSION_TIMEOUT,
            VERSION_TIMEOUT_TEXT, VERSION_TIMEOUT_LONGTEXT, true);
add_integer("ccn-header-timeout", CCN_HEADER_TIMEOUT,
            HEADER_TIMEOUT_TEXT, HEADER_TIMEOUT_LONGTEXT, true);
add_bool("ccn-streams-seekable", true,
         SEEKABLE_TEXT, SEEKABLE_LONGTEXT, true )
add_bool("ccn-tcp-connect", true,
         TCP_CONNECT_TEXT, TCP_CONNECT_LONGTEXT, true )
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
    int i_chunksize;        /**< size of CCN ContentObject data blocks */
    int i_prefetch;         /**< offset for prefetching */
    int i_version_timeout;  /**< timeout in seconds for getting latest media version */
    int i_header_timeout;   /**< timeout in seconds for getting latest header version */
    int i_missed_co;        /**< number of content objects we missed in CCNBlock */
    struct ccn *ccn;        /**< CCN handle */
    struct ccn *ccn_pf;     /**< CCN handle for prefetch thread */
    struct ccn_closure *prefetch;   /**< closure for handling prefetch content */
    struct ccn_charbuf *p_name;     /**< base name for stream including version */
    struct ccn_charbuf *p_prefetch_template; /**< interest expression template */
    struct ccn_charbuf *p_data_template; /**< interest expression template */
    struct ccn_charbuf *p_content_object; /**< content object storage */
    struct ccn_indexbuf *p_compsbuf; /**< name components indexbuf scratch storage */
    vlc_thread_t thread;    /**< thread that is running prefetch ccn_run loop */
    vlc_mutex_t lock;       /**< mutex protecting ccn_pf handle */
};

static enum ccn_upcall_res discard_content(struct ccn_closure *selfp,
                                           enum ccn_upcall_kind kind,
                                           struct ccn_upcall_info *info);
static void *ccn_prefetch_thread(void *p_this);
static void sequenced_name(struct ccn_charbuf *name,
                           struct ccn_charbuf *basename, uintmax_t seq);
static struct ccn_charbuf *make_prefetch_template();
static struct ccn_charbuf *make_data_template();

/*****************************************************************************
 * p_sys_clean: 
 *****************************************************************************/

static void p_sys_clean(struct access_sys_t *p_sys) {
    ccn_destroy(&(p_sys->ccn));
    ccn_destroy(&(p_sys->ccn_pf));
    if (p_sys->prefetch) {
        free(p_sys->prefetch);
        p_sys->prefetch = NULL;
    }
    ccn_charbuf_destroy(&p_sys->p_name);
    ccn_charbuf_destroy(&p_sys->p_prefetch_template);
    ccn_charbuf_destroy(&p_sys->p_data_template);
    ccn_charbuf_destroy(&p_sys->p_content_object);
    ccn_indexbuf_destroy(&p_sys->p_compsbuf);
    vlc_mutex_destroy(&p_sys->lock);
}

/*****************************************************************************
 * CCNOpen: 
 *****************************************************************************/
#define CHECK_NOMEM(x, msg) if ((x) == NULL) {\
i_err = VLC_ENOMEM; msg_Err(p_access, msg); goto exit; }

static int
CCNOpen(vlc_object_t *p_this)
{
    access_t     *p_access = (access_t *)p_this;
    access_sys_t *p_sys = NULL;
    int i_ret = 0;
    int i_err = VLC_EGENERIC;
    int i;
    struct ccn_charbuf *p_name = NULL;
    struct ccn_header *p_header = NULL;
    bool b_tcp;
    
    /* Init p_access */
    access_InitFields(p_access);
    msg_Info(p_access, "CCNOpen called");
    ACCESS_SET_CALLBACKS(NULL, CCNBlock, CCNControl, CCNSeek);
    p_sys = calloc(1, sizeof(access_sys_t));
    if (p_sys == NULL) {
        msg_Err(p_access, "CCNOpen failed: no memory for p_sys");
        return (VLC_ENOMEM);
    }
    p_access->p_sys = p_sys;
    p_sys->i_chunksize = -1;
    p_sys->i_missed_co = 0;
    p_sys->i_prefetch = var_CreateGetInteger(p_access, "ccn-prefetch");
    p_sys->i_version_timeout = var_CreateGetInteger(p_access, "ccn-version-timeout");
    p_sys->i_header_timeout = var_CreateGetInteger(p_access, "ccn-header-timeout");
    b_tcp = var_CreateGetBool(p_access, "ccn-tcp-connect");
    p_access->info.i_size = LLONG_MAX;	/* don't know yet, but bigger is better */
    
    p_sys->prefetch = calloc(1, sizeof(struct ccn_closure));
    CHECK_NOMEM(p_sys->prefetch, "CCNOpen failed: no memory for prefetch ccn_closure");
    p_sys->p_prefetch_template = make_prefetch_template();
    CHECK_NOMEM(p_sys->p_prefetch_template, "CCNOpen failed: no memory for prefetch template");
    p_sys->p_data_template = make_data_template();
    CHECK_NOMEM(p_sys->p_data_template, "CCNOpen failed: no memory for data template");
    
#if (VLCPLUGINVER >= 10200)
    msg_Dbg(p_access, "CCNOpen %s", p_access->psz_location);
#else
    msg_Dbg(p_access, "CCNOpen %s", p_access->psz_path);
#endif
    vlc_mutex_init(&p_sys->lock);
    p_sys->prefetch->data = p_access; /* so CCN callbacks can find p_sys */
    p_sys->prefetch->p = &discard_content; /* the CCN callback */
    
    p_sys->ccn = ccn_create();
    if (p_sys->ccn == NULL || ccn_connect(p_sys->ccn, b_tcp ? "tcp" : NULL) == -1) {
        msg_Err(p_access, "CCNOpen failed: unable to allocate handle and connect to ccnd");
        goto exit;
    }
    p_sys->ccn_pf = ccn_create();
    if (p_sys->ccn_pf == NULL || ccn_connect(p_sys->ccn_pf, b_tcp ? "tcp" : NULL) == -1) {
        msg_Err(p_access, "CCNOpen failed: unable to allocate prefetch handle and connect to ccnd");
        goto exit;
    }
    msg_Info(p_access, "CCNOpen connected to ccnd%s", b_tcp ? " with TCP" : "");
    
    p_name = ccn_charbuf_create();
    CHECK_NOMEM(p_name, "CCNOpen failed: no memory for name charbuf");
    p_sys->p_compsbuf = ccn_indexbuf_create();
    CHECK_NOMEM(p_sys->p_compsbuf, "CCNOpen failed: no memory for name components indexbuf");
    
#if (VLCPLUGINVER >= 10200)
    i_ret = ccn_name_from_uri(p_name, p_access->psz_location);
#else
    i_ret = ccn_name_from_uri(p_name, p_access->psz_path);
#endif
    if (i_ret < 0) {
        msg_Err(p_access, "CCNOpen failed: unable to parse CCN URI");
        goto exit;
    }
    p_sys->p_name = ccn_charbuf_create_n(p_name->length + 16);
    CHECK_NOMEM(p_sys->p_name, "CCNOpen failed: no memory for global name charbuf");
    i_ret = ccn_resolve_version(p_sys->ccn, p_name,
                                CCN_V_HIGHEST, p_sys->i_version_timeout);
    if (i_ret < 0) {
        msg_Err(p_access, "CCNOpen failed: unable to determine version");
        goto exit;
    }
    ccn_charbuf_append_charbuf(p_sys->p_name, p_name);
    /* name is versioned, so get the header to obtain the length */
    p_header = ccn_get_header(p_sys->ccn, p_name, p_sys->i_header_timeout);
    if (p_header != NULL) {
        p_access->info.i_size = p_header->length;
        p_sys->i_chunksize = p_header->block_size;
        ccn_header_destroy(&p_header);
    }
    msg_Dbg(p_access, "CCNOpen set length %"PRId64, p_access->info.i_size);
    ccn_charbuf_destroy(&p_name);
    
    p_sys->p_content_object = ccn_charbuf_create();
    CHECK_NOMEM(p_sys->p_content_object, "CCNOpen failed: no memory for initial content");
    /* make sure we can get the first block, or fail early */
    p_name = ccn_charbuf_create();
    sequenced_name(p_name, p_sys->p_name, 0);
    i_ret = ccn_get(p_sys->ccn, p_name, p_sys->p_data_template, 5000, p_sys->p_content_object, NULL, NULL, 0);
    if (i_ret < 0) {
        ccn_charbuf_destroy(&p_name);
        msg_Err(p_access, "CCNOpen failed: unable to locate specified input");
        goto exit;
    }
    if (0 != vlc_clone(&(p_sys->thread), ccn_prefetch_thread, p_access, VLC_THREAD_PRIORITY_INPUT)) {
        msg_Err(p_access, "CCNOpen failed: unable to vlc_clone for CCN prefetch thread");
        goto exit;
    }
    /* start prefetches for some more, unless it's a short file */
    vlc_mutex_lock(&p_sys->lock);
    for (i=1; i <= p_sys->i_prefetch; i++) {
        if (i * p_sys->i_chunksize >= p_access->info.i_size)
            break;
        sequenced_name(p_name, p_sys->p_name, i);
        i_ret = ccn_express_interest(p_sys->ccn_pf, p_name, p_sys->prefetch,
                                     p_sys->p_prefetch_template);
    }
    vlc_mutex_unlock(&p_sys->lock);
    ccn_charbuf_destroy(&p_name);
    return (VLC_SUCCESS);
    
exit:
    ccn_charbuf_destroy(&p_name);
    p_sys_clean(p_sys);
    free(p_sys);
    p_access->p_sys = NULL;
    return (i_err);
}

/*****************************************************************************
 * CCNClose: free unused data structures
 *****************************************************************************/
static void
CCNClose(vlc_object_t *p_this)
{
    access_t     *p_access = (access_t *)p_this;
    access_sys_t *p_sys = p_access->p_sys;
    
    msg_Info(p_access, "CCNClose called, missed %d blocks", p_sys->i_missed_co);
    ccn_run(p_sys->ccn, 100);
    ccn_disconnect(p_sys->ccn);
    vlc_mutex_lock(&p_sys->lock);
    ccn_disconnect(p_sys->ccn_pf);
    vlc_mutex_unlock(&p_sys->lock);
    msg_Info(p_access, "CCNClose about to join prefetch thread");
    vlc_join(p_sys->thread, NULL);
    msg_Info(p_access, "CCNClose joined prefetch thread");
    p_sys_clean(p_sys);
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
    struct ccn_charbuf *p_name = NULL;
    struct ccn_parsed_ContentObject pcobuf = {0};
    const unsigned char *data = NULL;
    size_t data_size = 0;
    uint64_t start_offset = 0;
    uint64_t i_nextpos;
    int i_ret;
    bool b_last = false;
    
    if (p_access->info.b_eof) {
        msg_Dbg(p_access, "CCNBlock eof");
        return NULL;
    }
    // start
    p_name = ccn_charbuf_create();
    sequenced_name(p_name, p_sys->p_name, p_access->info.i_pos / p_sys->i_chunksize);
    i_ret = ccn_get(p_sys->ccn, p_name, p_sys->p_data_template, 250, p_sys->p_content_object, &pcobuf, p_sys->p_compsbuf, 0);
    if (i_ret < 0) {
        ccn_charbuf_destroy(&p_name);
        msg_Dbg(p_access, "CCNBlock unable to retrieve requested content: retrying");
        p_sys->i_missed_co++;
        return NULL;
    }
    i_ret = ccn_content_get_value(p_sys->p_content_object->buf, p_sys->p_content_object->length, &pcobuf, &data, &data_size);
    if (ccn_is_final_pco(p_sys->p_content_object->buf, &pcobuf, p_sys->p_compsbuf) == 1 || data_size < p_sys->i_chunksize)
        b_last = true;
    if (data_size > 0) {
        start_offset = p_access->info.i_pos % p_sys->i_chunksize;
        /* Ask for next fragment as soon as possible */
        if (!b_last) {
            i_nextpos = p_access->info.i_pos + (data_size - start_offset);
            /* prefetch a fragment if it's not past the end */
            if (p_sys->i_prefetch * p_sys->i_chunksize <= p_access->info.i_size - i_nextpos) {
                sequenced_name(p_name, p_sys->p_name, p_sys->i_prefetch + i_nextpos / p_sys->i_chunksize);
                vlc_mutex_lock(&p_sys->lock);
                i_ret = ccn_express_interest(p_sys->ccn_pf, p_name, p_sys->prefetch, p_sys->p_prefetch_template);
                vlc_mutex_unlock(&p_sys->lock);
            }
        }
        if (start_offset > data_size) {
            msg_Err(p_access, "CCNBlock start_offset %"PRId64" > data_size %zu", start_offset, data_size);
        } else {
            p_block = block_New(p_access, data_size - start_offset);
            memcpy(p_block->p_buffer, data + start_offset, data_size - start_offset);
        }
        p_access->info.i_pos += (data_size - start_offset);
    }
    ccn_charbuf_destroy(&p_name);
    
    // end
    if (b_last) {
        p_access->info.i_size = p_access->info.i_pos;
        p_access->info.b_eof = true;
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
    int i, i_prefetch, i_base;
    
#if (VLCPLUGINVER < 10100)
    if (i_pos < 0) {
        msg_Warn(p_access, "CCNSeek attempting to seek before the beginning %"PRId64".", i_pos);
        i_pos = 0;
    }
#endif
    /* prefetch, but only do full amount if going forward */
    if (i_pos > p_access->info.i_pos)
        i_prefetch = p_sys->i_prefetch;
    else
        i_prefetch = p_sys->i_prefetch / 2;
    i_base = i_pos / p_sys->i_chunksize;
    p_name = ccn_charbuf_create();
    for (i = 0; i <= i_prefetch; i++) {
        sequenced_name(p_name, p_sys->p_name, i_base + i);
        vlc_mutex_lock(&p_sys->lock);
        ccn_express_interest(p_sys->ccn_pf, p_name, p_sys->prefetch,
                             p_sys->p_prefetch_template);
        vlc_mutex_unlock(&p_sys->lock);
    }
    ccn_charbuf_destroy(&p_name);        
    
    p_access->info.i_pos = i_pos;
    p_access->info.b_eof = false;
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
            msg_Warn(p_access, "CCNControl unimplemented query in control - %d", i_query);
            return VLC_EGENERIC;
            
    }
    return VLC_SUCCESS;
}

static void *
ccn_prefetch_thread(void *p_this)
{
    access_t *p_access = (access_t *)p_this;
    access_sys_t *p_sys = p_access->p_sys;
    struct pollfd fds[1];
    int i_ret = 0;
    
    msg_Info(p_access, "ccn_prefetch_thread starting");
    fds[0].fd = ccn_get_connection_fd(p_sys->ccn_pf);
    fds[0].events = POLLIN;
    do {
        i_ret = poll(fds, 1, 200);
        if (i_ret < 0 && errno != EINTR)    /* a real error occurred */
            break;
        if (i_ret > 0) {
            vlc_mutex_lock(&p_sys->lock);
            i_ret = ccn_run(p_sys->ccn_pf, 0);
            vlc_mutex_unlock(&p_sys->lock);
        }
    } while (i_ret == 0 && ccn_get_connection_fd(p_sys->ccn_pf) >= 0);
    msg_Info(p_access, "ccn_prefetch_thread exiting");
    return NULL;
}

static enum ccn_upcall_res
discard_content(struct ccn_closure *selfp,
                enum ccn_upcall_kind kind,
                struct ccn_upcall_info *info)
{
    return(CCN_UPCALL_RESULT_OK);
}

static void
sequenced_name(struct ccn_charbuf *name, struct ccn_charbuf *basename, uintmax_t seq)
{
    ccn_charbuf_reset(name);
    if (basename != NULL) {
        ccn_charbuf_append_charbuf(name, basename);
        ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, seq);
    }
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
    ccnb_append_tagged_binary_number(templ, CCN_DTAG_InterestLifetime, CCN_PREFETCH_LIFETIME);
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
    ccnb_append_tagged_binary_number(templ, CCN_DTAG_InterestLifetime, CCN_DATA_LIFETIME);
    ccn_charbuf_append_closer(templ); /* </Interest> */
    return(templ);
}

