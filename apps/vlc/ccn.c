/**
 * @file glue/vlc/ccn.c
 * 
 * CCNx input module for vlc.
 *
 * Copyright (C) 2009, 2010, 2011 Palo Alto Research Center, Inc.
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

#include <vlc_common.h>
#include <vlc_plugin.h>
#include <vlc_access.h>
#include <vlc_url.h>

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
#define CCN_FIFO_MAX_BLOCKS 128
#define CCN_CHUNK_SIZE 4096
#define CCN_FIFO_BLOCK_SIZE (128 * CCN_CHUNK_SIZE)
#define CCN_VERSION_TIMEOUT 400
#define CCN_HEADER_TIMEOUT 400

#define CACHING_TEXT N_("Caching value in ms")
#define CACHING_LONGTEXT N_(                                            \
                            "Caching value for CCN streams. This "      \
                            "value should be set in milliseconds.")
#define MAX_FIFO_TEXT N_("FIFO max blocks")
#define MAX_FIFO_LONGTEXT N_(						\
	"Maximum number of blocks held in FIFO "			\
	"used by content fetcher.")

#define BLOCK_FIFO_TEXT N_("FIFO block size")
#define BLOCK_FIFO_LONGTEXT N_(						\
	"Size of blocks held in FIFO "			\
	"used by content fetcher.")

static int  CCNOpen(vlc_object_t *);
static void CCNClose(vlc_object_t *);
static block_t *CCNBlock(access_t *);
#if (VLCPLUGINVER >= 110)
static int CCNSeek(access_t *, uint64_t);
#else
static int CCNSeek(access_t *, int64_t);
#endif
static int CCNControl(access_t *, int, va_list);

static void *ccn_event_thread(vlc_object_t *p_this);

vlc_module_begin();
set_shortname(N_("CCNx"));
set_description(N_("CCNx input"));
set_category(CAT_INPUT);
set_subcategory(SUBCAT_INPUT_ACCESS);
add_integer("ccn-caching", 4 * DEFAULT_PTS_DELAY / 1000, NULL,
            CACHING_TEXT, CACHING_LONGTEXT, true);
add_integer("ccn-fifo-maxblocks", CCN_FIFO_MAX_BLOCKS, NULL,
            MAX_FIFO_TEXT, MAX_FIFO_LONGTEXT, true);
add_integer("ccn-fifo-blocksize", CCN_FIFO_BLOCK_SIZE, NULL,
            BLOCK_FIFO_TEXT, BLOCK_FIFO_LONGTEXT, true);
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
    vlc_url_t  url;
    block_fifo_t *p_fifo;
    unsigned char *buf;
    int i_bufsize;
    int i_bufoffset;
    int timeouts;
    int i_fifo_max;
    int64_t i_pos;
    struct ccn *ccn;
    struct ccn_closure *incoming;
    struct ccn_charbuf *p_name;
    struct ccn_charbuf *p_template;
};

enum ccn_upcall_res
incoming_content(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info);
static struct ccn_charbuf *
sequenced_name(struct ccn_charbuf *basename, uintmax_t seq);

struct ccn_charbuf *make_data_template();

/*****************************************************************************
 * CCNOpen: 
 *****************************************************************************/
static int CCNOpen(vlc_object_t *p_this)
{
    access_t     *p_access = (access_t *)p_this;
    access_sys_t *p_sys = NULL;
    struct ccn_charbuf *p_name = NULL;
    struct ccn_header *p_header = NULL;
    int i_ret = 0;
    int i_err = VLC_EGENERIC;

    /* Init p_access */
    access_InitFields(p_access);
    ACCESS_SET_CALLBACKS(NULL, CCNBlock, CCNControl, CCNSeek);
    p_access->p_sys = calloc(1, sizeof(access_sys_t));
    p_sys = p_access->p_sys;
    if (p_sys == NULL)
        return VLC_ENOMEM;
#if (VLCPLUGINVER == 99)
    p_access->info.b_prebuffered = true;
#endif
    p_access->info.i_size = LLONG_MAX;	/* we don't know, but bigger is better */
    /* Update default_pts */
    var_Create(p_access, "ccn-caching", VLC_VAR_INTEGER | VLC_VAR_DOINHERIT);
    p_sys->i_fifo_max = var_CreateGetInteger(p_access, "ccn-fifo-maxblocks");
    p_sys->i_bufsize = var_CreateGetInteger(p_access, "ccn-fifo-blocksize");
    p_sys->buf = calloc(1, p_sys->i_bufsize);
    if (p_sys->buf == NULL) {
        i_err = VLC_ENOMEM;
        goto exit_error;
    }
    p_sys->p_template = make_data_template();
    p_sys->incoming = calloc(1, sizeof(struct ccn_closure));
    if (p_sys->incoming == NULL) {
        i_err = VLC_ENOMEM;
        goto exit_error;
    }
#if (VLCPLUGINVER >= 120)
    msg_Dbg(p_access, "CCN.Open %s, closure %p",
            p_access->psz_location, p_sys->incoming);
#else
    msg_Dbg(p_access, "CCN.Open %s, closure %p",
            p_access->psz_path, p_sys->incoming);
#endif
    p_sys->incoming->data = p_access; /* so CCN callbacks can find p_sys */
    p_sys->incoming->p = &incoming_content; /* the CCN callback */

    p_sys->ccn = ccn_create();
    if (p_sys->ccn == NULL || ccn_connect(p_sys->ccn, NULL) == -1) {
        goto exit_error;
    }
    p_name = ccn_charbuf_create();
    if (p_name == NULL) {
        i_err = VLC_ENOMEM;
        goto exit_error;
    }
#if (VLCPLUGINVER >= 120)
    i_ret = ccn_name_from_uri(p_name, p_access->psz_location);
#else
    i_ret = ccn_name_from_uri(p_name, p_access->psz_path);
#endif
    if (i_ret < 0) {
        goto exit_error;
    }
    p_sys->p_name = ccn_charbuf_create();
    if (p_sys->p_name == NULL) {
        i_err = VLC_ENOMEM;
        goto exit_error;
    }
    i_ret = ccn_resolve_version(p_sys->ccn, p_name, CCN_V_HIGHEST,
                                CCN_VERSION_TIMEOUT);
    ccn_charbuf_append_charbuf(p_sys->p_name, p_name);
    if (i_ret == 0) {
        /* name is versioned, so get the header to obtain the length */
        p_header = ccn_get_header(p_sys->ccn, p_name, CCN_HEADER_TIMEOUT);
        if (p_header != NULL) {
            p_access->info.i_size = p_header->length;
            ccn_header_destroy(&p_header);
        }
        msg_Dbg(p_access, "Set length %"PRId64, p_access->info.i_size);
    }
    ccn_charbuf_destroy(&p_name);
    p_name = sequenced_name(p_sys->p_name, 0);
    i_ret = ccn_express_interest(p_sys->ccn, p_name, p_sys->incoming,
                                 p_sys->p_template);
    ccn_charbuf_destroy(&p_name);
    if (i_ret < 0) {
        goto exit_error;
    }

    p_sys->p_fifo = block_FifoNew();
    if (p_sys->p_fifo == NULL) {
        i_err = VLC_ENOMEM;
        goto exit_error;
    }
#if (VLCPLUGINVER <= 99)
    i_ret = vlc_thread_create(p_access, "CCN run thread", ccn_event_thread,
                              VLC_THREAD_PRIORITY_INPUT, false);
#else
    i_ret = vlc_thread_create(p_access, "CCN run thread", ccn_event_thread,
                              VLC_THREAD_PRIORITY_INPUT);
#endif
    if (i_ret == 0)
        return VLC_SUCCESS;

 exit_error:
    msg_Err(p_access, "CCN.Open failed");
    if (p_sys->p_fifo) {
        block_FifoRelease(p_sys->p_fifo);
        p_sys->p_fifo = NULL;
    }
    ccn_charbuf_destroy(&p_name);
    if (p_sys->incoming) {
        free(p_sys->incoming);
        p_sys->incoming = NULL;
    }
    ccn_charbuf_destroy(&p_sys->p_name);
    ccn_destroy(&(p_sys->ccn));
    if (p_sys->buf) {
        free(p_sys->buf);
        p_sys->buf = NULL;
    }
    free(p_sys);
    return (i_err);
}

/*****************************************************************************
 * CCNClose: free unused data structures
 *****************************************************************************/
static void CCNClose(vlc_object_t *p_this)
{
    access_t     *p_access = (access_t *)p_this;
    access_sys_t *p_sys = p_access->p_sys;

    msg_Dbg(p_access, "CCN.Close called");
    vlc_object_kill(p_access); 
    if (p_sys->p_fifo)
        block_FifoWake(p_sys->p_fifo);
    vlc_thread_join(p_access);
    if (p_sys->p_fifo) {
        block_FifoRelease(p_sys->p_fifo);
        p_sys->p_fifo = NULL;
    }
    ccn_destroy(&(p_sys->ccn));
    if (p_sys->buf != NULL) free(p_sys->buf);
    free(p_sys);
}

/*****************************************************************************
 * CCNBlock:
 *****************************************************************************/
static block_t *CCNBlock(access_t *p_access)
{
    access_sys_t *p_sys = p_access->p_sys;
    block_t *p_block = NULL;

    if( p_access->info.b_eof ) {
        msg_Dbg(p_access, "CCN.Block eof");
        return NULL;
    }

    if (!vlc_object_alive(p_access))
        return NULL;

    p_block = block_FifoGet(p_sys->p_fifo);
    if (p_block == NULL)
        return NULL;

    msg_Dbg(p_access, "CCNBlock %zu bytes @ %"PRId64, p_block->i_buffer, p_access->info.i_pos);
    p_access->info.i_pos += p_block->i_buffer;
    if (p_block->i_buffer == 0) {
        p_access->info.i_size = p_access->info.i_pos;
        p_access->info.b_eof = true;
    }

    return (p_block);
}
#if 0
/* this is not needed, blocks work better... but just in case... */

/*****************************************************************************
 * CCNRead:
 *****************************************************************************/
static ssize_t CCNRead(access_t *p_access, uint8_t *buf, size_t size)
{
    access_sys_t *p_sys = p_access->p_sys;
    block_t *p_block;
    size_t block_size = 0;
    size_t result_size = 0;



    msg_Dbg(p_access, "CCN Read size %d @ %"PRId64, size, p_access->info.i_pos);
    while(result_size < size) {
        p_block = block_FifoShow(p_sys->p_fifo);
        if (p_block == NULL) 
            return (result_size);
        block_size = p_block->i_buffer;
        if (block_size == 0) {
            p_access->info.b_eof = true;
            return (result_size);
        }
        if (block_size <= (size - result_size)) {
            p_block = block_FifoGet(p_sys->p_fifo);
            memcpy(buf + result_size, p_block->p_buffer, block_size);
            block_Release(p_block);
            p_access->info.i_pos += block_size;
            result_size += block_size;
            msg_Dbg(p_access, "CCN Read: used all of block of %d bytes", block_size);
        } else {
            int used = size - result_size;
            memcpy(buf + result_size, p_block->p_buffer, used);
            p_block->p_buffer += used;
            p_block->i_buffer -= used;
            p_access->info.i_pos += used;
            result_size += used;
            msg_Dbg(p_access, "CCN Read: used %d of block of %d bytes", used, block_size);
        }
    }
    return (result_size);
}
#endif
/*****************************************************************************
 * CCNSeek:
 *****************************************************************************/
#if (VLCPLUGINVER < 110)
static int CCNSeek(access_t *p_access, int64_t i_pos)
#else
static int CCNSeek(access_t *p_access, uint64_t i_pos)
#endif
{
    access_sys_t *p_sys = p_access->p_sys;
    struct ccn_charbuf *p_name;
    int i_ret;

#if (VLCPLUGINVER < 110)
    if (i_pos < 0) {
        msg_Warn(p_access, "Attempting to seek before the beginning %"PRId64".", i_pos);
        i_pos = 0;
    }
#endif
    /* flush the FIFO, restart from the specified point */
    block_FifoEmpty(p_sys->p_fifo);
    /* forget any data in the intermediate buffer */
    p_sys->i_bufoffset = 0;
    p_sys->incoming = calloc(1, sizeof(struct ccn_closure));
    if (p_sys->incoming == NULL) {
        return (VLC_EGENERIC);
    }
    msg_Dbg(p_access, "CCN.Seek to %"PRId64", closure %p",
            i_pos, p_sys->incoming);
    p_sys->incoming->data = p_access; /* so CCN callbacks can find p_sys */
    p_sys->incoming->p = &incoming_content; /* the CCN callback */
    p_sys->i_pos = i_pos;
    p_name = sequenced_name(p_sys->p_name, p_sys->i_pos / CCN_CHUNK_SIZE);
    ccn_express_interest(p_sys->ccn, p_name, p_sys->incoming, p_sys->p_template);
    ccn_charbuf_destroy(&p_name);    

    p_access->info.i_pos = i_pos;
    p_access->info.b_eof = false;
    return (VLC_SUCCESS);
}
/*****************************************************************************
 * Control:
 *****************************************************************************/
static int CCNControl(access_t *p_access, int i_query, va_list args)
{
    access_sys_t *p_sys = p_access->p_sys;
    bool   *pb_bool;
    int          *pi_int;
    int64_t      *pi_64;

    switch(i_query)
        {
        case ACCESS_CAN_SEEK:
        case ACCESS_CAN_FASTSEEK:
        case ACCESS_CAN_CONTROL_PACE:
        case ACCESS_CAN_PAUSE:
            pb_bool = (bool*)va_arg(args, bool *);
            *pb_bool = true;
            break;

#if (VLCPLUGINVER <= 99)
        case ACCESS_GET_MTU:
            pi_int = (int*)va_arg(args, int *);
            *pi_int = 0;
            break;
#endif

        case ACCESS_GET_PTS_DELAY:
            pi_64 = (int64_t*)va_arg(args, int64_t *);
            *pi_64 = (int64_t)var_GetInteger(p_access, "ccn-caching") * INT64_C(1000);
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
            msg_Warn(p_access, "CCN unimplemented query in control - %d", i_query);
            return VLC_EGENERIC;

        }
    return VLC_SUCCESS;
}

static void *ccn_event_thread(vlc_object_t *p_this)
{
    access_t *p_access = (access_t *)p_this;
    access_sys_t *p_sys = p_access->p_sys;
    struct ccn *ccn = p_sys->ccn;
    int res = 0;
#if (VLCPLUGINVER > 99)
    int cancel = vlc_savecancel();
#endif

    while (res >= 0 && vlc_object_alive(p_access)) {
        res = ccn_run(ccn, 1000);
        if (res < 0 && ccn_get_connection_fd(ccn) == -1) {
            /* Try reconnecting, after a bit of delay */
            msleep((500 + (getpid() % 512)) * 1024);
            res = ccn_connect(ccn, NULL);
        }
    }
    if (res < 0) {
        vlc_object_kill(p_access);
        block_FifoWake(p_sys->p_fifo);
    }
#if (VLCPLUGINVER > 99)
    vlc_restorecancel(cancel);
#endif
}

enum ccn_upcall_res
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
    struct ccn_charbuf *templ = NULL;
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    const unsigned char *data = NULL;
    size_t data_size = 0;
    size_t written;
    const unsigned char *ib = NULL; /* info->interest_ccnb */
    struct ccn_indexbuf *ic = NULL;
    int first = 0;
    int res;

    switch (kind) {
    case CCN_UPCALL_FINAL:
        msg_Dbg(p_access, "CCN upcall final %p", selfp);
        if (selfp == p_sys->incoming)
            p_sys->incoming = NULL;
        free(selfp);
        return(CCN_UPCALL_RESULT_OK);
    case CCN_UPCALL_INTEREST_TIMED_OUT:
        if (selfp != p_sys->incoming) {
            msg_Dbg(p_access, "CCN Interest timed out on dead closure %p", selfp);
            return(CCN_UPCALL_RESULT_OK);
        }
        msg_Dbg(p_access, "CCN upcall reexpress -- timed out");
        if (p_sys->timeouts > 5) {
            msg_Dbg(p_access, "CCN upcall reexpress -- too many reexpressions");
            vlc_object_kill(p_access);
            if (p_sys->p_fifo)
                block_FifoWake(p_sys->p_fifo);
            return(CCN_UPCALL_RESULT_OK);
        }
        p_sys->timeouts++;
        return(CCN_UPCALL_RESULT_REEXPRESS); // XXX - may need to reseed bloom filter
    case CCN_UPCALL_CONTENT_UNVERIFIED:
        if (selfp != p_sys->incoming) {
            msg_Dbg(p_access, "CCN unverified content on dead closure %p", selfp);
            return(CCN_UPCALL_RESULT_OK);
        }
        return (CCN_UPCALL_RESULT_VERIFY);

    case CCN_UPCALL_CONTENT:
        if (selfp != p_sys->incoming) {
            msg_Dbg(p_access, "CCN content on dead closure %p", selfp);
            return(CCN_UPCALL_RESULT_OK);
        }
        break;
    default:
        msg_Warn(p_access, "CCN upcall result error");
        return(CCN_UPCALL_RESULT_ERR);
    }

    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    ib = info->interest_ccnb;
    ic = info->interest_comps;
    res = ccn_content_get_value(ccnb, ccnb_size, info->pco, &data, &data_size);
    if (res < 0) abort();

    p_sys->timeouts = 0;

    /* was this the last block? */
    /* TODO:  the test below should get refactored into the library */
    if (info->pco->offset[CCN_PCO_B_FinalBlockID] !=
        info->pco->offset[CCN_PCO_E_FinalBlockID]) {
        const unsigned char *finalid = NULL;
        size_t finalid_size = 0;
        const unsigned char *nameid = NULL;
        size_t nameid_size = 0;
        struct ccn_indexbuf *cc = info->content_comps;
        ccn_ref_tagged_BLOB(CCN_DTAG_FinalBlockID, ccnb,
                            info->pco->offset[CCN_PCO_B_FinalBlockID],
                            info->pco->offset[CCN_PCO_E_FinalBlockID],
                            &finalid,
                            &finalid_size);
        if (cc->n < 2) abort();
        ccn_ref_tagged_BLOB(CCN_DTAG_Component, ccnb,
                            cc->buf[cc->n - 2],
                            cc->buf[cc->n - 1],
                            &nameid,
                            &nameid_size);
        if (finalid_size == nameid_size && 0 == memcmp(finalid, nameid, nameid_size)) {
            b_last = true;
        }
    }
    
    /* a short block can also indicate the end, if the client isn't using FinalBlockID */
    if (data_size < CCN_CHUNK_SIZE)
        b_last = true;
    /* something to process */
    if (data_size > 0) {
        start_offset = p_sys->i_pos % CCN_CHUNK_SIZE;
        if (start_offset > data_size) {
            msg_Err(p_access, "start_offset %"PRId64" > data_size %zu", start_offset, data_size);
        } else {
            if ((data_size - start_offset) + p_sys->i_bufoffset > p_sys->i_bufsize) {
                /* won't fit in buffer, release the buffer upstream */
                p_block = block_New(p_access, p_sys->i_bufoffset);
                memcpy(p_block->p_buffer, p_sys->buf, p_sys->i_bufoffset);
                block_FifoPut(p_sys->p_fifo, p_block);
                p_sys->i_bufoffset = 0;
            }
            /* will fit in buffer */
            memcpy(p_sys->buf + p_sys->i_bufoffset, data + start_offset, data_size - start_offset);
            p_sys->i_bufoffset += (data_size - start_offset);
        }
    }

    /* if we're done, indicate so with a 0-byte block, release any buffered data upstream,
     * and don't express an interest
     */
    if (b_last) {
        if (p_sys->i_bufoffset > 0) {
            p_block = block_New(p_access, p_sys->i_bufoffset);
            memcpy(p_block->p_buffer, p_sys->buf, p_sys->i_bufoffset);
            block_FifoPut(p_sys->p_fifo, p_block);
            p_sys->i_bufoffset = 0;
        }
        block_FifoPut(p_sys->p_fifo, block_New(p_access, 0));
        return (CCN_UPCALL_RESULT_OK);
    }

#if (VLCPLUGINVER < 110)
    /* 0.9.9 did not include the block_FifoPace function */
    while (block_FifoCount(p_sys->p_fifo) > p_sys->i_fifo_max) {
        if (first == 0) {
            msg_Dbg(p_access, "fifo full");
        }
        first++;
        msleep(100000);
        if (!vlc_object_alive(p_access)) return(CCN_UPCALL_RESULT_OK);
    }
    if (first > 0) msg_Dbg(p_access, "fifo spun %d", first);
#else
    /* it was introduced, but not exported, and we're wating
     * for it to appear sometime post 1.0.3 -- version 1.1.0 is it.
     */
    block_FifoPace(p_sys->p_fifo, p_sys->i_fifo_max, SIZE_MAX);
#endif

    /* Ask for the next fragment */
    p_sys->i_pos = CCN_CHUNK_SIZE * (1 + (p_sys->i_pos / CCN_CHUNK_SIZE));
    name = sequenced_name(p_sys->p_name, p_sys->i_pos / CCN_CHUNK_SIZE);
    res = ccn_express_interest(info->h, name, selfp, NULL);
    ccn_charbuf_destroy(&name);

    if (res < 0) abort();
    
    return(CCN_UPCALL_RESULT_OK);
}

static struct ccn_charbuf *
sequenced_name(struct ccn_charbuf *basename, uintmax_t seq)
{
    struct ccn_charbuf *name = NULL;
    
    name = ccn_charbuf_create();
    ccn_charbuf_append_charbuf(name, basename);
    ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, seq);
    return(name);
}

struct ccn_charbuf *
make_data_template()
{
    struct ccn_charbuf *templ = ccn_charbuf_create();
    ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
    ccn_charbuf_append_tt(templ, CCN_DTAG_Name, CCN_DTAG);
    ccn_charbuf_append_closer(templ); /* </Name> */
    ccn_charbuf_append_tt(templ, CCN_DTAG_MaxSuffixComponents, CCN_DTAG);
    ccnb_append_number(templ, 1);
    ccn_charbuf_append_closer(templ); /* </MaxSuffixComponents> */
    ccn_charbuf_append_closer(templ); /* </Interest> */
    return(templ);
}

