/**
 * @file ccn_seqwriter.c
 * @brief
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <ccn/ccn.h>
#include <ccn/seqwriter.h>

#define MAX_DATA_SIZE 4096

struct ccn_seqwriter {
    struct ccn_closure cl;
    struct ccn *h;
    struct ccn_charbuf *nb;
    struct ccn_charbuf *nv;
    struct ccn_charbuf *buffer;
    struct ccn_charbuf *cob0;
    uintmax_t seqnum;
    int batching;
    unsigned char interests_possibly_pending;
    unsigned char closed;
};

static struct ccn_charbuf *
seqw_next_cob(struct ccn_seqwriter *w)
{
    struct ccn_charbuf *cob = ccn_charbuf_create();
    struct ccn_charbuf *name = ccn_charbuf_create();
    struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
    int res;
    
    if (w->closed)
        sp.sp_flags |= CCN_SP_FINAL_BLOCK;
    ccn_charbuf_append(name, w->nv->buf, w->nv->length);
    ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, w->seqnum);
    res = ccn_sign_content(w->h, cob, name, &sp, w->buffer->buf, w->buffer->length);
    if (res < 0)
        ccn_charbuf_destroy(&cob);
    ccn_charbuf_destroy(&name);
    return(cob);
}

static enum ccn_upcall_res
seqw_incoming_interest(
                       struct ccn_closure *selfp,
                       enum ccn_upcall_kind kind,
                       struct ccn_upcall_info *info)
{
    int res;
    struct ccn_charbuf *cob = NULL;
    struct ccn_seqwriter *w = selfp->data;
    
    if (w == NULL || selfp != &(w->cl))
        abort();
    switch (kind) {
        case CCN_UPCALL_FINAL:
            ccn_charbuf_destroy(&w->nb);
            ccn_charbuf_destroy(&w->nv);
            ccn_charbuf_destroy(&w->buffer);
            ccn_charbuf_destroy(&w->cob0);
            free(w);
            break;
        case CCN_UPCALL_INTEREST:
            if (w->closed || w->buffer->length > 0) {
                cob = seqw_next_cob(w);
                if (cob == NULL)
                    return(CCN_UPCALL_RESULT_OK);
                if (ccn_content_matches_interest(cob->buf, cob->length,
                                                 1, NULL,
                                                 info->interest_ccnb,
                                                 info->pi->offset[CCN_PI_E],
                                                 info->pi)) {
                    w->interests_possibly_pending = 0;
fprintf(stderr, "at %d interests_possibly_pending = %d\n", __LINE__, w->interests_possibly_pending);
                    res = ccn_put(info->h, cob->buf, cob->length);
                    if (res >= 0) {
                        w->buffer->length = 0;
                        w->seqnum++;
                        return(CCN_UPCALL_RESULT_INTEREST_CONSUMED);
                    }
                }
                ccn_charbuf_destroy(&cob);
            }
            if (w->cob0 != NULL) {
                cob = w->cob0;
                if (ccn_content_matches_interest(cob->buf, cob->length,
                                                 1, NULL,
                                                 info->interest_ccnb,
                                                 info->pi->offset[CCN_PI_E],
                                                 info->pi)) {
                    w->interests_possibly_pending = 0;
fprintf(stderr, "at %d interests_possibly_pending = %d\n", __LINE__, w->interests_possibly_pending);
                    ccn_put(info->h, cob->buf, cob->length);
                    return(CCN_UPCALL_RESULT_INTEREST_CONSUMED);
                }
            }
            w->interests_possibly_pending = 1;
fprintf(stderr, "at %d interests_possibly_pending = %d\n", __LINE__, w->interests_possibly_pending);
            break;
        default:
            break;
    }
    return(CCN_UPCALL_RESULT_OK);
}

struct ccn_seqwriter *
ccn_seqw_create(struct ccn *h, struct ccn_charbuf *name)
{
    struct ccn_seqwriter *w = NULL;
    struct ccn_charbuf *nb = NULL;
    struct ccn_charbuf *nv = NULL;
    int res;
    
    w = calloc(1, sizeof(*w));
    if (w == NULL)
        return(NULL);
    nb = ccn_charbuf_create();
    ccn_charbuf_append(nb, name->buf, name->length);
    nv = ccn_charbuf_create();
    ccn_charbuf_append(nv, name->buf, name->length);
    res = ccn_create_version(h, nv, CCN_V_NOW, 0, 0);
    if (res < 0 || nb == NULL) {
        ccn_charbuf_destroy(&nv);
        ccn_charbuf_destroy(&nb);
        free(w);
        return(NULL);
    }
    
    w->cl.p = &seqw_incoming_interest;
    w->cl.data = w;
    w->nb = nb;
    w->nv = nv;
    w->buffer = ccn_charbuf_create();
    w->h = h;
    w->seqnum = 0;
    w->interests_possibly_pending = 1;
fprintf(stderr, "at %d interests_possibly_pending = %d\n", __LINE__, w->interests_possibly_pending);
    res = ccn_set_interest_filter(h, nb, &(w->cl));
    if (res < 0) {
        ccn_charbuf_destroy(&w->nb);
        ccn_charbuf_destroy(&w->nv);
        ccn_charbuf_destroy(&w->buffer);
        free(w);
        return(NULL);
    }
    return(w);
}

int
ccn_seqw_write(struct ccn_seqwriter *w, const void *buf, size_t size)
{
    struct ccn_charbuf *cob = NULL;
    int res;
    int ans;
    
    if (w == NULL || w->buffer == NULL || size > MAX_DATA_SIZE)
        return(-1);
    ans = size;
    if (size + w->buffer->length > MAX_DATA_SIZE)
        ans = -1; /* Caller should treat like EAGAIN */
    else if (size != 0)
        ccn_charbuf_append(w->buffer, buf, size);
    if (w->interests_possibly_pending &&
        (w->batching == 0 || ans == -1)) {
        cob = seqw_next_cob(w);
        if (cob != NULL) {
            res = ccn_put(w->h, cob->buf, cob->length);
            if (res >= 0) {
                if (w->seqnum == 0) {
                    w->cob0 = cob;
                    cob = NULL;
                }
                w->buffer->length = 0;
                w->seqnum++;
                w->interests_possibly_pending = 0;
fprintf(stderr, "at %d interests_possibly_pending = %d\n", __LINE__, w->interests_possibly_pending);
            }
            ccn_charbuf_destroy(&cob);
        }
    }
    return(ans);
}

int
ccn_seqw_batch_start(struct ccn_seqwriter *w)
{
    if (w == NULL || w->closed)
        return(-1);
    return(++(w->batching));
}

int
ccn_seqw_batch_end(struct ccn_seqwriter *w)
{
    if (w == NULL || w->batching == 0)
        return(-1);
    if (--(w->batching) == 0)
        ccn_seqw_write(w, NULL, 0);
    return(w->batching);
}

int
ccn_seqw_possible_interest(struct ccn_seqwriter *w)
{
    if (w == NULL)
        return(-1);
    w->interests_possibly_pending = 1;
fprintf(stderr, "at %d interests_possibly_pending = %d\n", __LINE__, w->interests_possibly_pending);
    ccn_seqw_write(w, NULL, 0);
    return(0);
}

int
ccn_seqw_close(struct ccn_seqwriter *w)
{
    if (w == NULL || w->cl.data != w)
        return(-1);
    w->closed = 1;
    w->interests_possibly_pending = 1;
fprintf(stderr, "at %d interests_possibly_pending = %d\n", __LINE__, w->interests_possibly_pending);
    w->batching = 0;
    ccn_seqw_write(w, NULL, 0);
    ccn_set_interest_filter(w->h, w->nb, NULL);
    return(0);
}
