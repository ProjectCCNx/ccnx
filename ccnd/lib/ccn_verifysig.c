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

static const unsigned char some_public_key[162] =
"\060\201\237\060\015\006\011\052\206\110\206\367\015\001\001\001"
"\005\000\003\201\215\000\060\201\211\002\201\201\000\303\055\043"
"\124\167\152\115\130\241\165\015\204\323\027\336\267\123\075\145"
"\321\313\047\050\113\200\307\371\243\274\216\354\072\010\164\277"
"\025\025\302\177\304\167\254\134\005\075\202\024\211\323\051\047"
"\304\212\121\155\174\102\244\136\153\315\264\154\375\366\375\047"
"\324\105\145\044\112\041\162\177\045\335\123\061\117\331\047\037"
"\213\201\227\134\236\304\146\113\153\042\337\066\374\020\302\016"
"\053\220\075\305\332\232\102\276\367\301\336\111\244\323\064\054"
"\012\054\040\020\001\017\270\363\141\360\327\150\143\002\003\001"
"\000\001";

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
