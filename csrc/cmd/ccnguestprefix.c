/**
 * @file ccnguestprefix.c
 * Test guest prefix
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
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

/**
 * Provide usage hints for the program and then exit with a non-zero status.
 */
static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s - Print the guest prefix\n",
            progname);
    exit(1);
}

int
main(int argc, char **argv)
{
    struct ccn *ccn = NULL;
    struct ccn_charbuf *name = NULL;
    int opt;
    int res;
        
    while ((opt = getopt(argc, argv, "h")) != -1) {
        switch (opt) {
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
    name = ccn_charbuf_create();
    res = ccn_guest_prefix(ccn, name, 500);
    if (res < 0)
        exit(1);
    printf("%s\n", ccn_charbuf_as_string(name));
    exit(0);
}
