/**
 * @file ccnsyncslice.c
 * Utility to use the Sync library to create or delete sync configuration slices.
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

void
usage(char *prog)
{
    fprintf(stderr,
            "%s [-hv] (create|delete) topo-uri prefix-uri [filter-uri]...\n"
            "   topo-uri, prefix-uri, and the optional filter-uris must be CCNx URIs.\n", prog);
    exit(1);
}

int
main(int argc, char **argv)
{
    int opt;
    int res;
    char *prog = argv[0];
    struct ccn *h;
    struct ccns_slice *slice;
    struct ccn_charbuf *prefix = ccn_charbuf_create();
    struct ccn_charbuf *topo = ccn_charbuf_create();
    struct ccn_charbuf *clause = ccn_charbuf_create();
    struct ccn_charbuf *slice_name = ccn_charbuf_create();
    struct ccn_charbuf *slice_uri = ccn_charbuf_create();
    enum {
        CREATE = 0,
        DELETE = 1
    } cmd = CREATE;
    unsigned verbose = 0;
    unsigned i;
    
    if (prefix == NULL || topo == NULL || clause == NULL ||
        slice_name == NULL || slice_uri == NULL) {
        fprintf(stderr, "Unable to allocate required memory.\n");
        exit(1);
    }
    
    while ((opt = getopt(argc, argv, "vh")) != -1) {
        switch (opt) {
            case 'v':
                verbose = 1;
                break;
            default:
            case 'h':
                usage(prog);
                break;
        }
    }
    argc -= optind;
    argv += optind;
    
    if (argc < 3)
        usage(prog);
    if (strcmp(argv[0], "create") == 0)
        cmd = CREATE;
    else if (strcmp(argv[0], "delete") == 0)
        cmd = DELETE;
    else
        usage(prog);
    
    slice = ccns_slice_create();
    
    ccn_charbuf_reset(topo);
    if (0 > ccn_name_from_uri(topo, argv[1])) usage(prog);
    ccn_charbuf_reset(prefix);
    if (0 > ccn_name_from_uri(prefix, argv[2])) usage(prog);
    if (0 > ccns_slice_set_topo_prefix(slice, topo, prefix)) usage(prog);
    for (i = 3; i < argc; i++) {
        ccn_charbuf_reset(clause);
        if (0 > ccn_name_from_uri(clause, argv[i])) usage(prog);
        else
            if (0 > ccns_slice_add_clause(slice, clause)) usage(prog);
    }
    
    h = ccn_create();
    res = ccn_connect(h, NULL);
    if (0 > res) {
        fprintf(stderr, "Unable to connect to ccnd.\n");
        exit(1);
    }
    switch(cmd) {
        case CREATE:
            res = ccns_write_slice(h, slice, slice_name);
            break;
        case DELETE:
            ccns_slice_name(slice_name, slice);
            res = ccns_delete_slice(h, slice_name);
            break;
    }
    if (verbose || res < 0) {
        ccn_uri_append(slice_uri, slice_name->buf, slice_name->length, 1);
        printf("%s slice %s %s\n",
               cmd == CREATE ? "create" : "delete",
               ccn_charbuf_as_string(slice_uri),
               (res < 0) ? "failed" : "succeeded");
    }
    ccns_slice_destroy(&slice);
    ccn_destroy(&h);
    ccn_charbuf_destroy(&prefix);
    ccn_charbuf_destroy(&topo);
    ccn_charbuf_destroy(&clause);
    ccn_charbuf_destroy(&slice_name);
    ccn_charbuf_destroy(&slice_uri);
    
    exit(res);
}
