/**
 * @file ccnr_init.h
 * 
 * Part of ccnr - CCNx Repository Daemon.
 *
 */

/*
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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
 
#ifndef CCNR_INIT_DEFINED
#define CCNR_INIT_DEFINED

#include "ccnr_private.h"

struct ccnr_parsed_policy *ccnr_parsed_policy_create(void);
void ccnr_parsed_policy_destroy(struct ccnr_parsed_policy **ppp);
struct ccnr_handle *r_init_create(const char *progname,ccnr_logger logger,void *loggerdata);
void r_init_fail(struct ccnr_handle *, int, const char *, int);
void r_init_destroy(struct ccnr_handle **pccnr);
int r_init_map_and_process_file(struct ccnr_handle *h, struct ccn_charbuf *filename, int add_content);
struct ccn_charbuf *ccnr_init_policy_link_cob(struct ccnr_handle *ccnr, struct ccn *h, struct ccn_charbuf *targetname);
intmax_t r_init_confval(struct ccnr_handle *h, const char *key,
                        intmax_t lo, intmax_t hi, intmax_t deflt);
#endif
