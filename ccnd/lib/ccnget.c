/*
 * Get one content item matching the name prefix and write it to stdout.
 * Written as test for ccn_get, but probably useful for debugging.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

int
ccn_resolve_highest_version(struct ccn *h, struct ccn_charbuf *name, int timeout_ms);

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-a] [-c] ccn:/a/b\n"
            "   Get one content item matching the name prefix and write it to stdout"
            "\n"
            "   -a - allow stale data\n"
            "   -c - content only, not full ccnb\n",
            progname);
    exit(1);
}

int
main(int argc, char **argv)
{
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    struct ccn_charbuf *resultbuf = NULL;
    const char *arg = NULL;
    struct ccn_parsed_ContentObject pcobuf = { 0 };
    int res;
    char ch;
    int allow_stale = 0;
    int content_only = 0;
    const unsigned char *ptr;
    size_t length;
    int resolve_version = 0;
    
    while ((ch = getopt(argc, argv, "hacv")) != -1) {
        switch (ch) {
            case 'a':
                allow_stale = 1;
                break;
            case 'c':
                content_only = 1;
                break;
            case 'v':
                resolve_version = 1;
                break;
            case 'h':
            default:
                usage(argv[0]);
        }
    }
    arg = argv[optind];
    if (arg == NULL)
        usage(argv[0]);
    name = ccn_charbuf_create();
    res = ccn_name_from_uri(name, arg);
    if (res < 0) {
        fprintf(stderr, "%s: bad ccn URI: %s\n", argv[0], arg);
        exit(1);
    }
    if (argv[optind + 1] != NULL)
        fprintf(stderr, "%s warning: extra arguments ignored\n", argv[0]);
    if (allow_stale) {
        templ = ccn_charbuf_create();
        ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
        ccn_charbuf_append_tt(templ, CCN_DTAG_Name, CCN_DTAG);
        ccn_charbuf_append_closer(templ); /* </Name> */
        ccn_charbuf_append_tt(templ, CCN_DTAG_AnswerOriginKind, CCN_DTAG);
        ccn_charbuf_append_non_negative_integer(templ,
                                                CCN_AOK_DEFAULT | CCN_AOK_STALE);
        ccn_charbuf_append_closer(templ); /* </AnswerOriginKind> */
        ccn_charbuf_append_closer(templ); /* </Interest> */
    }
    resultbuf = ccn_charbuf_create();
    if (resolve_version) {
        res = ccn_resolve_highest_version(NULL, name, 500);
        if (res >= 0) {
            ccn_uri_append(resultbuf, name->buf, name->length, 1);
            fprintf(stderr, "== %s\n",
                            ccn_charbuf_as_string(resultbuf));
            resultbuf->length = 0;
        }
    }
    res = ccn_get(NULL, name, -1, templ, 3000, resultbuf, &pcobuf, NULL);
    if (res >= 0) {
        ptr = resultbuf->buf;
        length = resultbuf->length;
        if (content_only)
            ccn_content_get_value(ptr, length, &pcobuf, &ptr, &length);
        res = fwrite(ptr, length, 1, stdout) - 1;
    }
    ccn_charbuf_destroy(&resultbuf);
    ccn_charbuf_destroy(&templ);
    ccn_charbuf_destroy(&name);
    exit(res < 0);
}
