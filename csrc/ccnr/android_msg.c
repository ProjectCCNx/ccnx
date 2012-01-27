/**
 * Copyright (C) 2009-2012 Palo Alto Research Center, Inc.
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

/**
 *
 * Logging support for ccnr, for Android platform
 *
 *
 */

#include <stdio.h>
#include <sys/time.h>
#include <stdarg.h>
#include <time.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/ccnr.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

#include "ccnr_private.h"

#include <android/log.h>

/**
 *  Produce ccnr debug output to the Android log.
 *  Output is produced on stderr under the control of h->debug;
 *  prepends decimal timestamp and process identification.
 *  Caller should not supply newlines.
 *  @param      h  the ccnr handle
 *  @param      fmt  printf-like format string
 */
void
ccnr_msg(struct ccnr_handle *h, const char *fmt, ...)
{
    struct timeval t;
    va_list ap;
    struct ccn_charbuf *b;

    if (h != NULL && h->debug == 0)
        return;

    b = ccn_charbuf_create();

    gettimeofday(&t, NULL);
    ccn_charbuf_putf(b, "%ld.%06u ccnr[%d]: %s\n",
        (long)t.tv_sec, (unsigned)t.tv_usec, (int)getpid(), fmt);
    va_start(ap, fmt);

	__android_log_vprint(ANDROID_LOG_INFO, "CCNR", (const char *)b->buf, ap);
    va_end(ap);
    ccn_charbuf_destroy(&b);
}

/**
 * Print the ccnr usage message on stderr.
 *
 * Does not exit.
 */
void
ccnr_usage(void)
{
    static const char ccnr_usage_message[] =
    "ccnr - CCNx Repository Daemon\n"
    "  options: none\n"
    "  arguments: none\n"
    "  environment variables:\n"
  	"configuration (via $CCNR_DIRECTORY/config or environment):\n"
    "CCNR_DEBUG=WARNING\n"
    " Debug logging level:\n"
    " NONE - no messages\n"
    " SEVERE - severe, probably fatal, errors\n"
    " ERROR - errors\n"
    " WARNING - warnings\n"
    " FINE, FINER, FINEST - debugging/tracing\n"
    "CCNR_DIRECTORY=.\n"
    " Directory where ccnr data is kept\n"
    " Defaults to current directory\n"
    " Ignored in config file\n"
    "CCNR_GLOBAL_PREFIX=ccnx:/parc.com/csl/ccn/Repos\n"
    " CCNx URI representing the prefix where data/policy.xml is stored.\n"
    " Only meaningful if no policy file exists at startup.\n"
    "CCNR_BTREE_MAX_FANOUT=1999\n"
    "CCNR_BTREE_MAX_LEAF_ENTRIES=1999\n"
    "CCNR_BTREE_MAX_NODE_BYTES=2097152\n"
    "CCNR_BTREE_NODE_POOL=512\n"
    "CCNR_CONTENT_CACHE=4201\n"
    " Maximum number of Content Objects cached in memory."
    "CCNR_MIN_SEND_BUFSIZE=16384\n"
    " Minimum in bytes for output socket buffering.\n"
    "CCNR_PROTO=unix\n"
    " Specify 'tcp' to connect to ccnd using tcp instead of unix ipc\n"
    "CCNR_LISTEN_ON=\n"
    " List of ip addresses to listen on for status; defaults to wildcard\n"
    "CCNR_STATUS_PORT=\n"
    " Port to use for status server; default is to not serve status.\n"
    "SYNC_DEBUG=WARNING\n"
    " Same values as for CCNR_DEBUG\n"
    "SYNC_ENABLE=1\n"
    " Disable (0) or enable (1) Sync processing\n"
    "SYNC_TOPO=\n"
    " Specify default topo prefix for Sync protocol\n"
    " (TEMPORARY - will not be in the final release)\n"
    "SYNC_AUTO_REGISTER=\n"
    " Disable (0) or enable (1) root auto-registration, default enabled\n"
    " (TEMPORARY - will not be in the final release)\n";
    fprintf(stderr, ccnr_usage_message, CCN_DEFAULT_UNICAST_PORT);
}

