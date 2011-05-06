/**
 * @file reg_mgmt.h
 *
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009-2011 Palo Alto Research Center, Inc.
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

#ifndef CCN_REG_MGMT_DEFINED
#define CCN_REG_MGMT_DEFINED

#include <stddef.h>
#include <ccn/charbuf.h>

struct ccn_forwarding_entry {
    const char *action;
    struct ccn_charbuf *name_prefix;
    const unsigned char *ccnd_id;
    size_t ccnd_id_size;
    unsigned faceid;
    int flags;
    int lifetime;
    unsigned char store[48];
};

/** Refer to doc/technical/Registration.txt for the meaning of these flags */
#define CCN_FORW_ACTIVE         1
#define CCN_FORW_CHILD_INHERIT  2
#define CCN_FORW_ADVERTISE      4
#define CCN_FORW_LAST           8
#define CCN_FORW_CAPTURE       16
#define CCN_FORW_LOCAL         32
#define CCN_FORW_TAP           64
#define CCN_FORW_CAPTURE_OK   128
#define CCN_FORW_PUBMASK (CCN_FORW_ACTIVE        | \
                          CCN_FORW_CHILD_INHERIT | \
                          CCN_FORW_ADVERTISE     | \
                          CCN_FORW_LAST          | \
                          CCN_FORW_CAPTURE       | \
                          CCN_FORW_LOCAL         | \
                          CCN_FORW_TAP           | \
                          CCN_FORW_CAPTURE_OK    )

struct ccn_forwarding_entry *
ccn_forwarding_entry_parse(const unsigned char *p, size_t size);

void ccn_forwarding_entry_destroy(struct ccn_forwarding_entry**);

int ccnb_append_forwarding_entry(struct ccn_charbuf *,
                                 const struct ccn_forwarding_entry*);


#endif
