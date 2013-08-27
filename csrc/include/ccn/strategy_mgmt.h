/**
 * @file ccn/strategy_mgmt.h
 *
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

#ifndef CCN_STRATEGY_MGMT_DEFINED
#define CCN_STRATEGY_MGMT_DEFINED

#include <stddef.h>
#include <ccn/charbuf.h>
#include <ccn/sockcreate.h>

struct ccn_strategy_selection {
    const char *action;
    const unsigned char *ccnd_id;
    size_t ccnd_id_size;
    struct ccn_charbuf *name_prefix;
    const char *strategyid;
    const char *parameters;
    int lifetime;
    struct ccn_charbuf *store;
};

struct ccn_strategy_selection *
ccn_strategy_selection_parse(const unsigned char *p, size_t size);

int ccnb_append_strategy_selection(struct ccn_charbuf *c,
                                   const struct ccn_strategy_selection *ss);

void ccn_strategy_selection_destroy(struct ccn_strategy_selection **pss);

#endif
