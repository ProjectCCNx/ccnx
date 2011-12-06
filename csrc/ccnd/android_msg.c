/**
 * Copyright (C) 2009,2010 Palo Alto Research Center, Inc.
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
 * Logging support for ccnd, for Android platform
 *
 *
 */

#include <stdio.h>
#include <sys/time.h>
#include <stdarg.h>
#include <time.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/ccnd.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

#include "ccnd_private.h"

#include <android/log.h>

/**
 *  Produce ccnd debug output to the Android log.
 *  Output is produced on stderr under the control of h->debug;
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

    if (h != NULL && h->debug == 0)
        return;

    b = ccn_charbuf_create();

    gettimeofday(&t, NULL);
    ccn_charbuf_putf(b, "%ld.%06u ccnd[%d]: %s\n",
        (long)t.tv_sec, (unsigned)t.tv_usec, (int)getpid(), fmt);
    va_start(ap, fmt);

	__android_log_vprint(ANDROID_LOG_INFO, "CCND", (const char *)b->buf, ap);
    va_end(ap);
    ccn_charbuf_destroy(&b);
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
    size_t i;

    if (h != NULL && h->debug == 0)
        return;
    c = ccn_charbuf_create();
    ccn_charbuf_putf(c, "debug.%d %s ", lineno, msg);
    if (face != NULL)
        ccn_charbuf_putf(c, "%u ", face->faceid);
    ccn_uri_append(c, ccnb, ccnb_size, 1);
    ccn_charbuf_putf(c, " (%u bytes)", (unsigned)ccnb_size);
    if (ccn_parse_interest(ccnb, ccnb_size, &pi, NULL) >= 0) {
        ccn_ref_tagged_BLOB(CCN_DTAG_Nonce, ccnb,
                  pi.offset[CCN_PI_B_Nonce],
                  pi.offset[CCN_PI_E_Nonce],
                  &nonce,
                  &nonce_size);
        if (nonce_size > 0) {
            ccn_charbuf_putf(c, " ");
            for (i = 0; i < nonce_size; i++)
                ccn_charbuf_putf(c, "%02X", nonce[i]);
        }
    }
    ccnd_msg(h, "%s", ccn_charbuf_as_string(c));
    ccn_charbuf_destroy(&c);
}

/**
 * Print the ccnd usage message on stderr.
 *
 * Does not exit.
 */
void
ccnd_usage(void)
{
    static const char ccnd_usage_message[] =
    "ccnd - CCNx Daemon\n"
    "  options: none\n"
    "  arguments: none\n"
    "  environment variables:\n"
    "    CCND_DEBUG=\n"
    "      0 - no stderr messages\n"
    "      1 - default stderr messages (any non-zero value gets these)\n"
    "      2 - interest messages\n"
    "      4 - content messages\n"
    "      8 - matching details\n"
    "      16 - interest details\n"
    "      32 - gory interest details\n"
    "      64 - log occasional human-readable timestamps\n"
    "      128 - face registration debugging\n"
    "      bitwise OR these together for combinations; -1 gets everything\n"
    "    CCN_LOCAL_PORT=\n"
    "      UDP port for unicast clients (default %s).\n"
    "      Also listens on this TCP port for stream connections.\n"
    "      Also affects name of unix-domain socket.\n"
    "    CCND_CAP=\n"
    "      Capacity limit, in count of ContentObjects.\n"
    "      Not an absolute limit.\n"
    "    CCND_MTU=\n"
    "      Packet size in bytes.\n"
    "      If set, interest stuffing is allowed within this budget.\n"
    "      Single items larger than this are not precluded.\n"
    "    CCND_DATA_PAUSE_MICROSEC=\n"
    "      Adjusts content-send delay time for multicast and udplink faces\n";
    fprintf(stderr, ccnd_usage_message, CCN_DEFAULT_UNICAST_PORT);
}

