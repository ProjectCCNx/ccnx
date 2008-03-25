/*
 * ccn/ccn.h
 * 
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 *
 * This is the low-level interface for CCN clients
 *
 * $Id$
 */

#ifndef CCN_CCN_DEFINED
#define CCN_CCN_DEFINED

#include <ccn/coding.h>

struct ccn;

struct ccn *
ccn_create(void);

int
ccn_connect(struct ccn *h, const char *name);

int
ccn_disconnect(struct ccn *h);

void
ccn_destroy(struct ccn **h);

#endif
