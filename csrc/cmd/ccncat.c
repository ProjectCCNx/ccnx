/**
 * @file ccncat.c
 * Reads streams at the given CCNx URIs and writes to stdout
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2009-2013 Palo Alto Research Center, Inc.
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
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>
#include <ccn/fetch.h>

/**
 * Provide usage hints for the program and then exit with a non-zero status.
 */
static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-h] [-d flags] [-p pipeline] [-s scope] [-a] ccnx:/a/b ...\n"
            "  Reads streams at the given ccn URIs and writes to stdout\n"
            "  -h produces this message\n"
            "  -d flags specifies the fetch debug flags which are the sum of\n"
            "    NoteGlitch = 1,\n"
            "    NoteAddRem = 2,\n"
            "    NoteNeed = 4,\n"
            "    NoteFill = 8,\n"
            "    NoteFinal = 16,\n"
            "    NoteTimeout = 32,\n"
            "    NoteOpenClose = 64\n"
            "  -p pipeline specifies the size of the pipeline.  Default 4.\n"
            "     pipeline >= 0.\n"
            "  -s scope specifies the scope for the interests.  Default unlimited.\n"
            "     scope = 0 (cache), 1 (local), 2 (neighborhood), 3 (unlimited).\n"
            "  -a allow stale data\n",
            progname);
    exit(1);
}

struct ccn_charbuf *
make_template(int allow_stale, int scope)
{
    struct ccn_charbuf *templ = ccn_charbuf_create();
    ccnb_element_begin(templ, CCN_DTAG_Interest);
    ccnb_element_begin(templ, CCN_DTAG_Name);
    ccnb_element_end(templ); /* </Name> */
    // XXX - use pubid if possible
    ccnb_element_begin(templ, CCN_DTAG_MaxSuffixComponents);
    ccnb_append_number(templ, 1);
    ccnb_element_end(templ); /* </MaxSuffixComponents> */
    if (allow_stale) {
        ccnb_element_begin(templ, CCN_DTAG_AnswerOriginKind);
        ccnb_append_number(templ, CCN_AOK_DEFAULT | CCN_AOK_STALE);
        ccnb_element_end(templ); /* </AnswerOriginKind> */
    }
    if (scope >= 0 && scope <= 2) {
        ccnb_tagged_putf(templ, CCN_DTAG_Scope, "%d", scope);
    }
    ccnb_element_end(templ); /* </Interest> */
    return(templ);
}


/**
 * Process options and then loop through command line CCNx URIs retrieving
 * the data and writing it to stdout.
 */
int
main(int argc, char **argv)
{
    struct ccn *ccn = NULL;
    struct ccn_fetch *fetch = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    const char *arg = NULL;
    int dflag = 0;
    int allow_stale = 0;
    int scope = -1;
    int pipeline = 4;
    unsigned char buf[8192];
    int i;
    int res;
    int opt;
    int assumeFixed = 0; // variable only for now
    
    while ((opt = getopt(argc, argv, "had:p:s:")) != -1) {
        switch (opt) {
            case 'a':
                allow_stale = 1;
                break;
            case 'd':
                dflag = atoi(optarg);
                break;
            case 'p':
                pipeline = atoi(optarg);
                if (pipeline < 0)
                    usage(argv[0]);
                break;
            case 's':
                scope = atoi(optarg);
                if (scope < 0 || scope > 3)
                    usage(argv[0]);
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
    /* Check the args first */
    for (i = optind; argv[i] != NULL; i++) {
        name->length = 0;
        res = ccn_name_from_uri(name, argv[i]);
        if (res < 0) {
            fprintf(stderr, "%s: bad ccn URI: %s\n", argv[0], argv[i]);
            exit(1);
        }
    }
    
    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }
    
    templ = make_template(allow_stale, scope);
    
    fetch = ccn_fetch_new(ccn);
    if (dflag) {
        ccn_fetch_set_debug(fetch, stderr, dflag);
    }
    
    for (i = optind; (arg = argv[i]) != NULL; i++) {
        name->length = 0;
        res = ccn_name_from_uri(name, argv[i]);
        struct ccn_fetch_stream *stream = ccn_fetch_open(fetch, name, arg, templ, pipeline, CCN_V_HIGHEST, assumeFixed);
        if (NULL == stream) {
            continue;
        }
        while ((res = ccn_fetch_read(stream, buf, sizeof(buf))) != 0) {
            if (res > 0) {
                fwrite(buf, res, 1, stdout);
            } else if (res == CCN_FETCH_READ_NONE) {
                fflush(stdout);
                if (ccn_run(ccn, 1000) < 0) {
                    fprintf(stderr, "%s: error during ccn_run\n", argv[0]);
                    exit(1);
                }
            } else if (res == CCN_FETCH_READ_END) {
                break;
            } else if (res == CCN_FETCH_READ_TIMEOUT) {
                /* eventually have a way to handle long timeout? */
                ccn_reset_timeout(stream);
                fflush(stdout);
                if (ccn_run(ccn, 1000) < 0) {
                    fprintf(stderr, "%s: error during ccn_run\n", argv[0]);
                    exit(1);
                }
            } else {
                /* fatal stream error; shuld report this! */
                fprintf(stderr, "%s: fetch error: %s\n", argv[0], arg);
                exit(1);
            }
        }
        stream = ccn_fetch_close(stream);
    }
    fflush(stdout);
    fetch = ccn_fetch_destroy(fetch);
    ccn_destroy(&ccn);
    ccn_charbuf_destroy(&name);
    exit(0);
}
