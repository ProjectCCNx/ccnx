/**
 * @file ccndc.c
 * Bring up a link to another ccnd.
 *
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>
#include <ccn/face_mgmt.h>
#include <ccn/reg_mgmt.h>
#include <ccn/sockcreate.h>
#include <ccn/signing.h>
#include <ccn/keystore.h>

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s -f configfile\n"
            "   Read configfile and bring up links to other ccnd processes",
            progname);
    exit(1);
}

#define WITH_ERROR_CHECK(resval) chkres((resval), __LINE__)

static void
chkres(int res, int lineno)
{
    if (res >= 0)
        return;
    fprintf(stderr, "failure at "
                    "ccndc.c"
                    ":%d (res = %d)\n", lineno, res);
    exit(1);
}

int
main(int argc, char **argv)
{
    struct ccn *h = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *name_prefix = NULL;
    struct ccn_charbuf *newface = NULL;
    struct ccn_charbuf *prefixreg = NULL;
    struct ccn_charbuf *resultbuf = NULL;
    struct ccn_charbuf *temp = NULL;
    struct ccn_charbuf *templat = NULL;
    const unsigned char *ptr = NULL;
    size_t length = 0;
    const char *progname = NULL;
    const char *configfile = NULL;
    struct ccn_parsed_ContentObject pcobuf = {0};
    struct ccn_face_instance face_instance_storage = {0};
    struct ccn_face_instance *face_instance = &face_instance_storage;
    struct ccn_forwarding_entry forwarding_entry_storage = {0};
    struct ccn_forwarding_entry *forwarding_entry = &forwarding_entry_storage;
    struct ccn_charbuf *signed_info = NULL;
    struct ccn_charbuf *keylocator = NULL;
    struct ccn_keystore *keystore = NULL;
    long expire = -1;
    unsigned char ccndid_storage[32] = {0};
    const unsigned char *ccndid = NULL;
    size_t ccndid_size = 0;
    int res;
    char ch;
    
    progname = argv[0];
    while ((ch = getopt(argc, argv, "hf:")) != -1) {
        switch (ch) {
            case 'f':
                configfile = optarg;
                break;
            case 'h':
            default:
                usage(progname);
        }
    }

    name = ccn_charbuf_create();
    WITH_ERROR_CHECK(ccn_name_from_uri(name, arg));
    if (argc - optind < 3 || argc - optind > 4)
        usage(progname);
    
    h = ccn_create();
    res = ccn_connect(h, NULL);
    if (res < 0) {
        ccn_perror(h, "ccn_connect");
        exit(1);
    }

    newface = ccn_charbuf_create();
    name = ccn_charbuf_create();
    temp = ccn_charbuf_create();
    templat = ccn_charbuf_create();
    signed_info = ccn_charbuf_create();
    resultbuf = ccn_charbuf_create();
    name_prefix = ccn_charbuf_create();

    keystore = ccn_keystore_create();
        
    face_instance->action = "newface";
    face_instance->descr.ipproto = atoi(argv[optind + 1]); // XXX - 6 = tcp or 17 = udp
    face_instance->descr.address = argv[optind + 2];
    face_instance->descr.port = argv[optind + 3];
    if (face_instance->descr.port == NULL)
        face_instance->descr.port = "4485";
    face_instance->descr.mcast_ttl = -1;
    face_instance->lifetime = (~0U) >> 1;
    
    WITH_ERROR_CHECK(res = ccnb_append_face_instance(newface, face_instance));
    temp->length = 0;
    WITH_ERROR_CHECK(ccn_charbuf_putf(temp, "%s/.ccn/.ccn_keystore", getenv("HOME")));
    res = ccn_keystore_init(keystore,
                            ccn_charbuf_as_string(temp),
                            "Th1s1sn0t8g00dp8ssw0rd.");
    WITH_ERROR_CHECK(res);
    WITH_ERROR_CHECK(ccn_name_init(name));
    /* Construct a key locator containing the key itself */
    keylocator = ccn_charbuf_create();
    ccn_charbuf_append_tt(keylocator, CCN_DTAG_KeyLocator, CCN_DTAG);
    ccn_charbuf_append_tt(keylocator, CCN_DTAG_Key, CCN_DTAG);
    WITH_ERROR_CHECK(res = ccn_append_pubkey_blob(keylocator, ccn_keystore_public_key(keystore)));
    ccn_charbuf_append_closer(keylocator);	/* </Key> */
    ccn_charbuf_append_closer(keylocator);	/* </KeyLocator> */
    signed_info->length = 0;
    res = ccn_signed_info_create(signed_info,
                                 /* pubkeyid */ ccn_keystore_public_key_digest(keystore),
                                 /* publisher_key_id_size */ ccn_keystore_public_key_digest_length(keystore),
                                 /* datetime */ NULL,
                                 /* type */ CCN_CONTENT_DATA,
                                 /* freshness */ expire,
                                 /* finalblockid */ NULL,
                                 keylocator);
    if (res < 0) {
        fprintf(stderr, "Failed to create signed_info (res == %d)\n", res);
        exit(1);
    }
    
    temp->length = 0;
    res = ccn_encode_ContentObject(temp,
                                   name,
                                   signed_info,
                                   newface->buf,
                                   newface->length,
                                   NULL,
                                   ccn_keystore_private_key(keystore));
    WITH_ERROR_CHECK(res);
    
    /* Set up our Interest templatate to indicate scope 1 */
        templat->length = 0;
        ccn_charbuf_append_tt(templat, CCN_DTAG_Interest, CCN_DTAG);
        ccn_charbuf_append_tt(templat, CCN_DTAG_Name, CCN_DTAG);
        ccn_charbuf_append_closer(templat);	/* </Name> */
        ccnb_tagged_putf(templat, CCN_DTAG_Scope, "1");
        ccn_charbuf_append_closer(templat);	/* </Interest> */

    /* We need to figure out our local ccnd's CCIDID */
    name->length = 0;
    WITH_ERROR_CHECK(res = ccn_name_from_uri(name, "ccn:/ccn/ping/XXX")); // XXX - ideally use a nonce instead
    WITH_ERROR_CHECK(res = ccn_get(h, name, -1, templat, 200, resultbuf, &pcobuf, NULL));
    res = ccn_ref_tagged_BLOB(CCN_DTAG_PublisherPublicKeyDigest,
                        resultbuf->buf,
                        pcobuf.offset[CCN_PCO_B_PublisherPublicKeyDigest],
                        pcobuf.offset[CCN_PCO_E_PublisherPublicKeyDigest],
                        &ccndid, &ccndid_size);
    WITH_ERROR_CHECK(res);
    if (ccndid_size > sizeof(ccndid_storage))
        WITH_ERROR_CHECK(-1);
    memcpy(ccndid_storage, ccndid, ccndid_size);
    ccndid = ccndid_storage;
    
    /* Create the new face */
    WITH_ERROR_CHECK(ccn_name_init(name));
    WITH_ERROR_CHECK(ccn_name_append(name, "ccn", 3));
    WITH_ERROR_CHECK(ccn_name_append(name, ccndid, ccndid_size));
    WITH_ERROR_CHECK(ccn_name_append(name, "newface", 7));
    WITH_ERROR_CHECK(ccn_name_append(name, temp->buf, temp->length));
    res = ccn_get(h, name, -1, templat, 1000, resultbuf, &pcobuf, NULL);
    if (res < 0) {
        fprintf(stderr, "no response from face creation request\n");
        exit(1);
    }
    ptr = resultbuf->buf;
    length = resultbuf->length;
    res = ccn_content_get_value(resultbuf->buf, resultbuf->length, &pcobuf, &ptr, &length);
    WITH_ERROR_CHECK(res);
    face_instance = ccn_face_instance_parse(ptr, length);
    if (face_instance == NULL)
        WITH_ERROR_CHECK(res = -1);
    WITH_ERROR_CHECK(face_instance->faceid);
    
    /* Finally, register the prefix */
    name_prefix->length = 0;
    WITH_ERROR_CHECK(ccn_name_from_uri(name_prefix, arg));
    forwarding_entry->action = "prefixreg";
    forwarding_entry->name_prefix = name_prefix;
    forwarding_entry->faceid = face_instance->faceid;
    forwarding_entry->lifetime = (~0U) >> 1;
    prefixreg = ccn_charbuf_create();
    WITH_ERROR_CHECK(res = ccnb_append_forwarding_entry(prefixreg, forwarding_entry));
    temp->length = 0;
    res = ccn_encode_ContentObject(temp,
                                   name,
                                   signed_info,
                                   prefixreg->buf,
                                   prefixreg->length,
                                   NULL,
                                   ccn_keystore_private_key(keystore));
    WITH_ERROR_CHECK(res);
        WITH_ERROR_CHECK(ccn_name_init(name));
    WITH_ERROR_CHECK(ccn_name_append(name, "ccn", 3));
    WITH_ERROR_CHECK(ccn_name_append(name, ccndid, ccndid_size));
    WITH_ERROR_CHECK(ccn_name_append_str(name, "prefixreg"));
    WITH_ERROR_CHECK(ccn_name_append(name, temp->buf, temp->length));
    res = ccn_get(h, name, -1, templat, 1000, resultbuf, &pcobuf, NULL);
    if (res < 0) {
        fprintf(stderr, "no response from prefix registration request\n");
        exit(1);
    }
    fprintf(stderr, "Prefix %s will be forwarded to face %d\n", arg, face_instance->faceid);

    /* We're about to exit, so don't bother to free everything. */
    ccn_destroy(&h);
    exit(res < 0);
}
