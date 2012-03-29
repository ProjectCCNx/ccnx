/**
 * @file ccnsyncwatch.c
 * Utility to use the Sync library protocol to watch changes in a repository's contents.
 * 
 * A CCNx program.
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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
    if (0 == isxdigit(c)) return (-1);
    if (c >= '0' && c <= '9') return (c - '0');
    return (10+tolower(c) - 'a');
}

int
sync_cb(struct ccns_handle *h,
        struct ccn_charbuf *lhash,
        struct ccn_charbuf *rhash,
        struct ccn_charbuf *name)
{
    char *hexL;
    char *hexR;
    struct ccn_charbuf *uri = ccn_charbuf_create();
    ccn_uri_append(uri, name->buf, name->length, 1);
    if (lhash == NULL || lhash->length == 0) {
        hexL = strdup("none");
    } else
        hexL = hex_string(lhash->buf, lhash->length);
    if (rhash == NULL || rhash->length == 0) {
        hexR = strdup("none");
    } else
        hexR = hex_string(rhash->buf, rhash->length);
    printf("%s %s %s\n", ccn_charbuf_as_string(uri), hexL, hexR);
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
    struct ccn_charbuf *prefix = ccn_charbuf_create();
    struct ccn_charbuf *roothash = NULL;
    struct ccn_charbuf *topo = ccn_charbuf_create();
    int timeout = 10*1000;
    unsigned i, j, n;
    
    ccn_name_init(prefix);
    ccn_name_init(topo);
    while ((opt = getopt(argc, argv, "hp:r:t:w:")) != -1) {
        switch (opt) {
            case 'p':
                ccn_name_from_uri(prefix, optarg);
                break;
            case 'r':
                n = strlen(optarg);
                if ((n % 2) != 0) goto Usage;
                roothash = ccn_charbuf_create_n(n / 2);
                for (i = 0; i < (n / 2); i++) {
                    j = (hex_value(optarg[2*i]) << 4) | hex_value(optarg[1+2*i]);
                    ccn_charbuf_append_value(roothash, j, 1);
                }
                break;
            case 't':
                ccn_name_from_uri(topo, optarg);
                break;
            case 'w':
                timeout = atoi(optarg) * 1000;
                break;
            default:
            case 'h':
            Usage:
                fprintf(stderr, "%s [-t topo-uri] [-p prefix-uri] [-r roothash-hex] [-w timeout-secs]\n", argv[0]);
                exit(1);
        }
    }
    h = ccn_create();
    res = ccn_connect(h, NULL);
    slice = ccns_slice_create();
    ccns_slice_set_topo_prefix(slice, topo, prefix);
    ccns = ccns_open(h, slice, &sync_cb, roothash, NULL);
    ccn_run(h, timeout);
    ccns_close(&ccns, NULL, NULL);
    ccns_slice_destroy(&slice);
    ccn_destroy(&h);
    exit(res);
}
