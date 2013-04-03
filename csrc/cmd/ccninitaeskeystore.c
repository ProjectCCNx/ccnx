/**
 * @file ccninitaeskeystore.c
 * Initialize a CCNx AES keystore with given parameters (for symmetric keys)
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
#include <pwd.h>
#include <sys/stat.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/keystore.h>

#include <openssl/engine.h>


#define CCN_KEYSTORE_PASS "Th1s1sn0t8g00dp8ssw0rd."
#define CCN_SECRET_KEY_LENGTH 256	/* We only support HMAC-SHA256 right now */

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-h] [-f] [-k keydata] [-p password] [-d directory] [name]\n"
            "   Initialize a CCNx AES keystore with given parameters\n", progname);
    fprintf(stderr,
            "   -h           Display this help message.\n"
            "   -f 	     Force overwriting an existing keystore. Default no overwrite permitted.\n" 
            "   -k key 	     Key data for this key.\n"
            "   -p password  Password for this keystore.  Default default CCN password.\n"
            "   -d directory Directory in which to create .ccnx/.ccnx_keystore. Default $HOME.\n"
	    "   -r           Read & decrpyt key from existing file and print if ASCII. \n"
            "   name         Name of keystore file.  Default .ccnx-keystore-[keyhash]. \n"
            );
}

int
main(int argc, char **argv)
{
    int res;
    int opt;
    char *dir = NULL;
    struct ccn_charbuf *keystore = NULL;
    int force = 0;
    char *password = CCN_KEYSTORE_PASS;
    char *name = ".ccnx_keystore";
    char *username;
    int fullname = 0;
    char *key = NULL;
    unsigned char keybuf[CCN_SECRET_KEY_LENGTH/8];
    int copylen = CCN_SECRET_KEY_LENGTH/8;
    struct stat statbuf;
    int read_mode = 0;
    
    while ((opt = getopt(argc, argv, "hfk:p:d:r")) != -1) {
        switch (opt) {
            case 'f':
                force = 1;
                break;
            case 'k':
                key = optarg;
                break;
            case 'p':
                password = optarg;
                break;
            case 'd':
                dir = optarg;
                break;
            case 'r':
                read_mode = 1;
                break;
            case 'h':
            default:
                usage(argv[0]);
                exit(1);
        }
    }
    username = argv[optind];
    if (username != NULL) {
        name = username;
        fullname = 1;
    }
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
    ccn_charbuf_append_string(keystore, "/");
    ccn_charbuf_append_string(keystore, name);

    if (key == NULL) {
	RAND_bytes(keybuf, CCN_SECRET_KEY_LENGTH);
    } else {
        memset(keybuf, 0, sizeof(keybuf));
        if (strlen(key) < sizeof(keybuf))
            copylen = strlen(key);
        memcpy(keybuf, key, copylen);
    }

    if (!fullname)
	create_aes_filename_from_key(keystore, keybuf, CCN_SECRET_KEY_LENGTH);

    if (!force) {
        res = stat(ccn_charbuf_as_string(keystore), &statbuf);
        if (res != -1) {
            errno=EEXIST;
            perror(ccn_charbuf_as_string(keystore));
            exit(1);
        }
    }

    res = ccn_aes_keystore_file_init(ccn_charbuf_as_string(keystore), password, keybuf, 
			CCN_SECRET_KEY_LENGTH);
    if (res != 0) {
        if (errno != 0)
            perror(ccn_charbuf_as_string(keystore));
        else
            fprintf(stderr, "ccn_keystore_file_init: invalid argument\n");
        exit(1);
    }
    return(0);
}
