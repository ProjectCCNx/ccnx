#include <stdio.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netdb.h>
#include <string.h>

#include "ccn/ccnd.h"

int
main (int argc, char * const argv[]) {

    struct {
        const char *localsockname;
        const char *remotehostname;
    } options = {CCN_DEFAULT_LOCAL_SOCKNAME, NULL};

    int c;

    while ((c = getopt(argc, argv, "s:h:")) != -1) {
        switch (c) {
        case 's':
            options.localsockname = strdup(optarg);
            break;
        case 'h':
            options.remotehostname = strdup(optarg);
            break;
        }
    }
}
