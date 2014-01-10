/**
 * @file ccnseqwriter.c
 * Streams data from stdin into ccn
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2010-2013 Palo Alto Research Center, Inc.
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
#include <memory.h>
#include <ccn/ccn.h>
#include <ccn/uri.h>
#include <ccn/seqwriter.h>

static void
usage(const char *progname)
{
        fprintf(stderr,
                "%s [-h] [-b 0<blocksize<=%d] [-r] [-o keystore-directory] [-d digest] [-p password] ccnx:/some/uri\n"
                "    Reads stdin, sending data under the given URI"
                " using ccn versioning and segmentation.\n"
                "    -h generate this help message.\n"
                "    -b specify the block (segment) size for content objects.  Default %d\n"
                "    -r generate start-write interest so a repository will"
                " store the content.\n"
                "    -s n set scope of start-write interest.\n"
                "       n = 1(local), 2(neighborhood), 3(everywhere) Default 1.\n"
                "    -x specify the freshness for content objects.\n"
                "    -o specify a directory for symmetric keystore\n"
                "    -d specify a symmetric digest to use a symmetric key.\n"
                "    -p specify a password for a symmetric keystore.\n",
                progname, CCN_MAX_CONTENT_PAYLOAD, CCN_MAX_CONTENT_PAYLOAD / 2);
        exit(1);
}
/*
 * make_template: construct an interest template containing the specified scope
 *     An unlimited scope is passed in as 3, and the omission of the scope
 *     field from the template indicates this.
 */
struct ccn_charbuf *
make_template(int scope)
{
    struct ccn_charbuf *templ = NULL;
    templ = ccn_charbuf_create();
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccnb_element_begin(templ, CCN_DTAG_Name);
    ccnb_element_end(templ); /* </Name> */
    if (0 <= scope && scope <= 2)
        ccnb_tagged_putf(templ, CCN_DTAG_Scope, "%d", scope);
    ccnb_element_end(templ); /* </Interest> */
    return(templ);
}

int
main(int argc, char **argv)
{
    const char *progname = argv[0];
    struct ccn *ccn = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_seqwriter *w = NULL;
    int blocksize = CCN_MAX_CONTENT_PAYLOAD / 2;
    int freshness = -1;
    int torepo = 0;
    int scope = 1;
    int i;
    int status = 0;
    int res;
    ssize_t read_res;
    size_t blockread;
    unsigned char *buf = NULL;
    struct ccn_charbuf *templ;
    char *symmetric_suffix = NULL;
    const char *password = NULL;
    char *dir = NULL;
    
    while ((res = getopt(argc, argv, "hrb:s:x:d:p:o:")) != -1) {
        switch (res) {
            case 'b':
                blocksize = atoi(optarg);
                if (blocksize <= 0 || blocksize > CCN_MAX_CONTENT_PAYLOAD)
                    usage(progname);
                break;
            case 'r':
                torepo = 1;
                break;
            case 's':
                scope = atoi(optarg);
                if (scope < 1 || scope > 3)
                    usage(progname);
                break;
            case 'x':
                freshness = atoi(optarg);
                if (freshness < 0)
                    usage(progname);
                break;
            case 'd':
                symmetric_suffix = optarg;
                break;
            case 'p':
                password = optarg;
                break;
            case 'o':
                dir = optarg;
                break;
            case 'h':
                usage(progname);
                break;
        }
    }
    argc -= optind;
    argv += optind;
    if (argc != 1)
        usage(progname);
    name = ccn_charbuf_create();
    res = ccn_name_from_uri(name, argv[0]);
    if (res < 0) {
        fprintf(stderr, "%s: bad CCN URI: %s\n", progname, argv[0]);
        exit(1);
    }
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

    if (symmetric_suffix != NULL) {
        struct ccn_charbuf *key_digest = ccn_charbuf_create();

        if (ccn_get_key_digest_from_suffix(ccn, dir, symmetric_suffix, password, key_digest)) {
            perror("Can't access keystore");
            exit(1);
        }
        ccn_seqw_set_key_digest(w, key_digest->buf, key_digest->length);
        ccn_charbuf_destroy(&key_digest);
    }
    
    ccn_seqw_set_block_limits(w, blocksize, blocksize);
    if (freshness > -1)
        ccn_seqw_set_freshness(w, freshness);
    if (torepo) {
        struct ccn_charbuf *name_v = ccn_charbuf_create();
        ccn_seqw_get_name(w, name_v);
        ccn_name_from_uri(name_v, "%C1.R.sw");
        ccn_name_append_nonce(name_v);
        templ = make_template(scope);
        res = ccn_get(ccn, name_v, templ, 60000, NULL, NULL, NULL, 0);
        ccn_charbuf_destroy(&templ);
        ccn_charbuf_destroy(&name_v);
        if (res < 0) {
            fprintf(stderr, "No response from repository\n");
            exit(1);
        }
    }
    blockread = 0;
    for (i = 0;; i++) {
        while (blockread < blocksize) {
            if (ccn_run(ccn, 1) < 0) {
                fprintf(stderr, "Lost connection to ccnd: %s\n",
                        strerror(ccn_geterror(ccn)));
                exit(1);
            }
            read_res = read(0, buf + blockread, blocksize - blockread);
            if (read_res == 0)
                goto cleanup;
            if (read_res < 0) {
                perror("read");
                status = 1;
                goto cleanup;
            }
            blockread += read_res;
        }
        res = ccn_seqw_write(w, buf, blockread);
        while (res == -1) {
            if (ccn_run(ccn, 100) < 0) {
                fprintf(stderr, "Lost connection to ccnd: %s\n",
                        strerror(ccn_geterror(ccn)));
                exit(1);
            }
            res = ccn_seqw_write(w, buf, blockread);
        }
        if (res != blockread)
            abort(); /* hmm, ccn_seqw_write did a short write or something */
        blockread = 0;
    }
    
cleanup:
    // flush out any remaining data and close
    if (blockread > 0) {
        res = ccn_seqw_write(w, buf, blockread);
        while (res == -1) {
            if (ccn_run(ccn, 100) < 0) {
                fprintf(stderr, "Lost connection to ccnd: %s\n",
                        strerror(ccn_geterror(ccn)));
                exit(1);
            }
            res = ccn_seqw_write(w, buf, blockread);
        }
    }
    ccn_seqw_close(w);
    ccn_run(ccn, 1);
    free(buf);
    buf = NULL;
    ccn_charbuf_destroy(&name);
    ccn_destroy(&ccn);
    exit(status);
}
