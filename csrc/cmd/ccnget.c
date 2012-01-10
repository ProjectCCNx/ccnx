/**
 * @file ccnget.c
 * Get one content item matching the name prefix and write it to stdout.
 * Written as test for ccn_get, but probably useful for debugging.
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2009-2010 Palo Alto Research Center, Inc.
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
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-a] [-c] ccnx:/a/b\n"
            "   Get one content item matching the name prefix and write it to stdout"
            "\n"
            "   -a - allow stale data\n"
            "   -c - content only, not full ccnb\n"
            "   -u - allow unverified content\n"
            "   -v - resolve version number\n",
            progname);
    exit(1);
}

int
main(int argc, char **argv)
{
    struct ccn *h = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    struct ccn_charbuf *resultbuf = NULL;
    const char *arg = NULL;
    struct ccn_parsed_ContentObject pcobuf = { 0 };
    int res;
    int opt;
    int allow_stale = 0;
    int content_only = 0;
    const unsigned char *ptr;
    size_t length;
    int resolve_version = 0;
    const char *env_timeout = getenv("CCN_LINGER");
    int timeout_ms = 3000;
    int get_flags = 0;
    
    while ((opt = getopt(argc, argv, "hacuv")) != -1) {
        switch (opt) {
            case 'a':
                allow_stale = 1;
                break;
            case 'c':
                content_only = 1;
                break;
            case 'u':
                get_flags |= CCN_GET_NOKEYWAIT;
                break;
            case 'v':
                if (resolve_version == 0)
                    resolve_version = CCN_V_HIGHEST;
                else
                    resolve_version = CCN_V_HIGH;
                break;
            case 'h':
            default:
                usage(argv[0]);
        }
    }
    arg = argv[optind];
    if (arg == NULL)
        usage(argv[0]);
    name = ccn_charbuf_create();
    res = ccn_name_from_uri(name, arg);
    if (res < 0) {
        fprintf(stderr, "%s: bad ccn URI: %s\n", argv[0], arg);
        exit(1);
    }
    if (argv[optind + 1] != NULL)
        fprintf(stderr, "%s warning: extra arguments ignored\n", argv[0]);
    h = ccn_create();
    res = ccn_connect(h, NULL);
    if (res < 0) {
        ccn_perror(h, "ccn_connect");
        exit(1);
    }
    if (res < 0) {
        fprintf(stderr, "%s: bad ccn URI: %s\n", argv[0], arg);
        exit(1);
    }
    if (env_timeout != NULL && (res = atoi(env_timeout)) > 0) {
		timeout_ms = res * 1000;
    }
	if (allow_stale || env_timeout != NULL) {
        templ = ccn_charbuf_create();
        ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
        ccn_charbuf_append_tt(templ, CCN_DTAG_Name, CCN_DTAG);
        ccn_charbuf_append_closer(templ); /* </Name> */
		if (allow_stale) {
			ccn_charbuf_append_tt(templ, CCN_DTAG_AnswerOriginKind, CCN_DTAG);
			ccnb_append_number(templ,
							   CCN_AOK_DEFAULT | CCN_AOK_STALE);
			ccn_charbuf_append_closer(templ); /* </AnswerOriginKind> */
		}
		if (env_timeout != NULL) {
			/*
			 * Choose the interest lifetime so there are at least 3
			 * expressions (in the unsatisfied case).
			 */
			unsigned char buf[3] = { 0 };
			unsigned lifetime;
			int i;
			if (timeout_ms > 60000)
				lifetime = 30 << 12;
			else {
				lifetime = timeout_ms * 2 / 5 * 4096 / 1000;
			}
			for (i = sizeof(buf) - 1; i >= 0; i--, lifetime >>= 8)
				buf[i] = lifetime & 0xff;
			ccnb_append_tagged_blob(templ, CCN_DTAG_InterestLifetime, buf, sizeof(buf));
		}
        ccn_charbuf_append_closer(templ); /* </Interest> */
    }
    resultbuf = ccn_charbuf_create();
    if (resolve_version != 0) {
        res = ccn_resolve_version(h, name, resolve_version, 500);
        if (res >= 0) {
            ccn_uri_append(resultbuf, name->buf, name->length, 1);
            fprintf(stderr, "== %s\n",
                            ccn_charbuf_as_string(resultbuf));
            resultbuf->length = 0;
        }
    }
    res = ccn_get(h, name, templ, timeout_ms, resultbuf, &pcobuf, NULL, get_flags);
    if (res >= 0) {
        ptr = resultbuf->buf;
        length = resultbuf->length;
        if (content_only)
            ccn_content_get_value(ptr, length, &pcobuf, &ptr, &length);
        if (length > 0)
            res = fwrite(ptr, length, 1, stdout) - 1;
    }
    ccn_charbuf_destroy(&resultbuf);
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&name);
    ccn_destroy(&h);
    exit(res < 0);
}
