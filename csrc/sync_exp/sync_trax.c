/**
 * @file sync_trax.c
 * 
 * A CCNx program.
 */
/* Copyright (C) 2012 Palo Alto Research Center, Inc.
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
 
#include <errno.h>
#include <stdarg.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/types.h>
#include <sys/time.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/ccn_private.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

#include "SyncMacros.h"
#include "SyncBase.h"
#include "SyncNode.h"
#include "SyncPrivate.h"
#include "SyncUtil.h"
#include "sync.h"
#include "sync_depends.h"
#include "sync_diff.h"

// types

struct parms {
    struct ccn_charbuf *topo;
    struct ccn_charbuf *prefix;
    int debug;
    struct ccn *ccn;
    int64_t startTime;
    int64_t timeLimit;
};


static int
noteErr2(const char *why, const char *msg) {
    fprintf(stderr, "** ERROR: %s, %s\n", why, msg);
    fflush(stderr);
    return -1;
}

int
my_note_name(struct sync_name_closure *nc,
             struct ccn_charbuf *lhash,
             struct ccn_charbuf *rhash,
             struct ccn_charbuf *pname) {
    if (pname != NULL) {
        struct ccn_charbuf *uri = SyncUriForName(pname);
        char *str = ccn_charbuf_as_string(uri);
        nc->count++;
        printf("sync_trax, %ju, adding %s\n", (uintmax_t) nc->count, str);
        ccn_charbuf_destroy(&uri);
    }
    return 0;
}

int
doTest(struct parms *p) {
    char *here = "sync_trax.doTest";
    int res = 0;
    p->startTime = SyncCurrentTime();
    if (ccn_connect(p->ccn, NULL) == -1) {
        return noteErr2(here, "could not connect to ccnd");
    }
    
    struct ccns_slice *slice = ccns_slice_create();
    ccns_slice_set_topo_prefix(slice, p->topo, p->prefix);
    
    struct sync_name_closure *nc = calloc(1, sizeof(*nc));
    nc->note_name = my_note_name;
    struct ccns_handle *ch = ccns_open(p->ccn, slice, nc, NULL, NULL);
    
    for (;;) {
        int64_t now = SyncCurrentTime();
        int64_t dt = SyncDeltaTime(p->startTime, now);
        if (dt > p->timeLimit) break;
        ccn_run(p->ccn, 1000);
    }
    
    ccns_close(&ch, NULL, NULL);
    ccns_slice_destroy(&slice);
    
    return res;
}

int
main(int argc, char **argv) {
    int i = 1;
    int res = 0;
    int seen = 0;
    struct parms ps;
    struct parms *p = &ps;
    
    memset(p, 0, sizeof(*p));
    
    p->topo = ccn_charbuf_create();
    p->prefix = ccn_charbuf_create();
    p->debug = 0;
    p->timeLimit = 60*1000000; // default is one minute (kinda arbitrary)
    
    while (i < argc && res >= 0) {
        char * sw = argv[i];
        i++;
        char *arg1 = NULL;
        char *arg2 = NULL;
        if (i < argc) arg1 = argv[i];
        if (i+1 < argc) arg2 = argv[i+1];
        if (strcasecmp(sw, "-debug") == 0 || strcasecmp(sw, "-d") == 0) {
            i++;
            p->debug = atoi(arg1);
        } else if (strcasecmp(sw, "-topo") == 0) {
            if (arg1 != NULL) {
                p->topo->length = 0;
                ccn_name_from_uri(p->topo, arg1);
                i++;
                seen++;
            }
        } else if (strcasecmp(sw, "-prefix") == 0) {
            if (arg1 != NULL) {
                p->prefix->length = 0;
                ccn_name_from_uri(p->prefix, arg1);
                i++;
                seen++;
            }
        } else if (strcasecmp(sw, "-secs") == 0) {
            if (arg1 != NULL) {
                int64_t secs = atoi(arg1);
                p->timeLimit = secs * 1000000;
                i++;
            }
        } else {
            noteErr2("invalid switch: ", sw);
            seen = 0;
            break;
        }
    }
    
    if (seen) {
        p->ccn = ccn_create();
        doTest(p);
        if (p->ccn != NULL) {
            ccn_disconnect(p->ccn);
            ccn_destroy(&p->ccn);
        }
    }
	return(0);
}