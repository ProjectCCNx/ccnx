/*
 * @file ccnd/trace_strategy.c
 *
 * Part of ccnd - the CCNx Daemon
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
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

#include <ccn/charbuf.h>
#include "ccnd_strategy.h"
#include "ccnd_private.h"
#include "ccnd_stregistry.h"

/**
 * Append a human-readable rendition of the information in a pit face item
 */
static void
format_pfi(struct ccnd_handle *h, struct pit_face_item *p, struct ccn_charbuf *c)
{
    struct face *face;
    unsigned delta;
    
    face = ccnd_face_from_faceid(h, p->faceid);
    ccn_charbuf_putf(c, " %s%s%s%s%s%s%s%u",
                     ((p->pfi_flags & CCND_PFI_UPSTREAM) ? "u" :
                      (p->pfi_flags & CCND_PFI_DNSTREAM) ? "d" : "?"),
                     (p->pfi_flags & (CCND_PFI_PENDING|CCND_PFI_UPENDING)) ? "p" : "",
                     (p->pfi_flags & CCND_PFI_UPHUNGRY) ? "h" : "",
                     (p->pfi_flags & CCND_PFI_SENDUPST) ? "s" : "",
                     (p->pfi_flags & CCND_PFI_ATTENTION) ? "a" : "",
                     (p->pfi_flags & CCND_PFI_SUPDATA) ? "x" : "",
                     (p->pfi_flags & CCND_PFI_DCFACE) ? "c" : "",
                     (unsigned)p->faceid);
    if (face != NULL) {
        if ((p->pfi_flags & CCND_PFI_DNSTREAM) != 0)
            ccn_charbuf_putf(c, "-%d", (int)face->pending_interests);
        else
            ccn_charbuf_putf(c, "+%d", (int)face->outstanding_interests);
    }
    delta = p->expiry - h->wtnow; /* uses wrapping arithmetic */
    if (delta <= 0xffffff)
        ccn_charbuf_putf(c, "@%u", delta);
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
