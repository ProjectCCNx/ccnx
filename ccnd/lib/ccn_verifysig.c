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

static const unsigned char magicgoop[] = {
    0x30, 0x2f, 0x30, 0x0b, 0x06, 0x09, 0x60, 0x86,
0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01, 0x04, 0x20 };

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
main(int argc, char **argv)
{
    int ch;
    int res;
#if 0
    int i;
#endif
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
    
    const EVP_MD *md;
    EVP_MD_CTX *md_ctx;
    
    OpenSSL_add_all_digests();
    
    md = EVP_get_digestbynid(NID_sha256);
    if (md == NULL) FAIL((stderr, "The openssl library does not seem to support sha256"));
    
    md_ctx = EVP_MD_CTX_create();
    if (md_ctx == NULL) FAIL((stderr, "URP"));
    
    if (!EVP_DigestInit_ex(md_ctx, md, NULL)) {
        EVP_MD_CTX_destroy(md_ctx);
        FAIL((stderr, "URP"));
    }
    
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
        
        const unsigned char *msg_digest = NULL;
        size_t msg_digest_size = 0;
        
        res = ccn_ref_tagged_BLOB(CCN_DTAG_ContentDigest, rawbuf,
                                  co->offset[CCN_PCO_B_CAUTH_ContentDigest],
                                  co->offset[CCN_PCO_E_CAUTH_ContentDigest],
                                  &msg_digest,
                                  &msg_digest_size);
        if (res < 0) FAIL((stderr, "URP"));
#if 0
        for (i = 0; i < msg_digest_size; i++)
            fprintf(stderr, "%02x ", (unsigned)msg_digest[i]);
        fprintf(stderr, "<---<<< ContentDigest\n");
#endif
        
        if (msg_digest_size > sizeof(magicgoop) && 0 == memcmp(msg_digest, magicgoop, sizeof(magicgoop))) {
            // keep going
        }
        else {
            fprintf(stderr, "skipping: not a ContentDigest I understand right now\n");
            continue;
        }
        
        const unsigned char *actual_contentp = NULL;
        size_t actual_content_size = 0;
        res = ccn_ref_tagged_BLOB(CCN_DTAG_Content, rawbuf, co->Content, size - 1, &actual_contentp, &actual_content_size);
        if (res < 0) FAIL((stderr, "URP"));        
        
        if (!EVP_DigestInit_ex(md_ctx, md, NULL)) {
            EVP_MD_CTX_destroy(md_ctx);
            FAIL((stderr, "URP"));
        }
        
        res =  EVP_DigestUpdate(md_ctx, actual_contentp, actual_content_size);
        
        unsigned char mdbuf[EVP_MAX_MD_SIZE];
        unsigned int buflen = 0;
        res = EVP_DigestFinal_ex(md_ctx, mdbuf, &buflen);
#if 0
        for (i = 0; i < buflen; i++)
            fprintf(stderr, "%02x ", (unsigned)mdbuf[i]);
        fprintf(stderr, "<---<<< Computed sha256\n");
#endif
        
        if (msg_digest_size != buflen + sizeof(magicgoop))
            FAIL((stderr, "msg_digest_size(%d) != buflen(%d) + sizeof(magicgoop)(%d)", (int)msg_digest_size, (int)buflen, (int)sizeof(magicgoop)));
        if (0 != memcmp(mdbuf, msg_digest + sizeof(magicgoop), buflen)) {
            MOAN((stderr, "Computed sha256 digest does not match"));
            bad++;
            continue;
        }

        EVP_PKEY *verification_key = NULL;
        const unsigned char *public_key_ptr = some_public_key;
        // EVP_PKEY *d2i_PUBKEY(EVP_PKEY **a, const unsigned char **pp, long length);
        verification_key = d2i_PUBKEY(NULL, &public_key_ptr, sizeof(some_public_key));
        
        const EVP_MD *the_digest;
        if (verification_key->type == EVP_PKEY_DSA) {
            the_digest = EVP_dss1();
        }
        else {
            the_digest = EVP_sha256();
        }
        
        EVP_MD_CTX *ver_ctx;
        ver_ctx = EVP_MD_CTX_create();
        if (ver_ctx == NULL) FAIL((stderr, "URP"));
        
        res = EVP_VerifyInit(ver_ctx, the_digest);
        if (!res) {
            // EVP_MD_CTX_destroy(ver_ctx);
            FAIL((stderr, "EVP_VerifyInit"));
        }
        
        
        int nameComponentCount;
        nameComponentCount = ccn_fetch_tagged_nonNegativeInteger(
            CCN_DTAG_NameComponentCount,
            rawbuf,
            co->offset[CCN_PCO_B_CAUTH_NameComponentCount],
            co->offset[CCN_PCO_E_CAUTH_NameComponentCount]);
        if (nameComponentCount < 0)
            FAIL((stderr, "NameComponentCount is not a nonNegativeInteger"));
        if (nameComponentCount >= comps->n) {
            FAIL((stderr, "NameComponentCount(%d) exceeds number of name components(%d)",
                nameComponentCount, (int)(comps->n - 1)));
        }

        const unsigned char *signed_name = NULL;
        size_t signed_name_size = 0;
        if (nameComponentCount == comps->n - 1) {
            signed_name = rawbuf + co->Name;
            signed_name_size = co->offset[CCN_PCO_E_Name] - co->offset[CCN_PCO_B_Name];
        } else {
            MOAN((stderr, "Cannot process partial names in signatures yet"));
            continue;
        }
        res = EVP_VerifyUpdate(ver_ctx, signed_name, signed_name_size);

        res = EVP_VerifyUpdate(ver_ctx, rawbuf + co->offset[CCN_PCO_B_ContentAuthenticator],
                               co->offset[CCN_PCO_E_ContentAuthenticator] - co->offset[CCN_PCO_B_ContentAuthenticator]);
        
        const unsigned char *signature = NULL;
        size_t signature_size = 0;

        res = ccn_ref_tagged_BLOB(CCN_DTAG_Signature, rawbuf,
                                  co->offset[CCN_PCO_B_Signature],
                                  co->offset[CCN_PCO_E_Signature],
                                  &signature,
                                  &signature_size);
        if (res < 0) FAIL((stderr, "Unable to get Signature from object"));

        res = EVP_VerifyFinal(ver_ctx, signature, signature_size, verification_key);

         if (res != 1) {
             MOAN((stderr, "Signature failed to verify"));
             bad++;
         } else {
             fprintf(stderr, "Verified\n");
             good++;
         }   
    }
    printf("\n%d files, %d skipped, %d good, %d bad.\n", argi, argi - good - bad, good, bad);
    exit(status);
}
