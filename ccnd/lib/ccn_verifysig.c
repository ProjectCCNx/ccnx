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

#include <openssl/evp.h>
#include <openssl/x509.h>

#include <ccn/ccn.h>
#include <ccn/keystore.h>
#include <ccn/digest.h>

static unsigned char rawbuf[8801];

#define FAIL(args) do { fprintf args; Bug(__LINE__); } while(0)
#define MOAN(args) do { fprintf args; Moan(__LINE__); status = 1; } while(0)

static void
Moan(int line) {
    fprintf(stderr, " at ccn_verifysig.c:%d\n", line);
}

static void
Bug(int line) {
    Moan(line);
    if (line)
        exit(1);
}

int
ccn_verify_signature(const unsigned char *msg,
                     size_t size,
                     struct ccn_parsed_ContentObject *co,
                     struct ccn_indexbuf *comps, const void *verification_pubkey)
{
    EVP_MD_CTX verc;
    EVP_MD_CTX *ver_ctx = &verc;
    int res;

    const EVP_MD *digest = EVP_md_null();

    const unsigned char *signature_bits = NULL;
    size_t signature_bits_size = 0;

    res = ccn_ref_tagged_BLOB(CCN_DTAG_SignatureBits, msg,
                              co->offset[CCN_PCO_B_SignatureBits],
                              co->offset[CCN_PCO_E_SignatureBits],
                              &signature_bits,
                              &signature_bits_size);
    if (res < 0) FAIL((stderr, "Unable to get SignatureBits from object"));

    if (co->offset[CCN_PCO_B_DigestAlgorithm] == co->offset[CCN_PCO_E_DigestAlgorithm]) {
        digest = EVP_sha256();
    }
    else {
        /* XXX - figure out what algorithm the OID represents */
        fprintf(stderr, "not a DigestAlgorithm I understand right now\n");
        return (-1);
    }

    EVP_MD_CTX_init(ver_ctx);
    res = EVP_VerifyInit_ex(ver_ctx, digest, NULL);
    if (!res) {
        FAIL((stderr, "EVP_VerifyInit_ex"));
    }

    /* we sign from the beginning of the name through the end of the content */

    size_t signed_size = co->offset[CCN_PCO_E_Content] - co->offset[CCN_PCO_B_Name];
    res = EVP_VerifyUpdate(ver_ctx, msg + co->offset[CCN_PCO_B_Name], signed_size);

    res = EVP_VerifyFinal(ver_ctx, signature_bits, signature_bits_size, (EVP_PKEY *)verification_pubkey);
    EVP_MD_CTX_cleanup(ver_ctx);

    if (res == 1)
        return (1);
    else
        return (0);
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
    
    OpenSSL_add_all_digests();
    
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
