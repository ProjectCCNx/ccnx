/**
 * @file ccnr_msg.h
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
 
#ifndef CCNR_MSG_DEFINED
#define CCNR_MSG_DEFINED

#include "ccnr_private.h"

extern const char *ccnr_usage_message;
void ccnr_debug_ccnb(struct ccnr_handle *h,int lineno,const char *msg,struct fdholder *fdholder,const unsigned char *ccnb,size_t ccnb_size);
void ccnr_msg(struct ccnr_handle *h,const char *fmt,...);

#endif
