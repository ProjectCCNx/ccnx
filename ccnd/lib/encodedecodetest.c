#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <dirent.h>

#include <ccn/ccn.h>

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
encode_message(struct ccn_charbuf *message, struct path * name_path, char *data, size_t len) {
    struct ccn_charbuf *path = NULL;
    struct ccn_charbuf *authenticator = ccn_charbuf_create();
    struct ccn_charbuf *signature = ccn_charbuf_create();
    int i;

    path = ccn_charbuf_create();
    if (path == NULL || ccn_name_init(path) == -1) {
	fprintf(stderr, "Failed to allocate or initialize content path\n");
	return -1;
    }

    for (i=0; i<name_path->count; i++) {
	ccn_name_append_str(path, name_path->comps[i]);
    }

    if (ccn_auth_create_default(authenticator, signature, CCN_CONTENT_FRAGMENT, path, name_path->count, data, len) != 0) {
	return -1;
    }
    return ccn_encode_ContentObject(message, path, authenticator, signature, data, len);
    
}

int 
decode_message(struct ccn_charbuf *message, struct path * name_path, char *data, size_t len) {
    struct ccn_parsed_ContentObject content;
    struct ccn_indexbuf *comps = ccn_indexbuf_create();
    const unsigned char * content_value;
    char * s = NULL;
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
	       comps->n-1, name_path->count);
	res = -1;
    }
    for (i=0; i<name_path->count; i++) {
	if (ccn_name_comp_strcmp(message->buf, comps, i, name_path->comps[i]) != 0) {
	    printf("Decode mismatch on path component %d\n", i);
	    res = -1;
	}
	s = ccn_name_comp_strdup(message->buf, comps, i);
	if (s == NULL || strcmp(s, name_path->comps[i]) != 0) {
	    printf("Decode mismatch on retrieved path component %d\n", i);
	}
	if (s != NULL) { free(s); s = NULL; }
    }
    if (ccn_content_get_value(message->buf, message->length, &content,
			      &content_value, &content_length) != 0) {
	printf("Cannot retrieve content value\n");
	res = -1;
    } else if (content_length != len) {
	printf("Decode mismatch on content length %d vs. %d\n", 
	       content_length, len);
	res = -1;
    } else if (memcmp(content_value, data, len) != 0) {
	printf("Decode mismatch of content\n");
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
}

int
main (int argc, char *argv[]) {
    struct ccn_charbuf *buffer;
    struct ccn_skeleton_decoder dd = {0};
    ssize_t res;
    char *outname;
    int fd;
    int result = 0;
    char * contents[] = {"INVITE sip:foo@parc.com SIP/2.0\nVia: SIP/2.0/UDP 127.0.0.1:5060;rport;branch=z9hG4bK519044721\nFrom: <sip:jthornto@13.2.117.52>;tag=2105643453\nTo: Test User <sip:foo@parc.com>\nCall-ID: 119424355@127.0.0.1\nCSeq: 20 INVITE\nContact: <sip:jthornto@127.0.0.1:5060>\nMax-Forwards: 70\nUser-Agent: Linphone-1.7.1/eXosip\nSubject: Phone call\nExpires: 120\nAllow: INVITE, ACK, CANCEL, BYE, OPTIONS, REFER, SUBSCRIBE, NOTIFY, MESSAGE\nContent-Type: application/sdp\nContent-Length:   448\n\nv=0\no=jthornto 123456 654321 IN IP4 127.0.0.1\ns=A conversation\nc=IN IP4 127.0.0.1\nt=0 0\nm=audio 7078 RTP/AVP 111 110 0 3 8 101\na=rtpmap:111 speex/16000/1\na=rtpmap:110 speex/8000/1\na=rtpmap:0 PCMU/8000/1\na=rtpmap:3 GSM/8000/1\na=rtpmap:8 PCMA/8000/1\na=rtpmap:101 telephone-event/8000\na=fmtp:101 0-11\nm=video 9078 RTP/AVP 97 98 99\na=rtpmap:97 theora/90000\na=rtpmap:98 H263-1998/90000\na=fmtp:98 CIF=1;QCIF=1\na=rtpmap:99 MP4V-ES/90000\n", 
 
			 "Quaer #%2d zjduer  badone", NULL};
    char * paths[] = { "/sip/protocol/parc.com/domain/foo/principal/invite/verb/119424355@127.0.0.1/id", 
		       "/d/e/f", NULL};
    struct path * cur_path = NULL;
    int i;

    if (argc == 3 && strcmp(argv[1], "-o") == 0) {
	outname = argv[2];
    } else {
	printf("Usage: %s -o <outfilename>\n", argv[0]);
	exit(1);
    }
    buffer = ccn_charbuf_create();
    printf("Encoding sample message data length %d\n", strlen(contents[0]));
    cur_path = path_create(paths[0]);
    if (encode_message(buffer, cur_path, contents[0], strlen(contents[0]))) {
	printf("Failed to encode message!\n");
    } else {
	printf("Encoded sample message length is %d\n", buffer->length);

	res = ccn_skeleton_decode(&dd, buffer->buf, buffer->length);
	if (!(res == buffer->length && dd.state == 0)) {
	    printf("Failed to decode!  Result %d State %d\n", res, dd.state);
	    result = 1;
	}
	fd = open(outname, O_WRONLY|O_CREAT|O_TRUNC, S_IRWXU);
	write(fd, buffer->buf, buffer->length);
	close(fd);
	if (decode_message(buffer, cur_path, contents[0], strlen(contents[0])) != 0) {
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
	if (encode_message(buffer, cur_path, contents[i], strlen(contents[i]))) {
	    printf("Failed encode\n");
            result = 1;
	} else if (decode_message(buffer, cur_path, contents[i], strlen(contents[i]))) {
	    printf("Failed decode\n");
            result = 1;
	}
	path_destroy(&cur_path);
	ccn_charbuf_destroy(&buffer);
    }
    
    /* Test the uri encode / decode routines */
    
    // XXX tested routines don't have a header file yest.
    extern int ccn_uri_append(struct ccn_charbuf *, const unsigned char *, size_t, int);
    extern int ccn_name_from_uri(struct ccn_charbuf *c, const char *uri);
    static const char *uri_tests[] = {
        "_+4", "ccn:/this/is/a/test",       "",     "ccn:/this/is/a/test",
        ".+4", "../test2?x=2",              "?x=2", "ccn:/this/is/a/test2",
        "_-X", "../test2?x=2",              "",     "",
        "_+2", "/missing/scheme",           "",     "ccn:/missing/scheme",
        ".+0", "../../../../../././#/",     "#/",   "ccn:",
        NULL, NULL, NULL, NULL
    };
    const char **u;
    struct ccn_charbuf *uri_out = ccn_charbuf_create();
    buffer = ccn_charbuf_create();
    for (u = uri_tests; *u != NULL; u++,u++,u++,u++,i++) {
        printf("Unit test case %d\n", i);
        if (u[0][0] != '.')
            buffer->length = 0;
        res = ccn_name_from_uri(buffer, u[1]);
        if (!expected_res(res, u[0][1])) {
            printf("Failed: ccn_name_from_uri wrong res %d\n", res);
            result = 1;
        }
        if (res >= 0) {
            if (res > strlen(u[1])) {
                printf("Failed: ccn_name_from_uri long res %d\n", res);
                result = 1;
            }
            else if (0 != strcmp(u[1] + res, u[2])) {
                printf("Failed: ccn_name_from_uri expecting leftover '%s', got '%s'\n", u[2], u[1] + res);
                result = 1;
            }
            uri_out->length = 0;
            res = ccn_uri_append(uri_out, buffer->buf, buffer->length, 1);
            if (!expected_res(res, u[0][2])) {
                printf("Failed: ccn_uri_append wrong res %d\n", res);
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
    exit(result);
}
