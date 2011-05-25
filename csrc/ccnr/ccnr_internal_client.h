/**
 * @file ccnr_internal_client.h
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
 
#include "ccnr_private.h"

void ccnr_internal_client_stop(struct ccnr_handle *ccnr);
int ccnr_internal_client_start(struct ccnr_handle *ccnr);
void ccnr_face_status_change(struct ccnr_handle *ccnr,unsigned filedesc);
int ccnr_init_internal_keystore(struct ccnr_handle *ccnr);
