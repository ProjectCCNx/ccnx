/*
 * @file ccnd/ccnd_strategy2.c
 *
 * Part of ccnd - the CCNx Daemon
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include "ccnd_strategy.h"

struct face_state_item {
    unsigned faceid;           /**< the face id this entry describes */
    unsigned pending;          /**< pending interest count */
    unsigned timedout;         /**< has this face timed out recently */
    unsigned histindex;        /**< index in overall history structure of temporary item */
    struct pit_face_item *pfi; /**< temporary pointer to the item, set to NULL at exit */
};

#define N_FACESTATE 32

struct face_state {
    unsigned n;
    struct face_state_item *items;
};

int
compare_item(const void *a, const void *b)
{
    struct face_state_item *a_item = (struct face_state_item *)a;
    struct face_state_item *b_item = (struct face_state_item *)b;
    unsigned a_down, b_down, a_pending, b_pending;
    
    a_down = (a_item->pfi->pfi_flags & CCND_PFI_DNSTREAM) != 0;
    b_down = (b_item->pfi->pfi_flags & CCND_PFI_DNSTREAM) != 0;
    a_pending = a_item->pending * (a_item->timedout ? 2 : 1);
    b_pending = b_item->pending * (b_item->timedout ? 2 : 1);
    
    /* sort any downstream faces to the end */
    if (a_down && b_down) return 0;
    if (a_down) return (1);
    if (b_down) return (-1);
    
    /* sort upstream faces by their pending count */
    if (a_pending < b_pending) return (-1);
    if (a_pending > b_pending) return (1);
    return (0);
    
}

/**
 * This implements a distribution by performance strategy.
 *
 * The number of pending interests is a proxy for the performance of the face,
 * an interest is sent on the face with the minimum pending, or randomly to
 * one selected from those with the minimum.
 */

void
ccnd_loadsharing_strategy_impl(struct ccnd_handle *h,
                               struct strategy_instance *instance,
                               struct ccn_strategy *strategy,
                               enum ccn_strategy_op op,
                               unsigned faceid)
{
    struct pit_face_item *x = NULL;
    struct pit_face_item *p = NULL;
    struct face_state *face_state = (struct face_state *)instance->data;
    unsigned i;
    unsigned best;
    struct face_state_item t_items[N_FACESTATE];
    unsigned n_items = 0;
    
    memset(t_items, 0, sizeof(t_items));
    
    switch (op) {
        case CCNST_NOP:
            break;
        case CCNST_INIT:
            /* Allocate strategy per registration point private data for
             * per-face pending interest count undifferentiated by (interest) prefix.
             */
            if (face_state != NULL) {
                free(face_state->items);
                free(face_state);
            }
            face_state = calloc(1, sizeof(*face_state));
            face_state->items = calloc(N_FACESTATE, sizeof(*face_state->items));
            instance->data = face_state;
            break;
        case CCNST_FIRST:  /* newly created interest entry */
            /* clear any default timing information */
            for (x = strategy->pfl; x != NULL; x = x->next)
                x->expiry = 0;
            /* Find our downstream; right now there should be just one. */
            for (x = strategy->pfl; x != NULL; x = x->next)
                if ((x->pfi_flags & CCND_PFI_DNSTREAM) != 0)
                    break;
            if (x == NULL || (x->pfi_flags & CCND_PFI_PENDING) == 0)
                return;
            
            /* Ensure that we have a face state entry for every face in the pfl
             */
            for (p = strategy->pfl; p!= NULL; p = p->next) {
                for (i = 0; i < face_state->n; i++) {
                    if (face_state->items[i].faceid == p->faceid) {
                        fprintf(stderr, "strategy2: found face %u at index %u\n", p->faceid, i);
                        if (p != x && ((p->pfi_flags & CCND_PFI_UPENDING) == 0)) {
                            t_items[n_items] = face_state->items[i];
                            t_items[n_items].pfi = p;
                            t_items[n_items].histindex = i;
                            n_items++;
                        }
                        break;
                    }
                }
                if (i == face_state->n) {
                    /* there was no entry for this face, make one. */
                    if (i < N_FACESTATE) {
                        fprintf(stderr, "strategy2: adding face %u\n", p->faceid);
                        face_state->items[i].faceid = p->faceid;
                        face_state->items[i].pending = 0;
                        face_state->n++;
                        if (p != x && ((p->pfi_flags & CCND_PFI_UPENDING) == 0)) {
                            t_items[n_items] = face_state->items[i];
                            t_items[n_items].pfi = p;
                            t_items[n_items].histindex = i;
                            n_items++;
                        }
                    }
                }
            }
            /* no eligible faces, so nothing to do */
            if (n_items == 0)
                return;
            
            /* Sort the faces by number of items pending, lowest first
             */
            qsort(t_items, n_items, sizeof(t_items[0]), &compare_item);
            
            for (i = 0; i < n_items; i++) {
                if (t_items[i].pending > t_items[0].pending) break;
            }
            best = ccnd_random(h) % i;
            fprintf(stderr, "strategy2: i %u best %u\n", i, best);
            face_state->items[t_items[best].histindex].pending++;
            fprintf(stderr, "strategy2: face %u selected, pending %u\n",
                    t_items[best].pfi->faceid, t_items[best].pending);
            send_interest(h, strategy->ie, x, t_items[best].pfi);
            break;
        case CCNST_NEWUP:
            break;
        case CCNST_NEWDN:
            break;
        case CCNST_EXPUP:
            /*
             * Someone has not responded in time.
             * We decrement the pending count and flag the face has having had
             * a timeout so that the face selection can penalize non-responding
             * faces.
             */
            for (i = 0; i < face_state->n; i++)
                if (face_state->items[i].faceid == faceid) {
                    fprintf(stderr, "strategy2: timed out face %u at index %u\n", faceid, i);
                    if (face_state->items[i].pending > 0) {
                        face_state->items[i].pending--;
                    }
                    face_state->items[i].timedout = 1;
                    break;
                }
            break;
        case CCNST_EXPDN:
            break;
        case CCNST_REFRESH:
            break;
        case CCNST_TIMER:
            break;
        case CCNST_SATISFIED:
            /* Decrement the pending count on the face (faceid) that responded */
            for (i = 0; i < face_state->n; i++)
                if (face_state->items[i].faceid == faceid) {
                    face_state->items[i].timedout = 0;
                    if (face_state->items[i].pending > 0)
                        face_state->items[i].pending--;
                    break;
                }
            fprintf(stderr, "strategy2: face %u response\n", faceid);
            break;
        case CCNST_TIMEOUT: // all downstreams timed out, PIT entry will go away
            /* Interest has not been satisfied or refreshed */
            fprintf(stderr, "strategy2: face %u TIMEOUT\n", faceid);
            break;
        case CCNST_FINALIZE:
            /* Free the strategy per registration point private data */
            free(face_state->items);
            free(face_state);
            instance->data = NULL;
            break;
    }
}
