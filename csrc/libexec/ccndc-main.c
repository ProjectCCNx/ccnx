/**
 * @file ccndc-main.c
 * @brief Bring up a link to another ccnd.
 *
 * A CCNx program.
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

#include "ccndc.h"
#include "ccndc-log.h"
#include "ccndc-srv.h"

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <limits.h>
#include <string.h>
#include <strings.h>
#include <errno.h>

static int
read_configfile(struct ccndc_data *ccndc, const char *filename);

static void
usage(const char *progname)
{
    fprintf(stderr,
            "Usage:\n"
            "   %s [-h] [-d] [-v] [-t <lifetime>] (-f <configfile> | COMMAND)\n"
            "       -h print usage and exit\n"
            "       -d enter dynamic mode and create FIB entries based on DNS SRV records\n"
            "       -f <configfile> add or delete FIB entries based on the content of <configfile>\n"
            "       -t use value in seconds for lifetime of prefix registration\n"
            "       -v increase logging level\n"
            "\n"
            "   COMMAND can be one of following:\n"
            "       (add|del) <uri> (udp|tcp) <host> [<port> [<flags> [<mcastttl> [<mcastif>]]]])\n"
            "           to add prefix to or delete prefix from face identified by parameters\n"
            "       (add|del) <uri> face <faceid>\n"
            "           to add prefix to or delete prefix from face identified by number\n"
            "       (create|destroy) (udp|tcp) <host> [<port> [<mcastttl> [<mcastif>]]])\n"
            "           create or destroy a face identified by parameters\n"
            "       destroy face <faceid>\n"
            "           destroy face identified by number\n"
            "       setstrategy <prefix> <strategy> [<parameters> [<lifetime>]]\n"
            "           associate <strategy> with <prefix> with specified (strategy specific) <parameters> and <lifetime>\n"
            "       getstrategy <prefix>\n"
            "           get strategy information associated with <prefix>\n"
            "       removestrategy <prefix>\n"
            "           remove the strategy associated with <prefix>\n"
            "       srv\n"
            "           add ccnx:/ prefix to face created from parameters in SRV\n"
            "           record of a domain in DNS search list\n"
            ,
            progname);
}

char *
create_command_from_command_line(int argc, char **argv)
{
    int len = 0;
    char *out = NULL;
    int i;
    
    if (argc == 0)
        return NULL;
    
    for (i = 0; i < argc; i++) {
        len += strlen(argv[i]) + 1;
    }
    
    out = calloc(len, sizeof(char));
    if (out == 0) {
        ccndc_fatal(__LINE__, "Cannot allocate memory\n");
    }
    
    len = 0;
    for (i = 0; i < argc; i++) {
        memcpy(out+len, argv[i], strlen(argv[i]));
        len += strlen(argv[i]) + 1;
        out[len-1] = ' '; // separate everything with one space
    }
    out[len-1] = 0;
    
    return out;
}



int
main(int argc, char **argv)
{
    struct ccndc_data *ccndc = NULL;
    const char *progname = NULL;
    const char *configfile = NULL;
    int res = 1;
    int opt, disp_res;
    char *cmd = NULL;
    int dynamic = 0;
    int lifetime = -1;
    
    progname = argv[0];
    
    while ((opt = getopt(argc, argv, "hdvt:f:")) != -1) {
        switch (opt) {
            case 'f':
                configfile = optarg;
                break;
            case 't':
                lifetime = atoi(optarg);
                if (lifetime <= 0) {
                    usage(progname);
                    goto Cleanup;
                }
                break;
            case 'v':
                verbose = 1;
                break;
            case 'd':
                dynamic = 1;
                break;
            case 'h':
            default:
                usage(progname);
                goto Cleanup;
        }
    }
    
    if (configfile == NULL && !dynamic && optind == argc) {
        usage(progname);
        goto Cleanup;
    }
    
    ccndc = ccndc_initialize_data();
    if (lifetime > 0)
        ccndc->lifetime = lifetime;
    if (optind < argc) {
        /* config file cannot be combined with command line */
        if (configfile != NULL) {
            ccndc_warn(__LINE__, "Config file cannot be combined with command line\n");
            usage(progname);
            goto Cleanup;
        }
        
        if (argc - optind < 0) {
            usage(progname);
            goto Cleanup;
        }
        
        cmd = create_command_from_command_line(argc-optind-1, &argv[optind+1]);
        disp_res = ccndc_dispatch_cmd(ccndc, 0, argv[optind], cmd, argc - optind - 1);
        free(cmd);
        if (disp_res == INT_MIN) {
            usage(progname);
            goto Cleanup;
        }
    }
    if (configfile) {
        read_configfile(ccndc, configfile);
    }
    if (dynamic) {
        ccndc_daemonize(ccndc);
    }
    res = 0;
Cleanup:
    ccndc_destroy_data(&ccndc);
    return res;
}

/**
 * @brief Process configuration file
 * @param ccndc a pointer to the structure holding internal ccndc data, from ccndc_initialize_data()
 * @param filename
 *
 * Processing configuration file in two rounds. First round performs a dry run
 * to check for errors.  If no erors found, commands are executing if normal mode.
 */
static int
read_configfile(struct ccndc_data *ccndc, const char *filename)
{
    int configerrors;
    int lineno;
    
    FILE *cfg;
    char buf[1024];
    char *cp = NULL;
    char *cmd, *rest_of_the_command;
    int res;
    int phase;
    int retcode;
    int len;
    
    for (phase = 1; phase >= 0; --phase) {
        configerrors = 0;
        retcode = 0;
        lineno = 0;
        cfg = fopen(filename, "r");
        if (cfg == NULL)
            ccndc_fatal(__LINE__, "%s (%s)\n", strerror(errno), filename);
        
        while (fgets((char *)buf, sizeof(buf), cfg)) {
            lineno++;
            len = strlen(buf);
            if (buf[0] == '#' || len == 0)
                continue;
            if (buf[len - 1] == '\n')
                buf[len - 1] = '\0';
            cp = index(buf, '#');
            if (cp != NULL)
                *cp = '\0';
            
            rest_of_the_command = buf;
            do {
                cmd = strsep(&rest_of_the_command, " \t");
            } while (cmd != NULL && cmd[0] == 0);
            
            if (cmd == NULL) /* blank line */
                continue;
            res = ccndc_dispatch_cmd(ccndc, phase, cmd, rest_of_the_command, -1);
            retcode += res;
            if (phase == 1 && res < 0) {
                ccndc_warn(__LINE__, "Error: near line %d\n", lineno);
                configerrors++;
            }
        }
        fclose(cfg);
        if (configerrors != 0)
            return (-configerrors);
    } 
    return (retcode);
}
