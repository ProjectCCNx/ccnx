/**
 * @file ccnr_main.c
 * 
 * Part of ccnr -  CCNx Repository Daemon.
 *
 */

/*
 * Copyright (C) 2009-2013 Palo Alto Research Center, Inc.
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
 
#include <signal.h>
#include <stddef.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

#include "ccnr_private.h"

#include "ccnr_init.h"
#include "ccnr_dispatch.h"
#include "ccnr_msg.h"
#include "ccnr_stats.h"

static int
stdiologger(void *loggerdata, const char *format, va_list ap)
{
    FILE *fp = (FILE *)loggerdata;
    return(vfprintf(fp, format, ap));
}

static struct ccnr_handle *global_h = NULL;

static void
handle_signal(int sig)
{
    if (global_h != NULL)
        global_h->running = 0;
    signal(sig, SIG_DFL);
}
/**
 * CCNR Usage message
 */
static const char *ccnr_usage_message =
"ccnr - CCNx Repository Daemon\n"
"  options: none\n"
"  arguments: none\n"
"  configuration (via $CCNR_DIRECTORY/config or environment):\n"
"    CCNR_DEBUG=WARNING\n"
"      Debug logging level:\n"
"      NONE - no messages\n"
"      SEVERE - severe, probably fatal, errors\n"
"      ERROR - errors\n"
"      WARNING - warnings\n"
"      INFO - informational messages\n"
"      FINE, FINER, FINEST - debugging/tracing\n"
"    CCNR_DIRECTORY=.\n"
"      Directory where ccnr data is kept\n"
"      Defaults to current directory\n"
"      Ignored in config file\n"
"    CCNR_GLOBAL_PREFIX=ccnx:/parc.com/csl/ccn/Repos\n"
"      CCNx URI representing the prefix where data/policy.xml is stored.\n"
"      Only meaningful if no policy file exists at startup.\n"
"    CCNR_START_WRITE_SCOPE_LIMIT=3\n"
"      0..3 (default 3) Process start-write(-checked) interests with a scope\n"
"      not exceeding the given value.  0 is effectively read-only. 3 indicates unlimited.\n"
"    CCNR_BTREE_MAX_FANOUT=1999\n"
"      4..9999 (default 1999) Maximum number of entries within a node.\n"
"    CCNR_BTREE_MAX_LEAF_ENTRIES=1999\n"
"      4..9999 (default 1999) Maximum number of entries within a node at level 0.\n"
"    CCNR_BTREE_MAX_NODE_BYTES=2097152\n"
"      1024..8388608 (default 2097152) Maximum node size (bytes).\n"
"    CCNR_BTREE_NODE_POOL=512\n"
"      16..2000000 (default 512) Maximum number of btree nodes in memory.\n"
"    CCNR_CONTENT_CACHE=4201\n"
"      16..2000000 (default 4201) Maximum number of ContentObjects cached in memory.\n"
"    CCNR_MIN_SEND_BUFSIZE=16384\n"
"      Minimum in bytes for output socket buffering.\n"
"    CCNR_PROTO=unix\n"
"      Specify 'tcp' to connect to ccnd using tcp instead of unix ipc.\n"
"    CCNR_LISTEN_ON=\n"
"      List of ip addresses to listen on for status; defaults to localhost addresses.\n"
"    CCNR_STATUS_PORT=\n"
"      Port to use for status server; default is to not serve status.\n"
"    CCNS_DEBUG=WARNING\n"
"      Same values as for CCNR_DEBUG.\n"
"    CCNS_ENABLE=1\n"
"      Disable (0) or enable (1, default) Sync processing.\n"
"    CCNS_REPO_STORE=1\n"
"      Disable (0) or enable (1, default) storing Sync state in repository.\n"
"    CCNS_STABLE_ENABLED=1\n"
"      Disable (0) or enable (1, default) storing Sync stable-points to repository.\n"
"    CCNS_FAUX_ERROR=0\n"
"      Disable (0, default) or enable (1-99) percent simulated random packet loss.\n"
"    CCNS_HEARTBEAT_MICROS=200000\n"
"      100000..10000000 (default 200000) microseconds between Sync heartbeats.\n"
"    CCNS_ROOT_ADVISE_FRESH=4\n"
"      1..30 (default 4) freshness (seconds) for Sync root advise response.\n"
"    CCNS_ROOT_ADVISE_LIFETIME=20\n"
"      1..30 (default 20) lifetime (seconds) for Sync root advise response.\n"
"    CCNS_NODE_FETCH_LIFETIME=4\n"
"      1..30 (default 4) lifetime (seconds) for Sync node fetch response.\n"
"    CCNS_MAX_FETCH_BUSY=6\n"
"      1..100 (default 6) maximum simultaneous node or content fetches per Sync root.\n"
"    CCNS_MAX_COMPARES_BUSY=4\n"
"      1..100 (default 4) maximum simultaneous Sync roots in compare state.\n"
"    CCNS_NOTE_ERR=0\n"
"      Disable (0, default) or enable (1) exceptional Sync error reporting.\n"
"    CCNS_SYNC_SCOPE=2\n"
"      The default (2) restricts sync traffic to directly connected peers,\n"
"      which requires sync to be running on all nodes.  Set to 3 to permit\n"
"      forwarding of sync traffic.\n"
;

int
main(int argc, char **argv)
{
    int s;
    
    if (argc > 1) {
        fprintf(stderr, "%s", ccnr_usage_message);
        exit(1);
    }
    signal(SIGPIPE, SIG_IGN);
    global_h = r_init_create(argv[0], stdiologger, stderr);
    if (global_h == NULL)
        exit(1);
    signal(SIGINT, &handle_signal);
    signal(SIGTERM, &handle_signal);
    signal(SIGXFSZ, &handle_signal);
    r_dispatch_run(global_h);
    s = (global_h->running != 0);
    ccnr_msg(global_h, "exiting.");
    r_init_destroy(&global_h);
    exit(s);
}
