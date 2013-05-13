/**
 * @file ccnsyncwatch.c
 * Utility to use the Sync library protocol to watch changes in a repository's contents.
 *
 * A CCNx program.
 *
 * Copyright (C) 2012-2013 Palo Alto Research Center, Inc.
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
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/sync.h>
#include <ccn/uri.h>
#include <openssl/evp.h>

char *
hex_string(unsigned char *s, size_t l)
{
    const char *hex_digits = "0123456789abcdef";
    char *r;
    int i;
    r = calloc(1, 1 + 2 * l);
    for (i = 0; i < l; i++) {
        r[2*i] = hex_digits[(s[i]>>4) & 0xf];
        r[1+2*i] = hex_digits[s[i] & 0xf];
    }
    return(r);
}

int hex_value(char c)
{
    int ch = (unsigned char)c;
    if (0 == isxdigit(ch)) return (-1);
    if (c >= '0' && c <= '9') return (c - '0');
    return (10+tolower(ch) - 'a');
}

void
usage(char *prog)
{
    fprintf(stderr,
            "%s [-h] [-t topo-uri] [-p prefix-uri] [-f filter-uri] [-r roothash-hex] [-w timeout-secs]\n"
            "   topo-uri, prefix-uri, and filter-uri must be CCNx URIs.\n"
            "   roothash-hex must be an even number of hex digits "
            "representing a valid starting root hash.\n"
            "   timeout-secs is the time, in seconds that the program "
            "should monitor sync activity.\n"
            "       or -1 to run until interrupted.\n", prog);
    exit(1);
}

int
sync_cb(struct ccns_name_closure *nc,
        struct ccn_charbuf *lhash,
        struct ccn_charbuf *rhash,
        struct ccn_charbuf *name)
{
    char *hexL;
    char *hexR;
    struct ccn_charbuf *uri = ccn_charbuf_create();
    if (lhash == NULL || lhash->length == 0) {
        hexL = strdup("none");
    } else
        hexL = hex_string(lhash->buf, lhash->length);
    if (rhash == NULL || rhash->length == 0) {
        hexR = strdup("none");
    } else
        hexR = hex_string(rhash->buf, rhash->length);
    if (name != NULL)
        ccn_uri_append(uri, name->buf, name->length, 1);
    else
        ccn_charbuf_append_string(uri, "(null)");
    printf("%s %s %s\n", ccn_charbuf_as_string(uri), hexL, hexR);
    fflush(stdout);
    free(hexL);
    free(hexR);
    ccn_charbuf_destroy(&uri);
    return(0);
}

int
main(int argc, char **argv)
{
    int opt;
    int res;
    struct ccn *h;
    struct ccns_slice *slice;
    struct ccns_handle *ccns;
    struct ccns_name_closure nc = {0};
    struct ccns_name_closure *closure = &nc;
    struct ccn_charbuf *prefix = ccn_charbuf_create();
    struct ccn_charbuf *roothash = NULL;
    struct ccn_charbuf *topo = ccn_charbuf_create();
    struct ccn_charbuf *clause = ccn_charbuf_create();
    int timeout = 10*1000;
    unsigned i, j, n;
 
    slice = ccns_slice_create();
    ccn_charbuf_reset(prefix);
    ccn_charbuf_reset(topo);
    while ((opt = getopt(argc, argv, "hf:p:r:t:w:")) != -1) {
        switch (opt) {
            case 'f':
                ccn_charbuf_reset(clause);
                if (0 > ccn_name_from_uri(clause, optarg)) usage(argv[0]);
                ccns_slice_add_clause(slice, clause);
                break;
            case 'p':
                ccn_charbuf_reset(prefix);
                if (0 > ccn_name_from_uri(prefix, optarg)) usage(argv[0]);
                break;
            case 'r':
                n = strlen(optarg);
                if (n == 0) {
                    roothash = ccn_charbuf_create();
                    break;
                }
                if ((n % 2) != 0)
                    usage(argv[0]);
                roothash = ccn_charbuf_create_n(n / 2);
                for (i = 0; i < (n / 2); i++) {
                    j = (hex_value(optarg[2*i]) << 4) | hex_value(optarg[1+2*i]);
                    ccn_charbuf_append_value(roothash, j, 1);
                }
                break;
            case 't':
                ccn_charbuf_reset(topo);
                if (0 > ccn_name_from_uri(topo, optarg)) usage(argv[0]);
                break;
            case 'w':
                timeout = atoi(optarg);
                if (timeout < -1) usage(argv[0]);
                timeout *= 1000;
                break;
            default:
            case 'h':
                usage(argv[0]);
        }
    }

    ccns_slice_set_topo_prefix(slice, topo, prefix);
    h = ccn_create();
    res = ccn_connect(h, NULL);
    closure->callback = &sync_cb;
    ccns = ccns_open(h, slice, closure, roothash, NULL);
    ccn_run(h, timeout);
    ccns_close(&ccns, NULL, NULL);
    ccns_slice_destroy(&slice);
    ccn_destroy(&h);
    EVP_cleanup();
    exit(res);
}
