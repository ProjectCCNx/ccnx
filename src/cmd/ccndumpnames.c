/*
 * Dumps names of everything quickly retrievable to stdout
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

/**
 * This is a temporary interface, don't even bother to put it in a header file.
 */
void
ccn_dump_names(struct ccn *h, struct ccn_charbuf *name_prefix, int local_scope, int allow_stale);

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-a] [uri]\n"
            "   Dumps names of everything quickly retrievable\n"
            "   -a - allow stale data\n",
            progname);
    exit(1);
}

int
main(int argc, char **argv)
{
    struct ccn *ccn = NULL;
    struct ccn_charbuf *c = NULL;
    int allow_stale = 0;
    int ch;
    int res;
    
    while ((ch = getopt(argc, argv, "ha")) != -1) {
        switch (ch) {
            case 'a':
                allow_stale = 1;
                break;
            case 'h':
            default:
                usage(argv[0]);
        }
    }
    
    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }
    c = ccn_charbuf_create();
    if (argv[optind] == NULL)
        ccn_name_init(c);
    else {
        res = ccn_name_from_uri(c, argv[optind]);
        if (res < 0) {
            fprintf(stderr, "%s: bad ccn URI: %s\n", argv[0], argv[optind]);
            exit(1);
        }
        if (argv[optind+1] != NULL)
            fprintf(stderr, "%s warning: extra arguments ignored\n", argv[0]);
    }
    ccn_dump_names(ccn, c, 1, allow_stale);
    exit(0);
}
