/*
 * face_mgmt.h
 * Copyright (C) 2009 Palo Alto Research Center, Inc. All rights reserved.
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
