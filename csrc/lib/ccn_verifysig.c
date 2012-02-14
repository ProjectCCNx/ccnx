/**
 * @file ccn_verifysig.c
 * Utility to check the signature on ccnb-formatted ContentObjects.
 * 
 * A CCNx program.
 *
 * Copyright (C) 2009, 2010 Palo Alto Research Center, Inc.
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
#include <sys/types.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/keystore.h>
#include <ccn/signing.h>

static unsigned char rawbuf[8801];

#define MOAN(args) do { fprintf args; Moan(__LINE__); status = 1; } while(0)

void
Moan(int line) {
    fprintf(stderr, " at ccn_verifysig.c:%d\n", line);
}

int
main(int argc, char **argv)
{
    int opt;
    int res;
    int argi;
    int fd;
    ssize_t size;
    const char *filename;
    struct ccn_parsed_ContentObject obj = {0};
    struct ccn_parsed_ContentObject *co = &obj;
    struct ccn_indexbuf *comps = ccn_indexbuf_create();
    struct ccn_keystore *keystore;
    char *home = getenv("HOME");
    char *keystore_suffix = "/.ccnx/.ccnx_keystore";
    char *keystore_name = NULL;

    int status = 0;
    
    int good = 0;
    int bad = 0;
    
    /*    OpenSSL_add_all_digests(); */
    
    /* verify against the user's own public key until we have the infrastructure
     * to locate keys
     */
    const void *verification_pubkey = NULL;

    if (home == NULL) {
        printf("Unable to determine home directory for keystore\n");
        exit(1);
    }
    keystore_name = calloc(1, strlen(home) + strlen(keystore_suffix) + 1);
    
    strcat(keystore_name, home);
    strcat(keystore_name, keystore_suffix);

    keystore = ccn_keystore_create();
    if (0 != ccn_keystore_init(keystore, keystore_name, "Th1s1sn0t8g00dp8ssw0rd.")) {
        printf("Failed to initialize keystore\n");
        exit(1);
    }
    verification_pubkey = ccn_keystore_public_key(keystore);

    while ((opt = getopt(argc, argv, "h")) != -1) {
        switch (opt) {
        default:
        case 'h':
            fprintf(stderr, "provide names of files containing ccnb format content\n");
            exit(1);
        }
    }
    argc -= optind;
    argv += optind;
    for (argi = 0; argv[argi] != NULL; argi++) {
        filename = argv[argi];
        fd = open(filename, O_RDONLY);
        if (fd == -1) {
            perror(filename);
            status = 1;
            continue;
        }
        fprintf(stderr, "Reading %s ... ", filename);
        size = read(fd, rawbuf, sizeof(rawbuf));
        if (size < 0) {
            perror("skipping");
            close(fd);
            status = 1;
            continue;
        }
        close(fd);
        if (size == sizeof(rawbuf)) {
            fprintf(stderr, "skipping: too big\n");
            status = 1;
            continue;
        }
        res = ccn_parse_ContentObject(rawbuf, size, co, comps);
        if (res < 0) {
            fprintf(stderr, "skipping: not a ContentObject\n");
            status = 1;
            continue;
        }
        if (co->offset[CCN_PCO_B_KeyLocator] != co->offset[CCN_PCO_E_KeyLocator]) {
            struct ccn_buf_decoder decoder;
            struct ccn_buf_decoder *d =
                ccn_buf_decoder_start(&decoder,
                                      rawbuf + co->offset[CCN_PCO_B_Key_Certificate_KeyName],
                                      co->offset[CCN_PCO_E_Key_Certificate_KeyName] - co->offset[CCN_PCO_B_Key_Certificate_KeyName]);
            
           fprintf(stderr, "[has KeyLocator: ");
           if (ccn_buf_match_dtag(d, CCN_DTAG_KeyName)) fprintf(stderr, "KeyName] ");
           if (ccn_buf_match_dtag(d, CCN_DTAG_Certificate)) fprintf(stderr, "Certificate] ");
           if (ccn_buf_match_dtag(d, CCN_DTAG_Key)) fprintf(stderr, "Key] ");
        }

        res = ccn_verify_signature(rawbuf, size, co, verification_pubkey);
        
        if (res != 1) {
            fprintf(stderr, "Signature failed to verify\n");
            bad++;
        } else {
            fprintf(stderr, "Verified\n");
            good++;
        }   
    }
    printf("\n%d files, %d skipped, %d good, %d bad.\n", argi, argi - good - bad, good, bad);
    exit(status);
}
