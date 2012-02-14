/**
 * @file ccnr_stats.h
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
 
#ifndef CCNR_STATS_DEFINED
#define CCNR_STATS_DEFINED

#include "ccnr_private.h"

void ccnr_meter_bump(struct ccnr_handle *h,struct ccnr_meter *m,unsigned amt);
void ccnr_meter_destroy(struct ccnr_meter **pm);
void ccnr_meter_init(struct ccnr_handle *h,struct ccnr_meter *m,const char *what);
struct ccnr_meter *ccnr_meter_create(struct ccnr_handle *h,const char *what);
uintmax_t ccnr_meter_total(struct ccnr_meter *m);
unsigned ccnr_meter_rate(struct ccnr_handle *h,struct ccnr_meter *m);
int ccnr_stats_handle_http_connection(struct ccnr_handle *h,struct fdholder *fdholder);

#endif
