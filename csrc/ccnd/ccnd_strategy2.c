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
#include <limits.h>
#include "ccnd_strategy.h"
#include "ccnd_private.h" /* for logging, should be exposed rather than private */


struct face_state_item {
    unsigned faceid;           /**< the face id this entry describes */
    unsigned pending;          /**< pending interest count */
    unsigned timedout;         /**< has this face timed out recently */
    struct pit_face_item *pfi; /**< temporary pointer to the item, set to NULL at exit */
};

#define N_FACESTATE 32

struct face_state {
    unsigned n;
    struct face_state_item *items;
};

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
    unsigned i, count;
    int best;
    unsigned smallestq;
    unsigned pending;
    
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
            /* Find our downstream; right now there should be just one. */
            for (x = strategy->pfl; x != NULL; x = x->next)
                if ((x->pfi_flags & CCND_PFI_DNSTREAM) != 0)
                    break;
            if (x == NULL || (x->pfi_flags & CCND_PFI_PENDING) == 0)
                return;
            
            /* Ensure that we have a face state entry for every face in the pfl
             */
            count = 0;
            smallestq = INT_MAX;
            for (p = strategy->pfl; p!= NULL; p = p->next) {
                /* avoid messing with any downstream faces */
                if ((p->pfi_flags & CCND_PFI_DNSTREAM) != 0 ||
                    (p->pfi_flags & CCND_PFI_UPENDING) != 0)
                    continue;
                /* we will make the send upstream decision */
                p->pfi_flags &= ~CCND_PFI_SENDUPST;
                for (i = 0; i < face_state->n; i++) {
                    if (face_state->items[i].faceid == p->faceid) {
                        face_state->items[i].pfi = p;
                        // ccnd_msg(h, "loadsharing: found face %u at index %u flags 0x%x", p->faceid, i, p->pfi_flags);
                        pending = face_state->items[i].pending << face_state->items[i].timedout;
                        if (pending < smallestq) {
                            count = 1;
                            smallestq = pending;
                        } else if (pending == smallestq) {
                            count++;
                        }
                        break;
                    }
                }
                if (i == face_state->n) {
                    /* there was no entry for this face, make one. */
                    if (i < N_FACESTATE) {
                        // ccnd_msg(h, "loadsharing: adding face %u", p->faceid);
                        face_state->items[i].faceid = p->faceid;
                        face_state->items[i].pending = 0;
                        face_state->items[i].timedout = 0;
                        face_state->items[i].pfi = p;
                        face_state->n++;
                        if (smallestq > 0) {
                            smallestq = 0;
                            count = 1;
                        } else {
                            count++;
                        }
                    }
                }
            }
            /* no eligible faces, so nothing to do */
            if (count == 0)
                return;
            
            best = ccnd_random(h) % count;
            // ccnd_msg(h, "loadsharing: smallestq %u count %u best %u", smallestq, count, best);
            for (i = 0; i < face_state->n; i++) {
                if (face_state->items[i].pending == smallestq &&
                    face_state->items[i].pfi != NULL) {
                    if (best == 0) {
                        face_state->items[i].pfi->pfi_flags |= CCND_PFI_SENDUPST;
                        ccnd_msg(h, "loadsharing: selecting face %u pending %u", face_state->items[i].pfi->faceid,
                                 face_state->items[i].pending);
                        face_state->items[i].pending++;
                        break;
                    }
                    best--;
                }
            }
            for (i = 0; i < face_state->n; i++) {
                // if (face_state->items[i].pfi)
                    // ccnd_msg(h, "loadsharing: done face %u flags 0x%x pending %u",
                    //         face_state->items[i].pfi->faceid,
                    //         face_state->items[i].pfi->pfi_flags,
                    //         face_state->items[i].pending);
                face_state->items[i].pfi = NULL;
            }
            break;
        case CCNST_NEWUP:
            for (p = strategy->pfl; p!= NULL; p = p->next) {
                if (p->faceid == faceid) break;
            }
            p->pfi_flags &= ~CCND_PFI_SENDUPST;
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
            for (p = strategy->pfl; p!= NULL; p = p->next) {
                if (p->faceid == faceid) break;
            }
            p->pfi_flags &= ~CCND_PFI_SENDUPST;
            p->pfi_flags &= ~CCND_PFI_UPENDING;
            for (i = 0; i < face_state->n; i++)
                if (face_state->items[i].faceid == faceid) {
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
            for (p = strategy->pfl; p!= NULL; p = p->next) {
                if (p->faceid == faceid) break;
            }
            p->pfi_flags &= ~CCND_PFI_SENDUPST;
            p->pfi_flags &= ~CCND_PFI_UPENDING;
            /* Decrement the pending count on the face (faceid) that responded */
            for (i = 0; i < face_state->n; i++)
                if (face_state->items[i].faceid == faceid) {
                    face_state->items[i].timedout = 0;
                    if (face_state->items[i].pending > 0)
                        face_state->items[i].pending--;
                    break;
                }
            break;
        case CCNST_TIMEOUT: // all downstreams timed out, PIT entry will go away
            /* Interest has not been satisfied or refreshed */
            break;
        case CCNST_FINALIZE:
            /* Free the strategy per registration point private data */
            for (i = 0; i < face_state->n; i++) {
                    ccnd_msg(h, "loadsharing: finalize face %u pending %u",
                             face_state->items[i].faceid,
                             face_state->items[i].pending);
            }
            free(face_state->items);
            free(face_state);
            instance->data = NULL;
            break;
    }
}
