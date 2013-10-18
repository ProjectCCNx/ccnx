/*
 * @file ccnd/faceattr_strategy.c
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
 * A non-strategy for testing purposes
 *
 * This hack provides a way of setting
 * face attributes from the outside.
 * It should go away when an actual protocol
 * for changing face attributes is defined
 * and implemented.
 */
void
ccnd_faceattr_strategy_impl(struct ccnd_handle *h,
    struct strategy_instance *instance,
    struct ccn_strategy *strategy,
    enum ccn_strategy_op op,
    unsigned faceid)
{
    if (op == CCNST_INIT) {
        strategy_init_error(h, instance, "Sorry, Charlie");
    }
    else if (op == CCNST_FINALIZE) {
        ccnd_msg(h, "ccnd_faceattr_strategy_impl CCNST_INIT");
    }
}
