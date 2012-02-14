/**
 * @file ccn/face_mgmt.h
 *
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009 Palo Alto Research Center, Inc.
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

#ifndef CCN_FACE_MGMT_DEFINED
#define CCN_FACE_MGMT_DEFINED

#include <stddef.h>
#include <ccn/charbuf.h>
#include <ccn/sockcreate.h>

#define CCN_NO_FACEID (~0U)

struct ccn_face_instance {
    const char *action;
    const unsigned char *ccnd_id;
    size_t ccnd_id_size;
    unsigned faceid;
    struct ccn_sockdescr descr;
    int lifetime;
    struct ccn_charbuf *store;
};

struct ccn_face_instance *ccn_face_instance_parse(const unsigned char *p,
                                                  size_t size);

void ccn_face_instance_destroy(struct ccn_face_instance**);

int ccnb_append_face_instance(struct ccn_charbuf *,
                              const struct ccn_face_instance *);

#endif
