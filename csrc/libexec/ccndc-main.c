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

#include "ccndc.h"
#include "ccndc-log.h"
#include "ccndc-srv.h"

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
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
             "   %s [-h] [-d] [-v] (-f <configfile> | COMMAND)\n"
             "      -h print usage and exit\n"
             "      -d enter dynamic mode and create FIB entries based on DNS SRV records\n"
             "      -f <configfile> add or delete FIB entries based on the content of <configfile>\n"
             "      -v increase logging level\n"
             "\n"
             "   COMMAND can be one of following:\n"
             "      add <uri> (udp|tcp) <host> [<port> [<flags> [<mcastttl> [<mcastif>]]]])\n"
             "         to add FIB entry\n"
             "      readd <uri> (udp|tcp) <host> [<port> [<flags> [<mcastttl> [<mcastif>]]]])\n"
             "         to delete and then re-add FIB entry\n"
             "      del <uri> (udp|tcp) <host> [<port> [<flags> [<mcastttl> [<mcastif> [destroyface]]]]])\n"
             "         to remove FIB entry and optionally an associated face\n"
             "      destroyface <faceid>\n"
             "         destroy face based on face number\n"
             "      srv\n"
             "         add FIB entry based on SRV record of a domain in search list\n"
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
    struct ccndc_data *ccndc;
    const char *progname = NULL;
    const char *configfile = NULL;
    int res = 0;
    int opt;
    char *cmd = NULL;
    int dynamic = 0;

    progname = argv[0];
    
    while ((opt = getopt(argc, argv, "hdvf:")) != -1) {
        switch (opt) {
        case 'f':
            configfile = optarg;
            break;
        case 'v':
            verbose++;
            break;
        case 'd':
            dynamic++;
            break;
        case 'h':
        default:
            usage(progname);
            return 1;
        }
    }

    if (configfile == NULL && !dynamic && optind == argc) {
        usage(progname);
        return 1;
    }
    
    ccndc = ccndc_initialize();
    
    if (optind < argc) {
        /* config file cannot be combined with command line */
        if (configfile != NULL) {
            ccndc_warn(__LINE__, "Config file cannot be combined with command line\n");
            usage(progname);
            res = 1;
            goto Cleanup;
        }
        
        if (argc - optind < 0) {
            usage(progname);
            res = 1;
            goto Cleanup;
        }
        
        cmd = create_command_from_command_line(argc-optind-1, &argv[optind+1]);
        res = ccndc_dispatch_cmd(ccndc, 0, argv[optind], cmd, argc - optind - 1);
        free(cmd);
        
        if (res == -99) {
            usage(progname);
            res = 1;
            goto Cleanup;
        }
    }
    
    if (configfile) {
        read_configfile(ccndc, configfile);
    }

    if (dynamic) {
        ccndc_daemonize(ccndc);
    }

 Cleanup:
    ccndc_destroy(&ccndc);
    return res;
}

/**
 * @brief Process configuration file
 * @param filename
 *
 * Processing configuration file in two rounds. First round performs a dry run
 * to check for errors.  If no erors found, commands are executing if normal mode.
 */
static int
read_configfile(struct ccndc_data *ccndc, const char *filename)
{
    int configerrors = 0;
    int lineno = 0;

    FILE *cfg;
    char buf[1024];
    char *cp = NULL;
    int res = 0;
    
    cfg = fopen(filename, "r");
    if (cfg == NULL)
        ccndc_fatal(__LINE__, "%s (%s)\n", strerror(errno), filename);
    
    while (fgets((char *)buf, sizeof(buf), cfg)) {
        int len;
        lineno++;
        len = strlen(buf);
        if (buf[0] == '#' || len == 0)
            continue;
        if (buf[len - 1] == '\n')
            buf[len - 1] = '\0';
        cp = index(buf, '#');
        if (cp != NULL)
            *cp = '\0';

        char *cmd;
        char *rest_of_the_command = buf;
        do {
            cmd = strsep(&rest_of_the_command, " \t");
        } while (cmd != NULL && cmd[0] == 0);

        if (cmd == NULL) /* blank line */
            continue;
        res = ccndc_dispatch_cmd(ccndc, 1, cmd, rest_of_the_command, -1);
        if (res < 0) {
            ccndc_warn(__LINE__, "Error: near line %d\n", lineno);
            configerrors++;
        }
    }
    fclose(cfg);

    if (configerrors != 0)
        return (-configerrors);

    //////////////////////////////////////////////////////////////////
    // Same thing, but actually applying commands
    //////////////////////////////////////////////////////////////////
    
    cfg = fopen(filename, "r");
    if (cfg == NULL)
        ccndc_fatal(__LINE__, "%s (%s)\n", strerror(errno), filename);

    while (fgets((char *)buf, sizeof(buf), cfg)) {
        int len;
        lineno++;
        len = strlen(buf);
        if (buf[0] == '#' || len == 0)
            continue;
        if (buf[len - 1] == '\n')
            buf[len - 1] = '\0';
        cp = index(buf, '#');
        if (cp != NULL)
            *cp = '\0';

        char *cmd;
        char *rest_of_the_command = buf;
        do {
            cmd = strsep(&rest_of_the_command, " \t");
        } while (cmd != NULL && cmd[0] == 0);

        if (cmd == NULL) /* blank line */
            continue;

        res += ccndc_dispatch_cmd(ccndc, 0, cmd, rest_of_the_command, -1);
    }
    fclose(cfg);

    return res;
}
