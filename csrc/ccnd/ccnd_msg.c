/**
 * @file ccnd_msg.c
 *
 * Logging support for ccnd.
 *
 * Part of ccnd - the CCNx Daemon.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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

#include <stdio.h>
#include <sys/time.h>
#include <stdarg.h>
#include <time.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/ccnd.h>
#include <ccn/charbuf.h>
#include <ccn/hashtb.h>
#include <ccn/uri.h>

#include "ccnd_private.h"

/**
 *  Produce ccnd debug output.
 *  Output is produced via h->logger under the control of h->debug;
 *  prepends decimal timestamp and process identification.
 *  Caller should not supply newlines.
 *  @param      h  the ccnd handle
 *  @param      fmt  printf-like format string
 */
void
ccnd_msg(struct ccnd_handle *h, const char *fmt, ...)
{
    struct timeval t;
    va_list ap;
    struct ccn_charbuf *b;
    int res;
    time_t clock;
    if (h == NULL || h->debug == 0 || h->logger == 0)
        return;
    b = ccn_charbuf_create();
    gettimeofday(&t, NULL);
    if (((h->debug & 64) != 0) &&
        ((h->logbreak-- < 0 && t.tv_sec != h->logtime) ||
          t.tv_sec >= h->logtime + 30)) {
        clock = t.tv_sec;
        ccn_charbuf_putf(b, "%ld.000000 ccnd[%d]: %s ____________________ %s",
                         (long)t.tv_sec, h->logpid,
                         h->portstr ? h->portstr : "",
                         ctime(&clock));
        h->logtime = t.tv_sec;
        h->logbreak = 30;
    }
    ccn_charbuf_putf(b, "%ld.%06u ", (long)t.tv_sec, (unsigned)t.tv_usec);
    if (h->debug & 32)
        ccn_charbuf_putf(b, "%08x.", (unsigned)h->wtnow);
    ccn_charbuf_putf(b, "ccnd[%d]: %s\n", h->logpid, fmt);
    va_start(ap, fmt);
    res = (*h->logger)(h->loggerdata, (const char *)b->buf, ap);
    va_end(ap);
    ccn_charbuf_destroy(&b);
    /* if there's no one to hear, don't make a sound */
    if (res < 0)
        h->debug = 0;
}

/**
 *  Produce a ccnd debug trace entry.
 *  Output is produced by calling ccnd_msg.
 *  @param      h  the ccnd handle
 *  @param      lineno  caller's source line number (usually __LINE__)
 *  @param      msg  a short text tag to identify the entry
 *  @param      face    handle of associated face; may be NULL
 *  @param      ccnb    points to ccnb-encoded Interest or ContentObject
 *  @param      ccnb_size   is in bytes
 */
void
ccnd_debug_ccnb(struct ccnd_handle *h,
                int lineno,
                const char *msg,
                struct face *face,
                const unsigned char *ccnb,
                size_t ccnb_size)
{
    struct ccn_charbuf *c;
    struct ccn_parsed_interest pi;
    const unsigned char *nonce = NULL;
    size_t nonce_size = 0;
    const unsigned char *pubkey = NULL;
    size_t pubkey_size = 0;
    size_t i;
    struct interest_entry *ie = NULL;
    int default_lifetime = CCN_INTEREST_LIFETIME_SEC << 12;
    intmax_t lifetime = default_lifetime;
    
    if (h != NULL && h->debug == 0)
        return;
    if (ccn_parse_interest(ccnb, ccnb_size, &pi, NULL) >= 0) {
        pubkey_size = (pi.offset[CCN_PI_E_PublisherIDKeyDigest] -
                       pi.offset[CCN_PI_B_PublisherIDKeyDigest]);
        pubkey = ccnb + pi.offset[CCN_PI_B_PublisherIDKeyDigest];
        lifetime = ccn_interest_lifetime(ccnb, &pi);
        ccn_ref_tagged_BLOB(CCN_DTAG_Nonce, ccnb,
                  pi.offset[CCN_PI_B_Nonce],
                  pi.offset[CCN_PI_E_Nonce],
                  &nonce,
                  &nonce_size);
        ie = hashtb_lookup(h->interest_tab, ccnb, pi.offset[CCN_PI_B_Nonce]);
    }
    else {
        pi.min_suffix_comps = 0;
        pi.max_suffix_comps = 32767;
        pi.orderpref = 0;
        pi.answerfrom = CCN_AOK_DEFAULT;
        pi.scope = -1;
    }
    c = ccn_charbuf_create();
    ccn_charbuf_putf(c, "debug.%d %s ", lineno, msg);
    if (face != NULL)
        ccn_charbuf_putf(c, "%u ", face->faceid);
    ccn_uri_append(c, ccnb, ccnb_size, 1);
    ccn_charbuf_putf(c, " (%u bytes", (unsigned)ccnb_size);
    if (pi.min_suffix_comps != 0 || pi.max_suffix_comps != 32767) {
        ccn_charbuf_putf(c, ",c=%d", pi.min_suffix_comps);
        if (pi.min_suffix_comps != pi.max_suffix_comps) {
            ccn_charbuf_putf(c, ":");
            if (pi.max_suffix_comps != 32767)
                ccn_charbuf_putf(c, "%d", pi.max_suffix_comps);
        }
    }
    if (pubkey_size >= 3)
        ccn_charbuf_putf(c, ",pb=%02X%02X%02X",
                         pubkey[0], pubkey[1], pubkey[2]);
    if (pi.orderpref != 0)
        ccn_charbuf_putf(c, ",cs=%d", pi.orderpref);
    if (pi.answerfrom != CCN_AOK_DEFAULT)
        ccn_charbuf_putf(c, ",aok=%#x", pi.answerfrom);
    if (pi.scope != -1)
        ccn_charbuf_putf(c, ",scope=%d", pi.scope);
    if (lifetime != default_lifetime) {
        ccn_charbuf_putf(c, ",life=%d.%04d",
                         (int)(lifetime >> 12),
                         (int)(lifetime & 0xFFF) * 10000 / 4096);
    }
    if (ie != NULL)
        ccn_charbuf_putf(c, ",i=%u", ie->serial);
    ccn_charbuf_putf(c, ")");
    if (nonce_size > 0) {
        const char *p = "";
        ccn_charbuf_putf(c, " ");
        if (nonce_size == 12)
            p = "CCC-P-F-T-NN";
        for (i = 0; i < nonce_size; i++)
            ccn_charbuf_putf(c, "%s%02X", (*p) && (*p++)=='-' ? "-" : "", nonce[i]);
    }
    ccnd_msg(h, "%s", ccn_charbuf_as_string(c));
    ccn_charbuf_destroy(&c);
}

/**
 * CCND Usage message
 */
const char *ccnd_usage_message =
    "ccnd - CCNx Daemon\n"
    "  options: none\n"
    "  arguments: none\n"
    "  environment variables:\n"
    "    CCND_DEBUG=\n"
    "      0 - no messages\n"
    "      1 - basic messages (any non-zero value gets these)\n"
    "      2 - interest messages\n"
    "      4 - content messages\n"
    "      8 - matching details\n"
    "      16 - interest details\n"
    "      32 - gory interest details\n"
    "      64 - log occasional human-readable timestamps\n"
    "      128 - face registration debugging\n"
    "      bitwise OR these together for combinations; -1 gets max logging\n"
    "    CCN_LOCAL_PORT=\n"
    "      UDP port for unicast clients (default "CCN_DEFAULT_UNICAST_PORT").\n"
    "      Also listens on this TCP port for stream connections.\n"
    "      Also affects name of unix-domain socket.\n"
    "    CCN_LOCAL_SOCKNAME=\n"
    "      Name stem of unix-domain socket (default "CCN_DEFAULT_LOCAL_SOCKNAME").\n"
    "    CCND_CAP=\n"
    "      Capacity limit, in count of ContentObjects.\n"
    "      Not an absolute limit.\n"
    "    CCND_MTU=\n"
    "      Packet size in bytes.\n"
    "      If set, interest stuffing is allowed within this budget.\n"
    "      Single items larger than this are not precluded.\n"
    "    CCND_DATA_PAUSE_MICROSEC=\n"
    "      Adjusts content-send delay time for multicast and udplink faces\n"
    "    CCND_DEFAULT_TIME_TO_STALE=\n"
    "      Default for content objects without explicit FreshnessSeconds\n"
    "    CCND_MAX_TIME_TO_STALE=\n"
    "      Limit, in seconds, until content becomes stale\n"
    "    CCND_KEYSTORE_DIRECTORY=\n"
    "      Directory readable only by ccnd where its keystores are kept\n"
    "      Defaults to a private subdirectory of /var/tmp\n"
    "    CCND_LISTEN_ON=\n"
    "      List of ip addresses to listen on; defaults to wildcard\n"
    "    CCND_AUTOREG=\n"
    "      List of prefixes to auto-register on new faces initiated by peers\n"
    "      example: CCND_AUTOREG=ccnx:/like/this,ccnx:/and/this\n"
    "    CCND_PREFIX=\n"
    "      A prefix stem to use for generating guest prefixes\n"
    ;
