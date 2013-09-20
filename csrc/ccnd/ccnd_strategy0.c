/*
 * @file ccnd/ccnd_strategy0.c
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

#include "ccnd_strategy.h"

#define MINE 0x65e272 // hint: openssl rand -hex 3

struct strategy_state {
    unsigned magic;              /**< MINE to mark our stuff */
    unsigned src;                /**< faceid of recent content source */
    unsigned osrc;               /**< and of older matching content */
    unsigned usec;               /**< response-time prediction */
};

CCN_STATESIZECHECK(X_strategy_state, struct strategy_state);

static void
adjust_predicted_response(struct ccnd_handle *h,
                          struct strategy_state *s, unsigned faceid);

/**
 * This implements the default strategy.
 */
void
ccnd_default_strategy_impl(struct ccnd_handle *h,
                           struct strategy_instance *instance,
                           struct ccn_strategy *strategy,
                           enum ccn_strategy_op op,
                           unsigned faceid)
{
    struct pit_face_item *x = NULL;
    struct pit_face_item *p = NULL;
    struct strategy_state *npe = NULL;
    struct strategy_state *parent = NULL;
    struct nameprefix_state *sst[2] = {NULL};
    struct strategy_state dummy = { MINE, CCN_NOFACEID, CCN_NOFACEID, 50000 };
    unsigned best = CCN_NOFACEID;
    unsigned randlow, randrange;
    unsigned nleft;
    unsigned amt;
    int usec;
    
    if (strategy != NULL) {
        /* We will want access to the state for our prefix and its parent */
        strategy_getstate(h, strategy, sst, 2);
        
        /* First get or initialize the parent nameprefix state */
        if (sst[1] == NULL)
            parent = &dummy;
        else if (sst[1]->s[0] == CCN_UNINIT) {
            parent = (struct strategy_state *)sst[1]->s;
            *parent = dummy;
        }
        else if ((sst[1]->s[0] & CCN_MAGIC_MASK) == MINE)
            parent = (struct strategy_state *)sst[1]->s;
        
        /* Now get the state for the longer prefix */
        npe = (struct strategy_state *)sst[0]->s; /* This one should not be NULL */
        if ((npe->magic & CCN_AGED) != 0) {
            if ((npe->magic & CCN_MAGIC_MASK) != MINE)
                *npe = *parent;
            else
                npe->magic = MINE;
        }
        if (npe->magic != MINE)
            npe = &dummy; /* do not walk on somebody else's state */
    }
    switch (op) {
        case CCNST_NOP:
            break;
        case CCNST_FIRST:
            best = npe->src;
            if (best == CCN_NOFACEID)
                best = npe->src = npe->osrc;
            /* Find our downstream; right now there should be just one. */
            for (x = strategy->pfl; x != NULL; x = x->next)
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
            for (p = strategy->pfl; p!= NULL; p = p->next) {
                if ((p->pfi_flags & CCND_PFI_UPSTREAM) != 0) {
                    if (p->faceid == best) {
                        /* we may have already sent in case of TAP */
                        if ((p->pfi_flags & CCND_PFI_UPENDING) == 0)
                            p = send_interest(h, strategy->ie, x, p);
                        strategy_settimer(h, strategy->ie, npe->usec, CCNST_TIMER);
                    }
                    else if ((p->pfi_flags & CCND_PFI_UPENDING) != 0)
                        /* TAP interest has already been sent */;
                    else if (p->faceid == npe->osrc)
                        pfi_set_expiry_from_micros(h, strategy->ie, p, randlow);
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
                for (p = strategy->pfl; p!= NULL; p = p->next) {
                    if ((p->pfi_flags & CCND_PFI_SENDUPST) != 0) {
                        pfi_set_expiry_from_micros(h, strategy->ie, p, usec);
                        usec += ccnd_random(h) % amt;
                    }
                }
            }
            break;
        case CCNST_UPDATE:
            /* Just go ahead and send as prompted */
            for (p = strategy->pfl; p!= NULL; p = p->next) {
                if ((p->pfi_flags & CCND_PFI_ATTENTION) != 0) {
                    p->pfi_flags &= ~CCND_PFI_ATTENTION;
                    p->pfi_flags |= CCND_PFI_SENDUPST;
                }
            }
            break;
        case CCNST_TIMER:
            /*
             * Our best choice has not responded in time.
             * Increase the predicted response.
             */
            adjust_predicted_response(h, npe, CCN_NOFACEID);
            adjust_predicted_response(h, parent, CCN_NOFACEID);
            break;
        case CCNST_SATISFIED:
            /* Keep a little history about where matching content comes from. */
            adjust_predicted_response(h, npe, faceid);
            adjust_predicted_response(h, parent, faceid);
            break;
        case CCNST_TIMEOUT:
            /* Interest has not been satisfied or refreshed */
            break;
        case CCNST_INIT:
            break; /* No strategy private data needed */
        case CCNST_EXPUP:
            break;
        case CCNST_EXPDN:
            break;
        case CCNST_REFRESH:
            break;
        case CCNST_FINALIZE:
            break; /* Nothing to clean up */
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
 * faceid is CCN_NOFACEID if no content arrived, or else tells the
 * arrival face.
 */
static void
adjust_predicted_response(struct ccnd_handle *h,
                          struct strategy_state *s, unsigned faceid)
{
    unsigned t = s->usec;
    if (faceid == CCN_NOFACEID) {
        t = t + (t >> 3); /* no content arrived */
        if (t > h->predicted_response_limit)
            t = h->predicted_response_limit;
    }
    else if (faceid == s->src) {
        t = t - (t >> 7); /* content arrived on expected face */
        if (t < 127)
            t = 127;
    }
    s->usec = t;
    if (faceid == CCN_NOFACEID)
        return;
    /* content arrived, so keep track of the arrival face */
    if (s->src == CCN_NOFACEID)
        s->src = faceid;
    else if (s->src != faceid) {
        s->osrc = s->src;
        s->src = faceid;
    }
}

/**
 * A null strategy for testing purposes
 *
 * This does nothing except for servicing CCNST_UPDATE by sending
 * everything to all permitted upstreams.
 */
void
ccnd_null_strategy_impl(struct ccnd_handle *h,
                        struct strategy_instance *instance,
                        struct ccn_strategy *strategy,
                        enum ccn_strategy_op op,
                        unsigned faceid)
{
    struct pit_face_item *p;
    
    if (op == CCNST_UPDATE) {
        /* Just go ahead and send as prompted */
        for (p = strategy->pfl; p!= NULL; p = p->next) {
            if ((p->pfi_flags & CCND_PFI_ATTENTION) != 0) {
                p->pfi_flags &= ~CCND_PFI_ATTENTION;
                p->pfi_flags |= CCND_PFI_SENDUPST;
            }
        }
    }
}


static void
format_pfi(struct ccnd_handle *h, struct pit_face_item *p, struct ccn_charbuf *c)
{
    ccn_charbuf_putf(c, " %s%s%s%s%s%s%u@%u",
                     (p->pfi_flags & CCND_PFI_UPSTREAM) ? "u" :
                      (p->pfi_flags & CCND_PFI_DNSTREAM) ? "d" : "?",
                     (p->pfi_flags & (CCND_PFI_PENDING|CCND_PFI_UPENDING)) ? "p" : "",
                     (p->pfi_flags & CCND_PFI_UPHUNGRY) ? "h" : "",
                     (p->pfi_flags & CCND_PFI_SENDUPST) ? "s" : "",
                     (p->pfi_flags & CCND_PFI_ATTENTION) ? "a" : "",
                     (p->pfi_flags & CCND_PFI_SUPDATA) ? "x" : "",
                     (p->pfi_flags & CCND_PFI_DCFACE) ? "c" : "",
                     (unsigned)p->faceid,
                     (unsigned)(p->expiry - h->wtnow));
}

/* Set this pointer if you want to trace a different strategy. */
strategy_callout_proc ccnd_traced_strategy = &ccnd_default_strategy_impl;

/**
 * A trace strategy for testing purposes
 *
 * Useful for debugging.
 */
void
ccnd_trace_strategy_impl(struct ccnd_handle *h,
                         struct strategy_instance *instance,
                         struct ccn_strategy *strategy,
                         enum ccn_strategy_op op,
                         unsigned faceid)
{
    unsigned serial = 0;
    struct pit_face_item *p = NULL;
    struct ccn_charbuf *c = ccn_charbuf_create();
    
    if (strategy != NULL) {
        serial = strategy->ie->serial;
        ccn_charbuf_reset(c);
        for (p = strategy->pfl; p!= NULL; p = p->next)
            format_pfi(h, p, c);
    }
    /* Call through to the traced strategy. */
    ccnd_traced_strategy(h, instance, strategy, op, faceid);
    if (strategy != NULL) {
        ccn_charbuf_putf(c, " ///");
        for (p = strategy->pfl; p!= NULL; p = p->next)
            format_pfi(h, p, c);
    }
    switch (op) {
        case CCNST_INIT:
            ccnd_msg(h, "trace CCNST_INIT - %#p", (void *)instance);
            break;
        case CCNST_NOP:
            ccnd_msg(h, "trace CCNST_NOP %u %#p,i=%u", faceid,
                     (void *)instance, serial);
            break;
        case CCNST_FIRST:
            ccnd_msg(h, "trace CCNST_FIRST %u %#p,i=%u%s", faceid,
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_UPDATE:
            ccnd_msg(h, "trace CCNST_UPDATE - %#p,i=%u%s",
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_TIMER:
            ccnd_msg(h, "trace CCNST_TIMER %u %#p,i=%u%s", faceid,
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_SATISFIED:
            ccnd_msg(h, "trace CCNST_SATISFIED %u %#p,i=%u%s", faceid,
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_TIMEOUT:
            ccnd_msg(h, "trace CCNST_TIMEOUT %u %#p,i=%u%s", faceid,
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_EXPUP:
            ccnd_msg(h, "trace CCNST_EXPUP %u %#p,i=%u%s", faceid,
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_EXPDN:
            ccnd_msg(h, "trace CCNST_EXPDN %u %#p,i=%u%s", faceid,
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_REFRESH:
            ccnd_msg(h, "trace CCNST_REFRESH %u %#p,i=%u%s", faceid,
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_FINALIZE:
            ccnd_msg(h, "trace CCNST_FINALIZE %#p", (void *)instance);
            break;
    }
    ccn_charbuf_destroy(&c);
}
