/*
 * ccnd/ccnd_strategy0.c
 *
 * Main program of ccnd - the CCNx Daemon
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

#include "ccnd_strategy.h"

static void
adjust_predicted_response(struct ccnd_handle *h,
                          struct strategy_state *sst, int up);

/**
 * This implements the default strategy.
 *
 * Eventually there will be a way to have other strategies.
 */
void
strategy0_callout(struct ccnd_handle *h,
                  struct ccn_strategy *ie,
                  enum ccn_strategy_op op,
                  unsigned faceid)
{
    struct pit_face_item *x = NULL;
    struct pit_face_item *p = NULL;
    struct strategy_state *npe = NULL;
    struct strategy_state *sst[2] = {NULL};
    unsigned best = CCN_NOFACEID;
    unsigned randlow, randrange;
    unsigned nleft;
    unsigned amt;
    int usec;
    int i;
    
    switch (op) {
        case CCNST_NOP:
            break;
        case CCNST_FIRST:
            strategy_getstate(h, ie, sst, 2);
            npe = sst[0];
            best = npe->src;
            if (best == CCN_NOFACEID)
                best = npe->src = npe->osrc;
            /* Find our downstream; right now there should be just one. */
            for (x = ie->pfl; x != NULL; x = x->next)
                if ((x->pfi_flags & CCND_PFI_DNSTREAM) != 0)
                    break;
            if (x == NULL || (x->pfi_flags & CCND_PFI_PENDING) == 0)
                return;
            if (best == CCN_NOFACEID) {
                randlow = 4000;
                randrange = 75000;
            }
            else {
                randlow = npe->usec;
                if (randlow < 2000)
                    randlow = 100 + ccnd_random(h) % 4096U;
                randrange = (randlow + 1) / 2;
            }
            nleft = 0;
            for (p = ie->pfl; p!= NULL; p = p->next) {
                if ((p->pfi_flags & CCND_PFI_UPSTREAM) != 0) {
                    if (p->faceid == best) {
                        /* we may have already sent in case of TAP */
                        if ((p->pfi_flags & CCND_PFI_UPENDING) == 0)
                            p = send_interest(h, ie->ie, x, p);
                        strategy_settimer(h, ie->ie, npe->usec, CCNST_TIMER);
                    }
                    else if ((p->pfi_flags & CCND_PFI_UPENDING) != 0)
                        /* TAP interest has already been sent */;
                    else if (p->faceid == npe->osrc)
                        pfi_set_expiry_from_micros(h, ie->ie, p, randlow);
                    else {
                        /* Want to preserve the order of the rest */
                        nleft++;
                        p->pfi_flags |= CCND_PFI_SENDUPST;
                    }
                }
            }
            if (nleft > 0) {
                /* Send remainder in order, with randomized timing */
                amt = (2 * randrange + nleft - 1) / nleft;
                if (amt == 0) amt = 1; /* paranoia - should never happen */
                usec = randlow;
                for (p = ie->pfl; p!= NULL; p = p->next) {
                    if ((p->pfi_flags & CCND_PFI_SENDUPST) != 0) {
                        pfi_set_expiry_from_micros(h, ie->ie, p, usec);
                        usec += ccnd_random(h) % amt;
                    }
                }
            }
            break;
        case CCNST_TIMER:
            /*
             * Our best choice has not responded in time.
             * Increase the predicted response.
             */
            strategy_getstate(h, ie, sst, 2);
            for (i = 0; i < 2 && sst[i] != NULL; i++)
                adjust_predicted_response(h, sst[i], 1);
            break;
        case CCNST_SATISFIED:
            /* Keep a little history about where matching content comes from. */
            strategy_getstate(h, ie, sst, 2);
            for (i = 0; i < 2 && sst[i] != NULL; i++) {
                struct strategy_state *s = sst[i];
                if (s->src == faceid)
                    adjust_predicted_response(h, s, 0);
                else if (s->src == CCN_NOFACEID)
                    s->src = faceid;
                else {
                    s->osrc = s->src;
                    s->src = faceid;
                }
            }
            break;
        case CCNST_TIMEOUT:
            /* Interest has not been satisfied or refreshed */
            break;
    }
}

// XXX - import this late so we don't pollute too much.
#include "ccnd_private.h"

/**
 * Adjust the predicted response associated with a name prefix entry.
 *
 * It is decreased by a small fraction if we get content within our
 * previous predicted value, and increased by a larger fraction if not.
 *
 */
static void
adjust_predicted_response(struct ccnd_handle *h,
                          struct strategy_state *sst, int up)
{
    unsigned t = sst->usec;
    if (up)
        t = t + (t >> 3);
    else
        t = t - (t >> 7);
    if (t < 127)
        t = 127;
    else if (t > h->predicted_response_limit)
        t = h->predicted_response_limit;
    sst->usec = t;
}
