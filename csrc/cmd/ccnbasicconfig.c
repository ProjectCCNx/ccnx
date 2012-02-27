/**
 * @file ccnbasicconfig.c
 * Bring up a link to another ccnd.
 *
 * This is a very basic version that just registers a single prefix, possibly
 * creating a new face in the process.  Symbolic proto, host, and port are not
 * handled.  You might want to consider looking at ccndc instead.
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2009-2010 Palo Alto Research Center, Inc.
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
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/ccnd.h>
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
            "%s ccnx:/prefix/to/register proto host [port]\n"
            "   Bring up a link to another ccnd, registering a prefix\n",
            progname);
    exit(1);
}

#define CHKRES(resval) chkres((resval), __LINE__)

static void
chkres(int res, int lineno)
{
    if (res >= 0)
        return;
    fprintf(stderr, "failure at "
                    "ccnbasicconfig.c"
                    ":%d (res = %d)\n", lineno, res);
    exit(1);
}

int
main(int argc, char **argv)
{
    struct ccn *h = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *null_name = NULL;
    struct ccn_charbuf *name_prefix = NULL;
    struct ccn_charbuf *newface = NULL;
    struct ccn_charbuf *prefixreg = NULL;
    struct ccn_charbuf *resultbuf = NULL;
    struct ccn_charbuf *temp = NULL;
    struct ccn_charbuf *templ = NULL;
    const unsigned char *ptr = NULL;
    size_t length = 0;
    const char *arg = NULL;
    const char *progname = NULL;
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
    int opt;
    
    progname = argv[0];
    while ((opt = getopt(argc, argv, "h")) != -1) {
        switch (opt) {
            case 'h':
            default:
                usage(progname);
        }
    }

    /* Sanity check the URI and argument count */
    arg = argv[optind];
    if (arg == NULL)
        usage(progname);
    name = ccn_charbuf_create();
    res = ccn_name_from_uri(name, arg);
    if (res < 0) {
        fprintf(stderr, "%s: bad ccn URI: %s\n", progname, arg);
        exit(1);
    }
    if (argc - optind < 3 || argc - optind > 4)
        usage(progname);
    
    h = ccn_create();
    res = ccn_connect(h, NULL);
    if (res < 0) {
        ccn_perror(h, "ccn_connect");
        exit(1);
    }

    newface = ccn_charbuf_create();
    temp = ccn_charbuf_create();
    templ = ccn_charbuf_create();
    signed_info = ccn_charbuf_create();
    resultbuf = ccn_charbuf_create();
    name_prefix = ccn_charbuf_create();
    null_name = ccn_charbuf_create();
    CHKRES(ccn_name_init(null_name));

    keystore = ccn_keystore_create();
        
    face_instance->action = "newface";
    face_instance->descr.ipproto = atoi(argv[optind + 1]); // XXX - 6 = tcp or 17 = udp
    face_instance->descr.address = argv[optind + 2];
    face_instance->descr.port = argv[optind + 3];
    if (face_instance->descr.port == NULL)
        face_instance->descr.port = CCN_DEFAULT_UNICAST_PORT;
    face_instance->descr.mcast_ttl = -1;
    face_instance->lifetime = (~0U) >> 1;
    
    CHKRES(res = ccnb_append_face_instance(newface, face_instance));
    temp->length = 0;
    CHKRES(ccn_charbuf_putf(temp, "%s/.ccnx/.ccnx_keystore", getenv("HOME")));
    res = ccn_keystore_init(keystore,
                            ccn_charbuf_as_string(temp),
                            "Th1s1sn0t8g00dp8ssw0rd.");
    CHKRES(res);

    /* Construct a key locator containing the key itself */
    keylocator = ccn_charbuf_create();
    ccn_charbuf_append_tt(keylocator, CCN_DTAG_KeyLocator, CCN_DTAG);
    ccn_charbuf_append_tt(keylocator, CCN_DTAG_Key, CCN_DTAG);
    CHKRES(ccn_append_pubkey_blob(keylocator, ccn_keystore_public_key(keystore)));
    ccn_charbuf_append_closer(keylocator);	/* </Key> */
    ccn_charbuf_append_closer(keylocator);	/* </KeyLocator> */
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
                                   null_name,
                                   signed_info,
                                   newface->buf,
                                   newface->length,
                                   ccn_keystore_digest_algorithm(keystore),
                                   ccn_keystore_private_key(keystore));
    CHKRES(res);
    
    /* Set up our Interest template to indicate scope 1 */
        templ->length = 0;
        ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
        ccn_charbuf_append_tt(templ, CCN_DTAG_Name, CCN_DTAG);
        ccn_charbuf_append_closer(templ);	/* </Name> */
        ccnb_tagged_putf(templ, CCN_DTAG_Scope, "1");
        ccn_charbuf_append_closer(templ);	/* </Interest> */

    /* We need to figure out our local ccnd's CCIDID */
    name->length = 0;
    CHKRES(res = ccn_name_from_uri(name, "ccnx:/%C1.M.S.localhost/%C1.M.SRV/ccnd/KEY"));
    CHKRES(res = ccn_get(h, name, templ, 200, resultbuf, &pcobuf, NULL, 0));
    res = ccn_ref_tagged_BLOB(CCN_DTAG_PublisherPublicKeyDigest,
                        resultbuf->buf,
                        pcobuf.offset[CCN_PCO_B_PublisherPublicKeyDigest],
                        pcobuf.offset[CCN_PCO_E_PublisherPublicKeyDigest],
                        &ccndid, &ccndid_size);
    CHKRES(res);
    if (ccndid_size > sizeof(ccndid_storage))
        CHKRES(-1);
    memcpy(ccndid_storage, ccndid, ccndid_size);
    ccndid = ccndid_storage;
    
    /* Create the new face */
    CHKRES(ccn_name_init(name));
    CHKRES(ccn_name_append_str(name, "ccnx"));
    CHKRES(ccn_name_append(name, ccndid, ccndid_size));
    CHKRES(ccn_name_append(name, "newface", 7));
    CHKRES(ccn_name_append(name, temp->buf, temp->length));
    res = ccn_get(h, name, templ, 1000, resultbuf, &pcobuf, NULL, 0);
    if (res < 0) {
        fprintf(stderr, "no response from face creation request\n");
        exit(1);
    }
    ptr = resultbuf->buf;
    length = resultbuf->length;
    res = ccn_content_get_value(resultbuf->buf, resultbuf->length, &pcobuf, &ptr, &length);
    CHKRES(res);
    face_instance = ccn_face_instance_parse(ptr, length);
    if (face_instance == NULL)
        CHKRES(res = -1);
    CHKRES(face_instance->faceid);
    
    /* Finally, register the prefix */
    name_prefix->length = 0;
    CHKRES(ccn_name_from_uri(name_prefix, arg));
    forwarding_entry->action = "prefixreg";
    forwarding_entry->name_prefix = name_prefix;
    forwarding_entry->faceid = face_instance->faceid;
    forwarding_entry->lifetime = (~0U) >> 1;
    prefixreg = ccn_charbuf_create();
    CHKRES(res = ccnb_append_forwarding_entry(prefixreg, forwarding_entry));
    temp->length = 0;
    res = ccn_encode_ContentObject(temp,
                                   null_name,
                                   signed_info,
                                   prefixreg->buf,
                                   prefixreg->length,
                                   ccn_keystore_digest_algorithm(keystore),
                                   ccn_keystore_private_key(keystore));
    CHKRES(res);
    CHKRES(ccn_name_init(name));
    CHKRES(ccn_name_append_str(name, "ccnx"));
    CHKRES(ccn_name_append(name, ccndid, ccndid_size));
    CHKRES(ccn_name_append_str(name, "prefixreg"));
    CHKRES(ccn_name_append(name, temp->buf, temp->length));
    res = ccn_get(h, name, templ, 1000, resultbuf, &pcobuf, NULL, 0);
    if (res < 0) {
        fprintf(stderr, "no response from prefix registration request\n");
        exit(1);
    }
    fprintf(stderr, "Prefix %s will be forwarded to face %d\n", arg, face_instance->faceid);

    /* We're about to exit, so don't bother to free everything. */
    ccn_destroy(&h);
    exit(res < 0);
}
