/*
 * ccn_reg_mgmt.c
 *  
 * Copyright (C) 2009 Palo Alto Research Center, Inc. All rights reserved.
 */

#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/reg_mgmt.h>

struct ccn_forwarding_entry *
ccn_forwarding_entry_parse(const unsigned char *p, size_t size)
{
    return(NULL);
}

/**
 * Destroy the result of ccn_forwarding_entry_parse().
 */
void
ccn_forwarding_entry_destroy(struct ccn_forwarding_entry **pfe)
{
    if (*pfe == NULL)
        return;
    free(*pfe);
    *pfe = NULL;
}

int
ccnb_append_forwarding_entry(struct ccn_charbuf *c,
                             const struct ccn_forwarding_entry *fe)
{
    return -1;
}
