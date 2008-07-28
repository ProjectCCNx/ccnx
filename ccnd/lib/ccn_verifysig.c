/*
 * Utility to check the signature on ccnb-formatted ContentObjects
 */

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/keystore.h>
#include <ccn/digest.h>
#include <ccn/signing.h>

static unsigned char rawbuf[8801];

#define MOAN(args) do { fprintf args; Moan(__LINE__); status = 1; } while(0)

static void
Moan(int line) {
    fprintf(stderr, " at ccn_verifysig.c:%d\n", line);
}

int
main(int argc, char **argv)
{
    int ch;
    int res;
    int argi;
    int fd;
    ssize_t size;
    const char *filename;
    struct ccn_parsed_ContentObject obj = {0};
    struct ccn_parsed_ContentObject *co = &obj;
    struct ccn_indexbuf *comps = ccn_indexbuf_create();
    struct ccn_keystore *keystore;
    char keystore_name[1024] = {0};

    int status = 0;
    
    int good = 0;
    int bad = 0;
    
    /*    OpenSSL_add_all_digests(); */
    
    /* verify against the user's own public key until we have the infrastructure
     * to locate keys
     */
    const void *verification_pubkey = NULL;

    strcat(keystore_name, getenv("HOME"));
    strcat(keystore_name, "/.ccn/.ccn_keystore");
    keystore = ccn_keystore_create();
    if (0 != ccn_keystore_init(keystore, keystore_name, "Th1s1sn0t8g00dp8ssw0rd.")) {
        printf("Failed to initialize keystore\n");
        exit(1);
    }
    verification_pubkey = ccn_keystore_public_key(keystore);

    while ((ch = getopt(argc, argv, "h")) != -1) {
        switch (ch) {
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
        if (co->offset[CCN_PCO_B_CAUTH_KeyLocator] != co->offset[CCN_PCO_E_CAUTH_KeyLocator]) {
            struct ccn_buf_decoder decoder;
            struct ccn_buf_decoder *d =
                ccn_buf_decoder_start(&decoder,
                                      rawbuf + co->offset[CCN_PCO_B_CAUTH_Key_Certificate_KeyName],
                                      co->offset[CCN_PCO_E_CAUTH_Key_Certificate_KeyName] - co->offset[CCN_PCO_B_CAUTH_Key_Certificate_KeyName]);
            
           fprintf(stderr, "[has KeyLocator: ");
           if (ccn_buf_match_dtag(d, CCN_DTAG_KeyName)) fprintf(stderr, "KeyName] ");
           if (ccn_buf_match_dtag(d, CCN_DTAG_Certificate)) fprintf(stderr, "Certificate] ");
           if (ccn_buf_match_dtag(d, CCN_DTAG_Key)) fprintf(stderr, "Key] ");
        }

        res = ccn_verify_signature(rawbuf, size, co, comps, verification_pubkey);
        
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
