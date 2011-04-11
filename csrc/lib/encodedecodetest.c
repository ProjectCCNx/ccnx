/**
 * @file encodedecodetest.c
 * Unit tests for CCNx C library.
 *
 * A CCNx program.
 *
 * Copyright (C) 2009-2011 Palo Alto Research Center, Inc.
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
#include <unistd.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>

#include <ccn/ccn.h>
#include <ccn/bloom.h>
#include <ccn/uri.h>
#include <ccn/digest.h>
#include <ccn/keystore.h>
#include <ccn/signing.h>
#include <ccn/random.h>

struct path {
    int count;
    char * comps[];
};
struct path * path_create(char * strpath) {
    char * p = strpath;
    int slash_count = 0;
    int i = 0;
    struct path * path;

    if (strlen(strpath) < 1) {
	return NULL;
    }
    while (*p != '\0') {
	if (*p++ == '/') slash_count++;
    }
    path = malloc(sizeof(int) + sizeof(char *)*(slash_count+1));
    path->comps[0] = strtok(strdup(strpath), "/");
    path->count = 0;
    while (path->comps[i++]) {
	path->comps[i] = strtok(NULL, "/");
	path->count++;
    }
    return path;
}
void path_destroy(struct path **path) {
    free(*path);
    *path = NULL;
}

int 
encode_message(struct ccn_charbuf *message, struct path * name_path, char *data, size_t len, struct ccn_charbuf *signed_info, const void *pkey) {
    struct ccn_charbuf *path = ccn_charbuf_create();
    int i;
    int res;

    if (path == NULL || ccn_name_init(path) == -1) {
	fprintf(stderr, "Failed to allocate or initialize content path\n");
	return -1;
    }

    for (i = 0; i < name_path->count; i++) {
	ccn_name_append_str(path, name_path->comps[i]);
    }

    res = ccn_encode_ContentObject(message, path, signed_info, data, len, NULL, pkey);

    if (res != 0) {
	fprintf(stderr, "Failed to encode ContentObject\n");
    }

    ccn_charbuf_destroy(&path);
    return (res);
}

int 
decode_message(struct ccn_charbuf *message, struct path * name_path, char *data, size_t len, const void *verkey) {
    struct ccn_parsed_ContentObject content;
    struct ccn_indexbuf *comps = ccn_indexbuf_create();
    const unsigned char * content_value;
    size_t content_length;
    
    int res = 0;
    int i;
    
    memset(&content, 0x33, sizeof(content));

    if (ccn_parse_ContentObject(message->buf, message->length, &content, comps) != 0) {
	printf("Decode failed to parse object\n");
	res = -1;
    }

    if (comps->n-1 != name_path->count) {
	printf("Decode got wrong number of path components: %d vs. %d\n", 
	       (int)(comps->n-1), name_path->count);
	res = -1;
    }
    for (i=0; i<name_path->count; i++) {
	if (ccn_name_comp_strcmp(message->buf, comps, i, name_path->comps[i]) != 0) {
	    printf("Decode mismatch on path component %d\n", i);
	    res = -1;
	}
    }
    if (ccn_content_get_value(message->buf, message->length, &content,
			      &content_value, &content_length) != 0) {
	printf("Cannot retrieve content value\n");
	res = -1;
    } else if (content_length != len) {
	printf("Decode mismatch on content length %d vs. %d\n", 
	       (int)content_length, (int)len);
	res = -1;
    } else if (memcmp(content_value, data, len) != 0) {
	printf("Decode mismatch of content\n");
	res = -1;
    }

    if (ccn_verify_signature(message->buf, message->length, &content, verkey) != 1) {
        printf("Signature did not verify\n");
        res = -1;
    }

    ccn_indexbuf_destroy(&comps);
    return res;
    
}

int
expected_res(int res, char code)
{
    if (code == '*')
        return(1);
    if (code == '-')
        return(res < 0);
    if (code == '+')
        return(res > 0);
    if ('0' <= code && code <= '9')
        return(res == (code - '0'));
    abort(); // test program bug!!!
    /* NOTREACHED */
}

static char all_chars_percent_encoded[256 * 3 + 1]; /* Computed */

static void init_all_chars_percent_encoded(void) {
    struct ccn_charbuf *c;
    int i;
    c = ccn_charbuf_create();
    for (i = 0; i < 256; i+=2) {
        ccn_charbuf_putf(c, "%%%02x%%%02X", i, i+1);
    }
    if (c->length >= sizeof(all_chars_percent_encoded))
        c->length = sizeof(all_chars_percent_encoded) - 1;
    memcpy(all_chars_percent_encoded, c->buf, c->length);
    ccn_charbuf_destroy(&c);
}

static const char *all_chars_percent_encoded_canon =
 "ccnx:/"
 "%00%01%02%03%04%05%06%07%08%09%0A%0B%0C%0D%0E%0F"
 "%10%11%12%13%14%15%16%17%18%19%1A%1B%1C%1D%1E%1F"
 "%20%21%22%23%24%25%26%27%28%29%2A%2B%2C-.%2F"
 "0123456789%3A%3B%3C%3D%3E%3F"
 "%40ABCDEFGHIJKLMNOPQRSTUVWXYZ%5B%5C%5D%5E_"
 "%60abcdefghijklmnopqrstuvwxyz%7B%7C%7D~%7F"
 "%80%81%82%83%84%85%86%87%88%89%8A%8B%8C%8D%8E%8F"
 "%90%91%92%93%94%95%96%97%98%99%9A%9B%9C%9D%9E%9F"
 "%A0%A1%A2%A3%A4%A5%A6%A7%A8%A9%AA%AB%AC%AD%AE%AF"
 "%B0%B1%B2%B3%B4%B5%B6%B7%B8%B9%BA%BB%BC%BD%BE%BF"
 "%C0%C1%C2%C3%C4%C5%C6%C7%C8%C9%CA%CB%CC%CD%CE%CF"
 "%D0%D1%D2%D3%D4%D5%D6%D7%D8%D9%DA%DB%DC%DD%DE%DF"
 "%E0%E1%E2%E3%E4%E5%E6%E7%E8%E9%EA%EB%EC%ED%EE%EF"
 "%F0%F1%F2%F3%F4%F5%F6%F7%F8%F9%FA%FB%FC%FD%FE%FF";

int
main (int argc, char *argv[]) {
    struct ccn_charbuf *buffer = ccn_charbuf_create();
    struct ccn_charbuf *signed_info = ccn_charbuf_create();
    struct ccn_skeleton_decoder dd = {0};
    ssize_t res;
    char *outname = NULL;
    int fd;
    int result = 0;
    char * contents[] = {"INVITE sip:foo@parc.com SIP/2.0\nVia: SIP/2.0/UDP 127.0.0.1:5060;rport;branch=z9hG4bK519044721\nFrom: <sip:jthornto@13.2.117.52>;tag=2105643453\nTo: Test User <sip:foo@parc.com>\nCall-ID: 119424355@127.0.0.1\nCSeq: 20 INVITE\nContact: <sip:jthornto@127.0.0.1:5060>\nMax-Forwards: 70\nUser-Agent: Linphone-1.7.1/eXosip\nSubject: Phone call\nExpires: 120\nAllow: INVITE, ACK, CANCEL, BYE, OPTIONS, REFER, SUBSCRIBE, NOTIFY, MESSAGE\nContent-Type: application/sdp\nContent-Length:   448\n\nv=0\no=jthornto 123456 654321 IN IP4 127.0.0.1\ns=A conversation\nc=IN IP4 127.0.0.1\nt=0 0\nm=audio 7078 RTP/AVP 111 110 0 3 8 101\na=rtpmap:111 speex/16000/1\na=rtpmap:110 speex/8000/1\na=rtpmap:0 PCMU/8000/1\na=rtpmap:3 GSM/8000/1\na=rtpmap:8 PCMA/8000/1\na=rtpmap:101 telephone-event/8000\na=fmtp:101 0-11\nm=video 9078 RTP/AVP 97 98 99\na=rtpmap:97 theora/90000\na=rtpmap:98 H263-1998/90000\na=fmtp:98 CIF=1;QCIF=1\na=rtpmap:99 MP4V-ES/90000\n", 
 
			 "Quaer #%2d zjduer  badone",
                         "",
                         NULL};
    char * paths[] = { "/sip/protocol/parc.com/domain/foo/principal/invite/verb/119424355@127.0.0.1/id", 
		       "/d/e/f",
                       "/zero/length/content",
                       NULL};
    struct path * cur_path = NULL;
    struct ccn_keystore *keystore = ccn_keystore_create();
    char *home = getenv("HOME");
    char *keystore_suffix = "/.ccnx/.ccnx_keystore";
    char *keystore_name = NULL;

    int i;

    if (argc == 3 && strcmp(argv[1], "-o") == 0) {
	outname = argv[2];
    } else {
	printf("Usage: %s -o <outfilename>\n", argv[0]);
	exit(1);
    }

    if (home == NULL) {
        printf("Unable to determine home directory for keystore\n");
        exit(1);
    }
    keystore_name = calloc(1, strlen(home) + strlen(keystore_suffix) + 1);
    
    strcat(keystore_name, home);
    strcat(keystore_name, keystore_suffix);

    if (0 != ccn_keystore_init(keystore, keystore_name, "Th1s1sn0t8g00dp8ssw0rd.")) {
        printf("Failed to initialize keystore\n");
        exit(1);
    }

    printf("Creating signed_info\n");
    res = ccn_signed_info_create(signed_info,
                                 /*pubkeyid*/ccn_keystore_public_key_digest(keystore),
                                 /*publisher_key_id_size*/ccn_keystore_public_key_digest_length(keystore),
                                 /*datetime*/NULL,
                                 /*type*/CCN_CONTENT_GONE,
                                 /*freshness*/ 42,
                                 /*finalblockid*/NULL,
                                 /*keylocator*/NULL);
    if (res < 0) {
        printf("Failed to create signed_info!\n");
    }
    
    res = ccn_skeleton_decode(&dd, signed_info->buf, signed_info->length);
    if (!(res == signed_info->length && dd.state == 0)) {
        printf("Failed to decode signed_info!  Result %d State %d\n", (int)res, dd.state);
        result = 1;
    }
    memset(&dd, 0, sizeof(dd));
    printf("Done with signed_info\n");

    printf("Encoding sample message data length %d\n", (int)strlen(contents[0]));
    cur_path = path_create(paths[0]);
    if (encode_message(buffer, cur_path, contents[0], strlen(contents[0]), signed_info, ccn_keystore_private_key(keystore))) {
	printf("Failed to encode message!\n");
    } else {
	printf("Encoded sample message length is %d\n", (int)buffer->length);

	res = ccn_skeleton_decode(&dd, buffer->buf, buffer->length);
	if (!(res == buffer->length && dd.state == 0)) {
	    printf("Failed to decode!  Result %d State %d\n", (int)res, dd.state);
	    result = 1;
	}
        if (outname != NULL) {
            fd = open(outname, O_WRONLY|O_CREAT|O_TRUNC, S_IRWXU);
            if (fd == -1)
                perror(outname);
            res = write(fd, buffer->buf, buffer->length);
            close(fd);
	}
        if (decode_message(buffer, cur_path, contents[0], strlen(contents[0]), ccn_keystore_public_key(keystore)) != 0) {
	    result = 1;
	}
        printf("Expect signature verification failure: ");
        if (buffer->length >= 20)
            buffer->buf[buffer->length - 20] += 1;
	if (decode_message(buffer, cur_path, contents[0], strlen(contents[0]), ccn_keystore_public_key(keystore)) == 0) {
	    result = 1;
	}
    }
    path_destroy(&cur_path);
    ccn_charbuf_destroy(&buffer);
    printf("Done with sample message\n");
    
    /* Now exercise as unit tests */
    
    for (i = 0; paths[i] != NULL && contents[i] != NULL; i++) {
	printf("Unit test case %d\n", i);
	cur_path = path_create(paths[i]);
	buffer = ccn_charbuf_create();
	if (encode_message(buffer, cur_path, contents[i], strlen(contents[i]), signed_info, ccn_keystore_private_key(keystore))) {
	    printf("Failed encode\n");
            result = 1;
	} else if (decode_message(buffer, cur_path, contents[i], strlen(contents[i]), ccn_keystore_public_key(keystore))) {
	    printf("Failed decode\n");
            result = 1;
	}
	path_destroy(&cur_path);
	ccn_charbuf_destroy(&buffer);
    }
    
    /* Test the uri encode / decode routines */
        
    init_all_chars_percent_encoded();
    const char *uri_tests[] = {
        "_+4", "ccnx:/this/is/a/test",       "",     "ccnx:/this/is/a/test",
        ".+4", "../test2?x=2",              "?x=2", "ccnx:/this/is/a/test2",
        "_-X", "../should/error",           "",     "",
        "_+2", "/missing/scheme",           "",     "ccnx:/missing/scheme",
        ".+0", "../../../../../././#/",     "#/",   "ccnx:/",
        ".+1", all_chars_percent_encoded,   "",     all_chars_percent_encoded_canon,
        "_+1", all_chars_percent_encoded_canon, "", all_chars_percent_encoded_canon,
        ".+4", "ccnx:/.../.%2e./...././.....///?...", "?...", "ccnx:/.../.../..../.....",
        "_-X", "/%3G?bad-pecent-encode",    "",     "",
        "_-X", "/%3?bad-percent-encode",    "",     "",
        "_-X", "/%#bad-percent-encode",    "",     "",
        "_+3", "ccnx://joe@example.com:42/ignore/host/part of uri", "", "ccnx:/ignore/host/part%20of%20uri",
        NULL, NULL, NULL, NULL
    };
    const char **u;
    struct ccn_charbuf *uri_out = ccn_charbuf_create();
    buffer = ccn_charbuf_create();
    for (u = uri_tests; *u != NULL; u += 4, i++) {
        printf("Unit test case %d\n", i);
        if (u[0][0] != '.')
            buffer->length = 0;
        res = ccn_name_from_uri(buffer, u[1]);
        if (!expected_res(res, u[0][1])) {
            printf("Failed: ccn_name_from_uri wrong res %d\n", (int)res);
            result = 1;
        }
        if (res >= 0) {
            if (res > strlen(u[1])) {
                printf("Failed: ccn_name_from_uri long res %d\n", (int)res);
                result = 1;
            }
            else if (0 != strcmp(u[1] + res, u[2])) {
                printf("Failed: ccn_name_from_uri expecting leftover '%s', got '%s'\n", u[2], u[1] + res);
                result = 1;
            }
            uri_out->length = 0;
            res = ccn_uri_append(uri_out, buffer->buf, buffer->length, 1);
            if (!expected_res(res, u[0][2])) {
                printf("Failed: ccn_uri_append wrong res %d\n", (int)res);
                result = 1;
            }
            if (res >= 0) {
                if (uri_out->length != strlen(u[3])) {
                    printf("Failed: ccn_uri_append produced wrong number of characters\n");
                    result = 1;
                }
                ccn_charbuf_reserve(uri_out, 1)[0] = 0;
                if (0 != strcmp((const char *)uri_out->buf, u[3])) {
                    printf("Failed: ccn_uri_append produced wrong output\n");
                    printf("Expected: %s\n", u[3]);
                    printf("  Actual: %s\n", (const char *)uri_out->buf);
                    result = 1;
                }
            }
        }
    }
    ccn_charbuf_destroy(&buffer);
    ccn_charbuf_destroy(&uri_out);
    printf("Name marker tests\n");
    do {
        const char *expected_uri = "ccnx:/example.com/.../%01/%FE/%01%02%03%04%05%06%07%08/%FD%10%10%10%10%1F%FF/%00%81";
        const char *expected_chopped_uri = "ccnx:/example.com/.../%01/%FE";
        const char *expected_bumped_uri = "ccnx:/example.com/.../%01/%FF";
        const char *expected_bumped2_uri = "ccnx:/example.com/.../%01/%00%00";

        printf("Unit test case %d\n", i++);
        buffer = ccn_charbuf_create();
        uri_out = ccn_charbuf_create();
        res = ccn_name_init(buffer);
        res |= ccn_name_append_str(buffer, "example.com");
        res |= ccn_name_append_numeric(buffer, CCN_MARKER_NONE, 0);
        res |= ccn_name_append_numeric(buffer, CCN_MARKER_NONE, 1);
        res |= ccn_name_append_numeric(buffer, 0xFE, 0);
        res |= ccn_name_append_numeric(buffer, CCN_MARKER_NONE, 0x0102030405060708ULL);
        res |= ccn_name_append_numeric(buffer, CCN_MARKER_VERSION, 0x101010101FFFULL);
        res |= ccn_name_append_numeric(buffer, CCN_MARKER_SEQNUM, 129);
        res |= ccn_uri_append(uri_out, buffer->buf, buffer->length, 1);
        if (res < 0) {
            printf("Failed: name marker tests had negative res\n");
            result = 1;
        }
        if (0 != strcmp(ccn_charbuf_as_string(uri_out), expected_uri)) {
            printf("Failed: name marker tests produced wrong output\n");
            printf("Expected: %s\n", expected_uri);
            printf("  Actual: %s\n", (const char *)uri_out->buf);
            result = 1;
        }
        res = ccn_name_chop(buffer, NULL, 100);
        if (res != -1) {
            printf("Failed: ccn_name_chop did not produce error \n");
            result = 1;
        }
        res = ccn_name_chop(buffer, NULL, 4);
        if (res != 4) {
            printf("Failed: ccn_name_chop got wrong length\n");
            result = 1;
        }
        uri_out->length = 0;
        ccn_uri_append(uri_out, buffer->buf, buffer->length, 1);
        if (0 != strcmp(ccn_charbuf_as_string(uri_out), expected_chopped_uri)) {
            printf("Failed: ccn_name_chop botch\n");
            printf("Expected: %s\n", expected_chopped_uri);
            printf("  Actual: %s\n", (const char *)uri_out->buf);
            result = 1;
        }
        res = ccn_name_next_sibling(buffer);
        if (res != 4) {
            printf("Failed: ccn_name_next_sibling got wrong length\n");
            result = 1;
        }
        uri_out->length = 0;
        ccn_uri_append(uri_out, buffer->buf, buffer->length, 1);
        if (0 != strcmp(ccn_charbuf_as_string(uri_out), expected_bumped_uri)) {
            printf("Failed: ccn_name_next_sibling botch\n");
            printf("Expected: %s\n", expected_bumped_uri);
            printf("  Actual: %s\n", (const char *)uri_out->buf);
            result = 1;
        }
        ccn_name_next_sibling(buffer);
        uri_out->length = 0;
        ccn_uri_append(uri_out, buffer->buf, buffer->length, 1);
        if (0 != strcmp(ccn_charbuf_as_string(uri_out), expected_bumped2_uri)) {
            printf("Failed: ccn_name_next_sibling botch\n");
            printf("Expected: %s\n", expected_bumped2_uri);
            printf("  Actual: %s\n", (const char *)uri_out->buf);
            result = 1;
        }
        ccn_charbuf_destroy(&buffer);
        ccn_charbuf_destroy(&uri_out);
    } while (0);
    printf("Message digest tests\n");
    do {
        printf("Unit test case %d\n", i++);
        struct ccn_digest *dg = ccn_digest_create(CCN_DIGEST_SHA256);
        if (dg == NULL) {
            printf("Failed: ccn_digest_create returned NULL\n");
            result = 1;
            break;
        }
        printf("Unit test case %d\n", i++);
        const unsigned char expected_digest[] = {
            0xb3, 0x82, 0xcd, 0xb0, 0xe9, 0x5d, 0xf7, 0x3b, 0xe7, 0xdc, 0x19, 0x81, 0x3a, 0xfd, 0xdf, 0x89, 0xfb, 0xd4, 0xd4, 0xa0, 0xdb, 0x11, 0xa6, 0xba, 0x24, 0x16, 0x5b, 0xad, 0x9d, 0x90, 0x72, 0xb0
        };
        unsigned char actual_digest[sizeof(expected_digest)] = {0};
        const char *data = "Content-centric";
        if (ccn_digest_size(dg) != sizeof(expected_digest)) {
            printf("Failed: wrong digest size\n");
            result = 1;
            break;
        }
        printf("Unit test case %d\n", i++);
        ccn_digest_init(dg);
        res = ccn_digest_update(dg, data, strlen(data));
        if (res != 0)
            printf("Warning: check res %d\n", (int)res);
        printf("Unit test case %d\n", i++);
        res = ccn_digest_final(dg, actual_digest, sizeof(expected_digest));
        if (res != 0)
            printf("Warning: check res %d\n", (int)res);
        if (0 != memcmp(actual_digest, expected_digest, sizeof(expected_digest))) {
            printf("Failed: wrong digest\n");
            result = 1;
            break;
        }
    } while (0);
    printf("Really basic PRNG test\n");
    do {
        unsigned char r1[42];
        unsigned char r2[42];
        printf("Unit test case %d\n", i++);
        ccn_add_entropy(&i, sizeof(i), 0); /* Not much entropy, really. */
        ccn_random_bytes(r1, sizeof(r1));
        memcpy(r2, r1, sizeof(r2));
        ccn_random_bytes(r2, sizeof(r2));
        if (0 == memcmp(r1, r2, sizeof(r2))) {
            printf("Failed: badly broken PRNG\n");
            result = 1;
            break;
        }
    } while (0);
    printf("Bloom filter tests\n");
    do {
        unsigned char seed1[4] = "1492";
        const char *a[13] = {
            "one", "two", "three", "four",
            "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve",
            "thirteen"
        };
        struct ccn_bloom *b1 = NULL;
        struct ccn_bloom *b2 = NULL;
        int j, k, t1, t2;
        unsigned short us;
        
        printf("Unit test case %d\n", i++);
        b1 = ccn_bloom_create(13, seed1);
        
        for (j = 0; j < 13; j++)
            if (ccn_bloom_match(b1, a[j], strlen(a[j]))) break;
        if (j < 13) {
            printf("Failed: \"%s\" matched empty Bloom filter\n", a[j]);
            result = 1;
            break;
        }
        printf("Unit test case %d\n", i++);
        for (j = 0; j < 13; j++)
            ccn_bloom_insert(b1, a[j], strlen(a[j]));
        for (j = 0; j < 13; j++)
            if (!ccn_bloom_match(b1, a[j], strlen(a[j]))) break;
        if (j < 13) {
            printf("Failed: \"%s\" not found when it should have been\n", a[j]);
            result = 1;
            break;
        }
        printf("Unit test case %d\n", i++);
        for (j = 0, k = 0; j < 13; j++)
            if (ccn_bloom_match(b1, a[j]+1, strlen(a[j]+1)))
                k++;
        if (k > 0) {
            printf("Mmm, found %d false positives\n", k);
            if (k > 2) {
                result = 1;
                break;
            }
        }
        unsigned char seed2[5] = "aqfb\0";
        for (; seed2[3] <= 'f'; seed2[3]++) {
            printf("Unit test case %d (%4s)    ", i++, seed2);
            b2 = ccn_bloom_create(13, seed2);
            for (j = 0; j < 13; j++)
                ccn_bloom_insert(b2, a[j], strlen(a[j]));
            for (j = 0, k = 0, us = ~0; us > 0; us--) {
                t1 = ccn_bloom_match(b1, &us, sizeof(us));
                t2 = ccn_bloom_match(b2, &us, sizeof(us));
                j += (t1 | t2);
                k += (t1 & t2);
            }
            printf("either=%d both=%d wiresize=%d\n", j, k, ccn_bloom_wiresize(b1));
            if (k > 12) {
                printf("Failed: Bloom seeding may not be effective\n");
                result = 1;
            }
            ccn_bloom_destroy(&b2);
        }
        ccn_bloom_destroy(&b1);
    } while (0);
    printf("ccn_sign_content() tests\n");
    do {
        struct ccn *h = ccn_create();
        struct ccn_charbuf *co = ccn_charbuf_create();
        struct ccn_signing_params sparm = CCN_SIGNING_PARAMS_INIT;
        struct ccn_parsed_ContentObject pco = {0};
        struct ccn_charbuf *name = ccn_charbuf_create();
        
        printf("Unit test case %d\n", i++);
        ccn_name_from_uri(name, "ccnx:/test/data/%00%42");
        res = ccn_sign_content(h, co, name, NULL, "DATA", 4);
        if (res != 0) {
            printf("Failed: res == %d\n", (int)res);
            result = 1;
        }
        sparm.template_ccnb = ccn_charbuf_create();
        res = ccn_parse_ContentObject(co->buf, co->length, &pco, NULL);
        if (res != 0) {
            printf("Failed: ccn_parse_ContentObject res == %d\n", (int)res);
            result = 1;
            break;
        }
        ccn_charbuf_append(sparm.template_ccnb,
            co->buf + pco.offset[CCN_PCO_B_SignedInfo],
            pco.offset[CCN_PCO_E_SignedInfo] - pco.offset[CCN_PCO_B_SignedInfo]);
        sparm.sp_flags = CCN_SP_TEMPL_TIMESTAMP;
        printf("Unit test case %d\n", i++);
        res = ccn_sign_content(h, co, name, &sparm, "DATA", 4);
        if (res != 0) {
            printf("Failed: res == %d\n", (int)res);
            result = 1;
        }
        printf("Unit test case %d\n", i++);
        sparm.sp_flags = -1;
        res = ccn_sign_content(h, co, name, &sparm, "DATA", 4);
        if (res != -1) {
            printf("Failed: res == %d\n", (int)res);
            result = 1;
        }
        ccn_charbuf_destroy(&name);
        ccn_charbuf_destroy(&sparm.template_ccnb);
        ccn_charbuf_destroy(&co);
        ccn_destroy(&h);
    } while (0);
    exit(result);
}
