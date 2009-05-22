/*
 * ccn_versioning.c
 * Copyright (C) 2009 Palo Alto Research Center, Inc. All rights reserved.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

/*
 * name is a ccnb-encoded Name prefix. It gets extended with the highest extant
 * version that can be found without exceeding the specified timeout between
 * fetches.
 * The ccn handle h may be NULL if desired.
 * Returns -1 for error, 0 if name could not be extended, 1 if it could be.
 */

int
ccn_resolve_highest_version(struct ccn *h, struct ccn_charbuf *name, int timeout_ms)
{
    int res;
    int myres = -1;
    struct ccn_parsed_ContentObject pco_space = { 0 };
    struct ccn_charbuf *templ = NULL;
    struct ccn_charbuf *result = ccn_charbuf_create();
    struct ccn_parsed_ContentObject *pco = &pco_space;
    struct ccn_indexbuf *ndx = ccn_indexbuf_create();
    const unsigned char *vers = NULL;
    size_t vers_size = 0;
    int i;
    int c0;
    int nl = name->length;
    int n = 
    if (nl < 2)
        return(-1);
    result->length = 0;
    res = ccn_get(h, name, -1, templ, timeout_ms,
                  result, pco, ndx);
    while (result->length != 0) {
        nco = pco->offset[CCN_PCO_B_Name] + nl - 1;
        res = ccn_ref_tagged_BLOB(CCN_DTAG_Component, result->buf, nco, pco->offset[CCN_PCO_E_Name], vers, vers_size);
        if (res < 0) break;
        if (3 <= vers_size && vers_size <= 8 && vers[0] == CCN_MARKER_VERSION) {
            /* Looks like we have versions. */
            name->buf[nl - 1] = 0;
            name->length = nl;
            res = ccn_name_append(name, vers, vers_size);
            
        }
        
        c0 = ndx->buf[0];
        for (i = ndx->n - 2; i >= 0; i--) {
            ci = ndx->buf[i];
            if (ci - c0 == nl - 2) {
                if (result->buf[ci + 3] == CCN_MARKER_VERSION)
                break;
            }
        }
    }
    
    if (res < 0)
        return(-1);
    return(myres);
}
