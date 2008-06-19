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
encode_message(struct ccn_charbuf *message, struct path * name_path, unsigned char *data, size_t len) {
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
	ccn_name_append(path, name_path->comps[i], strlen(name_path->comps[i]));
    }

    ccn_auth_create_default(authenticator, signature, CCN_CONTENT_FRAGMENT, path, name_path->count, data, len);
    ccn_encode_ContentObject(message, path, authenticator, signature, data, len);
    
}

int 
decode_message(struct ccn_charbuf *message, struct path * name_path, unsigned char *data, size_t len) {
    struct ccn_parsed_ContentObject content;
    struct ccn_indexbuf *comps = ccn_indexbuf_create();
    const unsigned char * content_value;
    char * s = NULL;
    int content_length;
    
    int res = 0;
    int i;

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
	if (s != NULL) free(s);
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
main (int argc, char *argv[]) {
    struct ccn_charbuf *buffer;
    struct ccn_skeleton_decoder dd = {0};
    ssize_t res;
    char *outname;
    int fd;
    int result = 0;
    char * contents[] = {"This is the message", "Quaer #%2d zjduer  badone"};
    char * paths[] = { "/a/b/c", "/d/e/f" };
    struct path * cur_path = NULL;
    int i;

    if (argc > 1) {
	outname = argv[1];
    } else {
	printf("Usage: %s <outfilename>\n", argv[0]);
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
    }
    if (decode_message(buffer, cur_path, contents[0], strlen(contents[0])) != 0) {
	result = 1;
    }
    path_destroy(&cur_path);
    ccn_charbuf_destroy(&buffer);
    printf("Done with sample message\n");
    
    /* Now exercises as unit tests */
    
    for (i=0; i<2; i++) {
	printf("Unit test case %d\n", i);
	cur_path = path_create(paths[i]);
	buffer = ccn_charbuf_create();
	if (encode_message(buffer, cur_path, contents[i], strlen(contents[i]))) {
	    printf("Failed encode\n");
	} else if (decode_message(buffer, cur_path, contents[i], strlen(contents[i]))) {
	    printf("Failed decode\n");
	}
	path_destroy(&cur_path);
	ccn_charbuf_destroy(&buffer);
    }

    exit(result);
}
