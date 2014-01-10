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

#include <stdlib.h>
#include <string.h>
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
    ccn_charbuf_putf(c, " %s%s%s%s%s%s%s%s%u",
                     ((p->pfi_flags & CCND_PFI_UPSTREAM) ? "u" :
                      (p->pfi_flags & CCND_PFI_DNSTREAM) ? "d" : "?"),
                     (p->pfi_flags & (CCND_PFI_PENDING|CCND_PFI_UPENDING)) ? "p" : "",
                     (p->pfi_flags & CCND_PFI_UPHUNGRY) ? "h" : "",
                     (p->pfi_flags & CCND_PFI_SENDUPST) ? "s" : "",
                     (p->pfi_flags & CCND_PFI_ATTENTION) ? "a" : "",
                     (p->pfi_flags & CCND_PFI_INACTIVE) ? "q" : "",
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
    struct strategy_instance *inner = NULL;
    struct ccn_charbuf *c = ccn_charbuf_create();
    const char *sp = NULL;
    
    sp = instance->parameters;
    if (sp == NULL || sp[0] == 0)
        sp = "default";
    if (strategy != NULL) {
        serial = strategy->ie->serial;
        ccn_charbuf_reset(c);
        for (p = strategy->pfl; p!= NULL; p = p->next)
            format_pfi(h, p, c);
    }
    /* Call through to the traced strategy. */
    if (op == CCNST_INIT) {
        /*
         * The first portion of the parameter string (before the first slash)
         * is the name of the traced strategy.  The remainder (after this slash)
         * forms its parameter string.
         */
        char tname[16];
        const char *s = NULL;
        const struct strategy_class *sclass = NULL;
        
        s = strstr(sp, "/");
        if (s == NULL)
            s = sp + strlen(sp);
        if (s - sp >= sizeof(tname)) {
            strategy_init_error(h, instance, "traced strategy name too long");
            ccn_charbuf_destroy(&c);
            return;
        }
        memcpy(tname, sp, s - sp);
        tname[s - sp] = 0;
        if (s[0] == '/')
            s++;
        sclass = strategy_class_from_id(tname);
        if (sclass == NULL) {
            strategy_init_error(h, instance, "traced strategy name unknown");
            ccn_charbuf_destroy(&c);
            return;
        }
        inner = calloc(1, sizeof(*inner));
        inner->sclass = sclass;
        inner->parameters = s;
        inner->data = NULL;
        inner->npe = instance->npe;
        instance->data = inner;
        (sclass->callout)(h, inner, strategy, op, faceid);
    }
    else if (op == CCNST_FINALIZE) {
        inner = instance->data;
        if (inner != NULL) {
            (inner->sclass->callout)(h, inner, strategy, op, faceid);
            if (inner->data != NULL) abort();
            free(inner);
            instance->data = inner = NULL;
        }
    }
    else {
        /* Call through to the traced strategy. */
        inner = instance->data;
        (inner->sclass->callout)(h, inner, strategy, op, faceid);
    }
    if (strategy != NULL) {
        ccn_charbuf_putf(c, " ///");
        for (p = strategy->pfl; p!= NULL; p = p->next)
            format_pfi(h, p, c);
    }
    switch (op) {
        case CCNST_INIT:
            ccnd_msg(h, "st-%s CCNST_INIT - %#p", sp, (void *)instance);
            break;
        case CCNST_NOP:
            ccnd_msg(h, "st-%s CCNST_NOP %u %#p,i=%u", sp, faceid,
                     (void *)instance, serial);
            break;
        case CCNST_FIRST:
            ccnd_msg(h, "st-%s CCNST_FIRST %u %#p,i=%u%s", sp, faceid,
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_UPDATE:
            ccnd_msg(h, "st-%s CCNST_UPDATE - %#p,i=%u%s", sp,
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_TIMER:
            ccnd_msg(h, "st-%s CCNST_TIMER %u %#p,i=%u%s", sp, faceid,
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_SATISFIED:
            ccnd_msg(h, "st-%s CCNST_SATISFIED %u %#p,i=%u%s", sp, faceid,
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_TIMEOUT:
            ccnd_msg(h, "st-%s CCNST_TIMEOUT %u %#p,i=%u%s", sp, faceid,
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_EXPUP:
            ccnd_msg(h, "st-%s CCNST_EXPUP %u %#p,i=%u%s", sp, faceid,
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_EXPDN:
            ccnd_msg(h, "st-%s CCNST_EXPDN %u %#p,i=%u%s", sp, faceid,
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_REFRESH:
            ccnd_msg(h, "st-%s CCNST_REFRESH %u %#p,i=%u%s", sp, faceid,
                     (void *)instance, serial, ccn_charbuf_as_string(c));
            break;
        case CCNST_FINALIZE:
            ccnd_msg(h, "st-%s CCNST_FINALIZE %#p", sp, (void *)instance);
            break;
    }
    ccn_charbuf_destroy(&c);
}
