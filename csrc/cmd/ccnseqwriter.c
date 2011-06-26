/**
 * @file ccnseqwriter.c
 * Streams data from stdin into ccn
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2010-2011 Palo Alto Research Center, Inc.
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
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ccn/ccn.h>
#include <ccn/uri.h>
#include <ccn/seqwriter.h>

static void
usage(const char *progname)
{
        fprintf(stderr,
                "%s [-h] [-b blocksize] [-r] ccnx:/some/uri\n"
                " Reads stdin, sending data under the given URI"
                " using ccn versioning and segmentation.\n", progname);
        exit(1);
}

int
main(int argc, char **argv)
{
    const char *progname = argv[0];
    struct ccn *ccn = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_seqwriter *w = NULL;
    long blocksize = 1024;
    int torepo = 0;
    int i;
    int status = 0;
    int res;
    ssize_t read_res;
    unsigned char *buf = NULL;
    while ((res = getopt(argc, argv, "hrb:")) != -1) {
        switch (res) {
            case 'b':
                blocksize = atol(optarg);
                if (blocksize <= 0 || blocksize > 4096)
                    usage(progname);
                break;
            case 'r':
                torepo = 1;
                break;
            default:
            case 'h':
                usage(progname);
                break;
        }
    }
    argc -= optind;
    argv += optind;
    if (argv[0] == NULL)
        usage(progname);
    name = ccn_charbuf_create();
    res = ccn_name_from_uri(name, argv[0]);
    if (res < 0) {
        fprintf(stderr, "%s: bad ccnx URI: %s\n", progname, argv[0]);
        exit(1);
    }
    if (argv[1] != NULL)
        fprintf(stderr, "%s warning: extra arguments ignored\n", progname);

    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }
    
    buf = calloc(1, blocksize);
    
    w = ccn_seqw_create(ccn, name);
    if (w == NULL) {
        fprintf(stderr, "ccn_seqw_create failed\n");
        exit(1);
    }
    if (torepo) {
        struct ccn_charbuf *name_v = ccn_charbuf_create();
        ccn_seqw_get_name(w, name_v);
        ccn_name_from_uri(name_v, "%C1.R.sw");
        ccn_name_append_nonce(name_v);
        ccn_get(ccn, name_v, NULL, 2000, NULL, NULL, NULL, 0);
        ccn_charbuf_destroy(&name_v);
    }
    for (i = 0;; i++) {
        ccn_run(ccn, 1);
        read_res = read(0, buf, blocksize);
        if (read_res < 0) {
            perror("read");
            read_res = 0;
            status = 1;
        }
        if (read_res == 0) {
            ccn_seqw_close(w);
            w = NULL;
            status = 0;
            break;
        }
        res = ccn_seqw_write(w, buf, read_res);
        while (res == -1) {
            ccn_run(ccn, 100);
            res = ccn_seqw_write(w, buf, read_res);
        }
        if (res != read_res)
            abort(); /* hmm, ccn_seqw_write did a short write or something */
    }
    ccn_run(ccn, 1);
    free(buf);
    buf = NULL;
    ccn_charbuf_destroy(&name);
    ccn_destroy(&ccn);
    exit(status);
}
