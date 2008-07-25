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

unsigned char some_public_key[162]={
0x30,0x81,0x9F,0x30,0x0D,0x06,0x09,0x2A,0x86,0x48,0x86,0xF7,0x0D,0x01,0x01,0x01,
0x05,0x00,0x03,0x81,0x8D,0x00,0x30,0x81,0x89,0x02,0x81,0x81,0x00,0x9C,0x32,0xCB,
0xCF,0x6E,0xA8,0x90,0x55,0x5F,0x67,0xF3,0xA8,0x3D,0xF4,0x03,0xAC,0xD5,0x22,0xF0,
0x54,0xD1,0x34,0x68,0xC8,0xFD,0x1F,0x7E,0x5B,0xC8,0xBF,0xFE,0xE8,0xF8,0xD1,0x9A,
0x5E,0x28,0x18,0xE5,0x28,0xE3,0x94,0xB1,0xA9,0x44,0x9F,0x19,0x81,0x87,0x67,0xC1,
0xCE,0x7F,0xD9,0x01,0xF6,0xAD,0xCE,0x48,0xB5,0x46,0x9E,0x73,0xC1,0x8E,0xC0,0x33,
0x0C,0x98,0x4C,0x73,0xAE,0x3C,0x86,0x2F,0x2E,0xC8,0xC0,0x3B,0x71,0x5F,0x2B,0xF1,
0x34,0x6E,0x9E,0x0B,0x82,0xE1,0x6D,0x80,0x6B,0x51,0x56,0x87,0xC6,0x64,0x1E,0xDB,
0x8E,0xB1,0x0F,0x5E,0x76,0x7E,0x5B,0xB4,0x73,0x7B,0xB8,0xA6,0xA4,0xA0,0x3E,0x2E,
0x7A,0x99,0xF8,0x50,0x30,0x34,0x6E,0x6E,0x70,0xF8,0x30,0x6C,0xB5,0x02,0x03,0x01,
0x00,0x01,
};

int
ccn_verify_signature(const unsigned char *msg,
                     size_t size,
                     struct ccn_parsed_ContentObject *co,
                     struct ccn_indexbuf *comps, EVP_PKEY *verification_pubkey)
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

    res = EVP_VerifyFinal(ver_ctx, signature_bits, signature_bits_size, verification_pubkey);
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
    
    int status = 0;
    
    int good = 0;
    int bad = 0;
    
    OpenSSL_add_all_digests();
    
    /* we're checking against a single public key, until we have the
     * infrastructure for locating keys
     */
    EVP_PKEY *verification_pubkey = NULL;
    const unsigned char *public_key_ptr = some_public_key;
    verification_pubkey = d2i_PUBKEY(NULL, &public_key_ptr, sizeof(some_public_key));
        
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
