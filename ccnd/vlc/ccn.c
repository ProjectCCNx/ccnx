/*****************************************************************************
 * ccn.c: CCN input module
 *****************************************************************************
 * Copyright (C) 2009, Palo Alto Research Center
 * $Id:$
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston3 MA 02110-1301, USA.
 *****************************************************************************/

/*****************************************************************************
 * Preamble
 *****************************************************************************/

#ifdef HAVE_CONFIG_H
# include "config.h"
#endif

#include <vlc_common.h>
#include <vlc_plugin.h>
#include <vlc_access.h>
#include <vlc_url.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

/*****************************************************************************
 * Disable internationalization
 *****************************************************************************/
#define _(str) (str)
#define N_(str) (str)
/*****************************************************************************
 * Module descriptor
 *****************************************************************************/
#define CACHING_TEXT N_("Caching value in ms")
#define CACHING_LONGTEXT N_( \
    "Caching value for CCN streams. This " \
    "value should be set in milliseconds.")

static int  Open(vlc_object_t *);
static void Close(vlc_object_t *);
static block_t *Block(access_t *);
static int Control(access_t *p_access, int i_query, va_list args);

static void *ccn_event_thread(vlc_object_t *p_this);

vlc_module_begin();
    set_shortname(N_("CCN"));
    set_description(N_("CCN input"));
    set_category(CAT_INPUT);
    set_subcategory(SUBCAT_INPUT_ACCESS);
    add_integer("ccn-caching", 2 * DEFAULT_PTS_DELAY / 1000, NULL,
                 CACHING_TEXT, CACHING_LONGTEXT, true);
    set_capability("access", 0);
    add_shortcut("ccn");
    set_callbacks(Open, Close);
vlc_module_end();

/*****************************************************************************
 * Local prototypes
 *****************************************************************************/
struct access_sys_t
{
    vlc_url_t  url;
    vlc_mutex_t lock;
    struct ccn *ccn;
    struct ccn_closure incoming;
    int done;
};

enum ccn_upcall_res
incoming_content(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info);
/*****************************************************************************
 * Open: 
 *****************************************************************************/
static int Open(vlc_object_t *p_this)
{
    access_t     *p_access = (access_t *)p_this;
    access_sys_t *p_sys;
    struct ccn_charbuf *name;
    int res;

    /* Init p_access */
    access_InitFields(p_access);
    ACCESS_SET_CALLBACKS(NULL, Block, Control, NULL); /* let's not implement Seek yet */

    MALLOC_ERR(p_access->p_sys, access_sys_t);
    p_sys = p_access->p_sys;
    if (p_sys == NULL)
        goto exit_error;
    memset(p_sys, 0, sizeof(access_sys_t));
    p_sys->incoming.data = p_access; /* so that CCN callbacks can find the p_sys */
    p_sys->incoming.p = &incoming_content; /* the callback */
    
    name = ccn_charbuf_create();
    res = ccn_name_from_uri(name, p_access->psz_path);
    if (res < 0) {
        goto exit_error;
    }
    ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, 0);

    p_sys->ccn = ccn_create();
    if (ccn_connect(p_sys->ccn, NULL) == -1) {
        goto exit_error;
    }
    /* TODO: this will probably need a template... */
    ccn_express_interest(p_sys->ccn, name, -1, &p_sys->incoming, NULL);
    vlc_mutex_init(&(p_sys->lock));
    vlc_thread_create(p_access, "CCN run thread", ccn_event_thread,
                      VLC_THREAD_PRIORITY_OUTPUT, false);
    fprintf(stderr, "CCN: success\n");
    return VLC_SUCCESS;

 exit_error:
    /* TODO: any other cleanup */
    vlc_mutex_destroy(&(p_sys->lock));
    if (p_sys->ccn != NULL)
        ccn_destroy(&(p_sys->ccn));
    if (name != NULL)
        ccn_charbuf_destroy(&name);
    free(p_sys);
    return VLC_EGENERIC;
}

/*****************************************************************************
 * Close: free unused data structures
 *****************************************************************************/
static void Close(vlc_object_t *p_this)
{
    access_t     *p_access = (access_t *)p_this;
    access_sys_t *p_sys = p_access->p_sys;

    /* TODO: cleanup;  do we need to join the ccn incoming data thread? */
    vlc_mutex_destroy(&(p_sys->lock));
    if (p_sys->ccn != NULL)
        ccn_destroy(&(p_sys->ccn));
    free(p_sys);
}

/*****************************************************************************
 * Block:
 *****************************************************************************/
static block_t *Block(access_t *p_access)
{
    access_sys_t *p_sys = p_access->p_sys;
    block_t *p_block = NULL;

    if( p_access->info.b_eof )
        return NULL;

    /* Need to lock the data queue, pull one block off and return it*/
    return (p_block);
}
/*****************************************************************************
 * Control:
 *****************************************************************************/
static int Control(access_t *p_access, int i_query, va_list args)
{
    bool   *pb_bool;
    int          *pi_int;
    int64_t      *pi_64;

    switch(i_query)
    {
        case ACCESS_CAN_PAUSE:
        case ACCESS_CAN_SEEK:
        case ACCESS_CAN_FASTSEEK:
        case ACCESS_CAN_CONTROL_PACE:
            pb_bool = (bool*)va_arg(args, bool *);
            *pb_bool = false;
            break;

        case ACCESS_GET_MTU:
            pi_int = (int*)va_arg(args, int *);
            *pi_int = 0;
            break;

        case ACCESS_GET_PTS_DELAY:
            pi_64 = (int64_t*)va_arg(args, int64_t *);
            *pi_64 = (int64_t)var_GetInteger(p_access, "ccn-caching") * INT64_C(1000);
            break;

        case ACCESS_SET_PAUSE_STATE:
        case ACCESS_GET_TITLE_INFO:
        case ACCESS_SET_TITLE:
        case ACCESS_SET_SEEKPOINT:
        case ACCESS_SET_PRIVATE_ID_STATE:
        case ACCESS_GET_CONTENT_TYPE:
            return VLC_EGENERIC;

        default:
            msg_Warn(p_access, "unimplemented query in control");
            return VLC_EGENERIC;

    }
    return VLC_SUCCESS;
}

static void *ccn_event_thread(vlc_object_t *p_this)
{
    access_t *p_access = (access_t *)p_this;
    access_sys_t *p_sys = p_access->p_sys;
    struct ccn *ccn = p_sys->ccn;
    int res;

    while (res >= 0) {
        res = ccn_run(ccn, 500);
    }
}

enum ccn_upcall_res
incoming_content(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    access_t *p_access = (access_t *)(selfp->data);
    access_sys_t *p_sys = p_access->p_sys;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    const unsigned char *data = NULL;
    size_t data_size = 0;
    size_t written;
    const unsigned char *ib = NULL; /* info->interest_ccnb */
    struct ccn_indexbuf *ic = NULL;
    int res;

    switch (kind) {
    case CCN_UPCALL_FINAL:
        /* TODO: need to clean up, do we cancel the thread here? */
        return(CCN_UPCALL_RESULT_OK);
    case CCN_UPCALL_INTEREST_TIMED_OUT:
        return(CCN_UPCALL_RESULT_REEXPRESS); // XXX - may need to reseed bloom filter
    case CCN_UPCALL_CONTENT_UNVERIFIED:
        return(CCN_UPCALL_RESULT_VERIFY);
    case CCN_UPCALL_CONTENT:
        break;
    default:
        return(CCN_UPCALL_RESULT_ERR);
    }

    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    ib = info->interest_ccnb;
    ic = info->interest_comps;
    res = ccn_content_get_value(ccnb, ccnb_size, info->pco, &data, &data_size);
    if (res < 0) abort();
    if (info->pco->type != CCN_CONTENT_DATA) {
        /* This is spam, so need to try again excluding this one. */
        name = ccn_charbuf_create();
        ccn_name_append_components(name, ib, ic->buf[0], ic->buf[ic->n - 1]);
        note_new_exclusion(p_sys, ccnb,
                           info->pco->offset[CCN_PCO_B_Signature],
                           info->pco->offset[CCN_PCO_E_Signature]);
        templ = make_template(p_sys, info);
        res = ccn_express_interest(info->h, name, -1, selfp, templ);
        /* TODO: must not abort... */
        if (res < 0)
            abort();
        ccn_charbuf_destroy(&templ);
        ccn_charbuf_destroy(&name);
        return(CCN_UPCALL_RESULT_OK);
    }
    /* OK, we will accept this block. */
    if (data_size == 0)
        /* XXX: we're done, what else needs to be dealt with here */
        p_sys->done = 1;
    else {
        /* TODO: append it to the output queue */
    }
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
        if (finalid_size == nameid_size && 0 == memcmp(finalid, nameid, nameid_size))
            p_sys->done = 1;
    }
    
    if (p_sys->done) {
        ccn_set_run_timeout(info->h, 0);
        return(CCN_UPCALL_RESULT_OK);
    }
    
    /* Ask for the next fragment */
    name = ccn_charbuf_create();
    ccn_name_init(name);
    if (ic->n < 2) abort();
    res = ccn_name_append_components(name, ib, ic->buf[0], ic->buf[ic->n - 2]);
    if (res < 0) abort();
    ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, ++(selfp->intdata));
    clear_excludes(p_sys);
    templ = make_template(p_sys, info);
    
    res = ccn_express_interest(info->h, name, -1, selfp, templ);
    if (res < 0) abort();
    
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&name);
    
    return(CCN_UPCALL_RESULT_OK);
}
