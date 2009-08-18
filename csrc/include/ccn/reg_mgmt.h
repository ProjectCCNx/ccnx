/*
 * reg_mgmt.h
 * Copyright (C) 2009 Palo Alto Research Center, Inc. All rights reserved.
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

#define CCN_FORW_ACTIVE         1
#define CCN_FORW_CHILD_INHERIT  2
#define CCN_FORW_ADVERTISE      4

struct ccn_forwarding_entry *
ccn_forwarding_entry_parse(const unsigned char *p, size_t size);

void ccn_forwarding_entry_destroy(struct ccn_forwarding_entry**);

int ccnb_append_forwarding_entry(struct ccn_charbuf *,
                                 const struct ccn_forwarding_entry*);


#endif
