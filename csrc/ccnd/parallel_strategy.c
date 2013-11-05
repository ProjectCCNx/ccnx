/*
 * @file ccnd/parallel_strategy.c
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

#include "ccnd_strategy.h"

/**
 * This implements a strategy which sends an interest in parallel to all
 * eligible active upstream faces.  This is expected to result in better performance
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
    struct pit_face_item *p;
    int all_inactive = 1;
    
    /* expiry times do not need to be adjusted if we want things sent "now" */
    if (op == CCNST_UPDATE) {
        for (p = strategy->pfl; p!= NULL; p = p->next) {
            if ((p->pfi_flags & CCND_PFI_ATTENTION) != 0) {
                if ((p->pfi_flags & CCND_PFI_INACTIVE) == 0) {
                    all_inactive = 0;
                    break;
                }
            }
        }
        /* Just go ahead and send as prompted, unless the face is inactive
         * except if all the faces are inactive.  Also probe an inactive face
         * with low but non-zero probability
         */
        for (p = strategy->pfl; p!= NULL; p = p->next) {
            if ((p->pfi_flags & CCND_PFI_ATTENTION) != 0) {
                p->pfi_flags &= ~CCND_PFI_ATTENTION;
                if (all_inactive || (p->pfi_flags & CCND_PFI_INACTIVE) == 0 ||
                    (ccnd_random(h) & 31) == 0)
                    p->pfi_flags |= CCND_PFI_SENDUPST;
            }
        }
    }
}

