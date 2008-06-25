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

#include <ccn/ccn.h>

static unsigned char rawbuf[8801];

int
main(int argc, char **argv)
{
    int ch;
    int res;
    int i;
    int argi;
    int fd;
    ssize_t size;
    const char *filename;
    struct ccn_parsed_ContentObject obj = {0};
    struct ccn_indexbuf *comps = ccn_indexbuf_create();
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = NULL;

    int status = 0;
    
    const EVP_MD *md;
    EVP_MD_CTX *md_ctx;
    
    OpenSSL_add_all_digests();
    
    md = EVP_get_digestbynid(NID_sha256);
    if (md == NULL) abort();
    
    md_ctx = EVP_MD_CTX_create();
    if (md_ctx == NULL) abort();

    if (!EVP_DigestInit_ex(md_ctx, md, NULL)) {
        EVP_MD_CTX_destroy(md_ctx);
        abort();
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
        res = ccn_parse_ContentObject(rawbuf, size, &obj, comps);
        if (res < 0) {
            fprintf(stderr, "skipping: not a ContentObject\n");
            status = 1;
            continue;
        }
        d = ccn_buf_decoder_start(&decoder, rawbuf + obj.Content, size - obj.Content);
        const unsigned char *actual_contentp = rawbuf;
        size_t actual_content_size = 0;
        if (!ccn_buf_match_dtag(d, CCN_DTAG_Content)) abort();
        ccn_buf_advance(d);
        ccn_buf_match_blob(d, &actual_contentp, &actual_content_size);
        
        
        if (!EVP_DigestInit_ex(md_ctx, md, NULL)) {
            EVP_MD_CTX_destroy(md_ctx);
            abort();
        }

       res =  EVP_DigestUpdate(md_ctx, actual_contentp, actual_content_size);
       
        unsigned char mdbuf[EVP_MAX_MD_SIZE];
        unsigned int buflen = 0;
        res = EVP_DigestFinal_ex(md_ctx, mdbuf, &buflen);
        for (i = 0; i < buflen; i++)
            fprintf(stderr, "%02x ", (unsigned)mdbuf[i]);
        fprintf(stderr, "\n");
        
        /* figure out what gets included in the digest, and do
           EVP_DigestUpdate(md_ctx, &stuff, count) and then
           unsigned char mdbuf[EVP_MAX_MD_SIZE];
           unsigned int buflen = 0;
           EVP_DigestFinal_ex(md_ctx, mdbuf, &buflen);

           This will give us the digest of the data.
           If we're trying to verify it according to the public key, we have to
           locate the actual key, and then it looks very similar, with:
           
           const EVP_PKEY *verification_key = <get the key>;
           (create a context)
           (get the digest)
           if (verification_key->type == EVP_PKEY_DSA) {
           	digest = EVP_dss1();
           }
           EVP_VerifyInit(context, digest);
           EVP_VerifyUpdate(context, data, length);
           (read in the signature)
           err = EVP_VerifyFinal(context, signature, signaturelength, verification_key);
           if (err != 1) {
           	failed...
           }
        */
        fprintf(stderr, "to be continued\n");
    }
    exit(status);
}
