/**
 * @file ccnr_main.c
 * 
 * Part of ccnr -  CCNx Repository Daemon.
 *
 */

/*
 * Copyright (C) 2009-2011 Palo Alto Research Center, Inc.
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
 
#include <signal.h>
#include <stddef.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

#include "ccnr_private.h"

#include "ccnr_init.h"
#include "ccnr_dispatch.h"
#include "ccnr_msg.h"
#include "ccnr_stats.h"

static int
stdiologger(void *loggerdata, const char *format, va_list ap)
{
    FILE *fp = (FILE *)loggerdata;
    return(vfprintf(fp, format, ap));
}

int
main(int argc, char **argv)
{
    struct ccnr_handle *h;
    
    if (argc > 1) {
        fprintf(stderr, "%s", ccnr_usage_message);
        exit(1);
    }
    signal(SIGPIPE, SIG_IGN);
    h = r_init_create(argv[0], stdiologger, stderr);
    if (h == NULL)
        exit(1);
    r_dispatch_run(h);
    ccnr_msg(h, "exiting.");
    r_init_destroy(&h);
    exit(0);
}
