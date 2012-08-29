/* -*- mode: C; c-file-style: "gnu"; c-basic-offset: 4; indent-tabs-mode:nil; -*- */
/**
 * @file ccndc.c
 * @brief Bring up a link to another ccnd.
 *
 * A CCNx program.
 *
 * Copyright (C) 2009-2012 Palo Alto Research Center, Inc.
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

#include "ccndc-ccnb.h"
#include "ccndc-log.h"

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <strings.h>

static void
usage (const char *progname)
{
    fprintf (stderr,
             "Usage:\n"
             // "%s [-d] [-v] (-f configfile | (add|del|delwithface) uri (udp|tcp) host [port [flags [mcastttl [mcastif]]]])\n"
             "   %s (add|del) uri (udp|tcp) host [port [flags [mcastttl [mcastif]]]])\n"
             // "      -d enter dynamic mode and create FIB entries based on DNS SRV records\n"
             // "      -f configfile add or delete FIB entries based on contents of configfile\n"
             // "      -v increase logging level\n"
             // "         add|del add or delete FIB entry based on parameters\n"
             // "   delwithface delete FIB entry and associated face\n"
             // "%s [-v] destroyface faceid\n"
             // "   destroy face based on face number\n",
             // progname,
             , progname);
    exit(1);
}

char *create_command_from_command_line (int argc, char **argv)
{
    int len = 0;
    char *out = NULL;
    int i;

    for (i = 0; i < argc; i++) {
        len += strlen (argv[i]) + 1;
    }
    
    out = calloc (len, sizeof (char));
    if (out == 0) {
        ccndc_fatal (__LINE__, "Cannot allocate memory\n");
    }

    len = 0;
    for (i = 0; i < argc; i++) {
        memcpy (out+len, argv[i], strlen (argv[i]));
        len += strlen (argv[i]) + 1;
        out[len-1] = ' '; // separate everything with one space
    }
    out[len-1] = 0;
    
    return out;
}

int
main (int argc, char **argv)
{
    struct ccndc_data *ccndc;
    const char *progname = NULL;
    const char *configfile = NULL;
    int res;
    int opt;
    char *cmd = NULL;

    progname = argv[0];

    if (argc == 1) {
        usage (progname);
    }
    
    ccndc = ccndc_initialize ();
    
    while ((opt = getopt(argc, argv, "h")) != -1) {
        switch (opt) {
            // case 'f':
            //     configfile = optarg;
            //     break;
            case 'h':
            default:
                usage(progname);
        }
    }

    if (optind < argc) {
        /* config file cannot be combined with command line */
        if (configfile != NULL) {
            usage(progname);
        }
        /* (add|delete) uri type host [port [flags [mcast-ttl [mcast-if]]]] */

        if (argc - optind < 1)
            usage (progname);
        
        if (strcasecmp (argv[optind], "add") == 0) {
            if (argc - optind < 2 || argc - optind > 8)
                usage (progname);

            cmd = create_command_from_command_line (argc-optind-1, &argv[optind+1]);
            res = ccndc_add (ccndc, 0, cmd);
            free (cmd);
        }
        else if (strcasecmp (argv[optind], "del") == 0) {
            if (argc - optind < 2 || argc - optind > 9)
                usage (progname);

            cmd = create_command_from_command_line (argc-optind-1, &argv[optind+1]);
            res = ccndc_del (ccndc, 0, cmd);
            free (cmd);
        }
        else if (strcasecmp (argv[optind], "destroyface") == 0) {
            if (argc - optind < 2 || argc - optind > 2)
                usage (progname);

            cmd = create_command_from_command_line (argc-optind-1, &argv[optind+1]);
            res = ccndc_destroyface (ccndc, 0, cmd);
            free (cmd);
        }
        else {
            usage (progname);
        }
        
        // res = process_command_tokens(pflhead, 0,
        //                              argv[optind],
        //                              argv[optind+1],
        //                              (optind + 2) < argc ? argv[optind+2] : NULL,
        //                              (optind + 3) < argc ? argv[optind+3] : NULL,
        //                              (optind + 4) < argc ? argv[optind+4] : NULL,
        //                              (optind + 5) < argc ? argv[optind+5] : NULL,
        //                              (optind + 6) < argc ? argv[optind+6] : NULL,
        //                              (optind + 7) < argc ? argv[optind+7] : NULL);
        if (res < 0)
            usage(progname);
    }
    
    // if (configfile) {
    //     read_configfile(configfile, pflhead);
    // }
    
    // h = ccn_create();
    // res = ccn_connect(h, NULL);
    // if (res < 0) {
    //     ccn_perror (h, "ccn_connect");
    //     exit(1);
    // }
    
    // if (pflhead->next) {        
    //     ccndid_size = get_ccndid(h, ccndid, sizeof(ccndid_storage));
    //     for (pfl = pflhead->next; pfl != NULL; pfl = pfl->next) {
    //         process_prefix_face_list_item(h, pfl);
    //     }
    //     prefix_face_list_destroy(&pflhead->next);
    // }

    return res;
}


// static int
// read_configfile(const char *filename, struct prefix_face_list_item *pfltail)
// {
//     int configerrors = 0;
//     int lineno = 0;
//     char *cmd;
//     char *uri;
//     char *proto;
//     char *host;
//     char *port;
//     char *flags;
//     char *mcastttl;
//     char *mcastif;
//     FILE *cfg;
//     char buf[1024];
//     const char *seps = " \t\n";
//     char *cp = NULL;
//     char *last = NULL;
//     int res;
    
//     cfg = fopen(filename, "r");
//     if (cfg == NULL)
//         ccndc_fatal(__LINE__, "%s (%s)\n", strerror(errno), filename);
    
//     while (fgets((char *)buf, sizeof(buf), cfg)) {
//         int len;
//         lineno++;
//         len = strlen(buf);
//         if (buf[0] == '#' || len == 0)
//             continue;
//         if (buf[len - 1] == '\n')
//             buf[len - 1] = '\0';
//         cp = index(buf, '#');
//         if (cp != NULL)
//             *cp = '\0';
        
//         cmd = strtok_r(buf, seps, &last);
//         if (cmd == NULL)	/* blank line */
//             continue;
//         uri = strtok_r(NULL, seps, &last);
//         proto = strtok_r(NULL, seps, &last);
//         host = strtok_r(NULL, seps, &last);
//         port = strtok_r(NULL, seps, &last);
//         flags = strtok_r(NULL, seps, &last);
//         mcastttl = strtok_r(NULL, seps, &last);
//         mcastif = strtok_r(NULL, seps, &last);
//         res = process_command_tokens(pfltail, lineno, cmd, uri, proto, host, port, flags, mcastttl, mcastif);
//         if (res < 0) {
//             configerrors--;
//         } else {
//             pfltail = pfltail->next;
//         }
//     }
//     fclose(cfg);
//     return (configerrors);
// }
