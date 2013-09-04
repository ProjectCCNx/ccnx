/*
 * @file ccnd/ccnd_strategy1.c
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

/**
 * This implements a strategy which sends an interest in parallel to all
 * eligible upstream faces.  This is expected to result in better performance
 * when there are multiple independent sources at the expense of increased
 * network traffic.
 */
void
ccnd_parallel_strategy_impl(struct ccnd_handle *h,
                  struct strategy_instance *instance,
                  struct ccn_strategy *strategy,
                  enum ccn_strategy_op op,
                  unsigned faceid)
{
    struct pit_face_item *x = NULL;
    struct pit_face_item *p = NULL;

    switch (op) {
        case CCNST_NOP:
            break;
        case CCNST_INIT:
            break; /* No strategy private data needed */
        case CCNST_FIRST:
            /* clear any default timing information */
            for (x = strategy->pfl; x != NULL; x = x->next)
                x->expiry = 0;
            /* Find our downstream; right now there should be just one. */
            for (x = strategy->pfl; x != NULL; x = x->next)
                if ((x->pfi_flags & CCND_PFI_DNSTREAM) != 0)
                    break;
            if (x == NULL || (x->pfi_flags & CCND_PFI_PENDING) == 0)
                return;

            for (p = strategy->pfl; p!= NULL; p = p->next) {
                if ((p->pfi_flags & CCND_PFI_UPSTREAM) != 0) {
                        /* we may have already sent in case of TAP */
                        if ((p->pfi_flags & CCND_PFI_UPENDING) == 0)
                            p = send_interest(h, strategy->ie, x, p);
                }
            }
            break;
        case CCNST_NEWUP:
            break;
        case CCNST_NEWDN:
            break;
        case CCNST_EXPUP:
            break;
        case CCNST_EXPDN:
            break;
        case CCNST_REFRESH:
            break;
        case CCNST_TIMER:
            break;
        case CCNST_SATISFIED:
            break;
        case CCNST_TIMEOUT:
            /* Interest has not been satisfied or refreshed */
            break;
        case CCNST_FINALIZE:
            break; /* Nothing to clean up */
    }
}
