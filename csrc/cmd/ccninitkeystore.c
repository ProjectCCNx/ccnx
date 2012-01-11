/**
 * @file ccninitkeystore.c
 * Initialize a CCNx keystore with given parameters.
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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
#include <errno.h>
#include <pwd.h>
#include <sys/stat.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/keystore.h>

#define CCN_KEYSTORE_PASS "Th1s1sn0t8g00dp8ssw0rd."

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-h] [-f] [-u username] [-l keylength] [-v validity] [directory]\n"
            "   Initialize a CCNx keystore with given parameters\n", progname);
    fprintf(stderr,
            "   -h  Display this help message.\n"
            "   -f  Force overwriting an existing keystore. Default no overwrite permitted.\n" 
            "   -u username  Username for this keystore.  Default username of effective uid.\n"
            "   -l keylength  Length of RSA key to be generated.  Default 1024 bits.\n"
            "   -v validity  Number of days that certificate should be valid.  Default 30.\n"
            "   directory  Directory in which to create .ccnx/.ccnx_keystore. Default $HOME.\n"
            );
}

int
main(int argc, char **argv)
{
    int res;
    int opt;
    struct stat statbuf;
    char *dir;
    struct ccn_charbuf *keystore = NULL;
    int force = 0;
    char *user = NULL;
    char useruid[32];
    struct passwd *pwd = NULL;
    int keylength = 0;
    int validity = 0;
    
    while ((opt = getopt(argc, argv, "hfu:p:l:v:")) != -1) {
        switch (opt) {
            case 'f':
                force = 1;
                break;
            case 'u':
                user = optarg;
                break;
            case 'l':
                keylength = atoi(optarg);
                if (keylength < 512) {
                    fprintf(stderr, "%d: Key length too short for signing CCNx objects.\n", keylength);
                    exit(1);
                }
                break;
            case 'v':
                validity = atoi(optarg);
                if (validity < 0) {
                    fprintf(stderr, "%d: Certificate validity must be > 0.\n", validity);
                    exit(1);
                }
                break;
            case 'h':
            default:
                usage(argv[0]);
                exit(1);
        }
    }
    dir = argv[optind];
    if (dir == NULL){
        dir = getenv("HOME");
    }
    res = stat(dir, &statbuf);
    if (res == -1) {
        perror(dir);
        exit(1);
    } else if ((statbuf.st_mode & S_IFMT) != S_IFDIR) {
        errno = ENOTDIR;
        perror(dir);
        exit(1);
    }
    keystore = ccn_charbuf_create();
    ccn_charbuf_putf(keystore, "%s/.ccnx", dir);
    res = stat(ccn_charbuf_as_string(keystore), &statbuf);
    if (res == -1) {
        res = mkdir(ccn_charbuf_as_string(keystore), 0700);
        if (res != 0) {
            perror(ccn_charbuf_as_string(keystore));
            exit(1);
        }
    }
    ccn_charbuf_append_string(keystore, "/.ccnx_keystore");
    res = stat(ccn_charbuf_as_string(keystore), &statbuf);
    if (res != -1 && !force) {
        errno=EEXIST;
        perror(ccn_charbuf_as_string(keystore));
        exit(1);
    }
    if (user == NULL) {
        errno = 0;
        pwd = getpwuid(geteuid());
        if (pwd != NULL)
            user = pwd->pw_name;
        else {
            if (errno != 0) {
                perror("getpwuid");
                exit(1);
            }
            snprintf(useruid, sizeof(useruid), "uid%d", geteuid());
            user = useruid;
        }
    }
    res = ccn_keystore_file_init(ccn_charbuf_as_string(keystore), CCN_KEYSTORE_PASS,
                                 user, keylength, validity);
    if (res != 0) {
        if (errno != 0)
            perror(ccn_charbuf_as_string(keystore));
        else
            fprintf(stderr, "ccn_keystore_file_init: invalid argument\n");
        exit(1);
    }
    return(0);
}
