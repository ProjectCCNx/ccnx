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

/**
 * This implements the default strategy.
 *
 * Eventually there will be a way to have other strategies.
 */
void
strategy0_callout(struct ccnd_handle *h,
                  struct ccn_strategy *ie,
                  enum ccn_strategy_op op)
{
    struct pit_face_item *x = NULL;
    struct pit_face_item *p = NULL;
    struct strategy_state *npe = NULL;
//    struct ccn_indexbuf *tap = NULL;
    unsigned best = CCN_NOFACEID;
    unsigned randlow, randrange;
    unsigned nleft;
    unsigned amt;
    int usec;
    
    switch (op) {
        case CCNST_NOP:
            break;
        case CCNST_FIRST:
            
//            npe = get_fib_npe(h, ie);
//            if (npe != NULL)
//                tap = npe->tap;
            npe = strategy_getstate(h, ie);
            best = npe->src;
            if (best == CCN_NOFACEID)
                best = npe->src = npe->osrc;
            /* Find our downstream; right now there should be just one. */
            for (x = ie->pfl; x != NULL; x = x->next)
                if ((x->pfi_flags & CCND_PFI_DNSTREAM) != 0)
                    break;
//            if (x == NULL || (x->pfi_flags & CCND_PFI_PENDING) == 0) {
//                ccnd_debug_ccnb(h, __LINE__, "canthappen", NULL,
//                                ie->interest_msg, ie->size);
//                break;
//            }
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
                        p = send_interest(h, ie->ie, x, p);
                        strategy_settimer(h, ie->ie, npe->usec, CCNST_TIMER);
                    }
//                    else if (ccn_indexbuf_member(tap, p->faceid) >= 0)
//                        p = send_interest(h, ie->ie, x, p);
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
            adjust_predicted_response(h, ie->ie, 1);
            break;
        case CCNST_SATISFIED:
            break;
        case CCNST_TIMEOUT:
            break;
    }
}
