/* -*- mode: C; c-file-style: "gnu"; c-basic-offset: 4; indent-tabs-mode:nil; -*- */
/**
 * @file ccndc-log.h
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

#include "ccndc-log.h"

int verbose = 0;

void
ccndc_warn(int lineno, const char *format, ...)
{
    if (verbose) {
        struct timeval t;
        va_list ap;
        va_start(ap, format);
        gettimeofday(&t, NULL);
        fprintf(stderr, "%d.%06d ccndc[%d]:%d: ", (int)t.tv_sec, (unsigned)t.tv_usec, (int)getpid(), lineno);
        vfprintf(stderr, format, ap);
        va_end(ap);
    }
}

void
ccndc_fatal(int line, const char *format, ...)
{
    struct timeval t;
    va_list ap;
    va_start(ap, format);
    gettimeofday(&t, NULL);
    fprintf(stderr, "%d.%06d ccndc[%d]:%d: ", (int)t.tv_sec, (unsigned)t.tv_usec, (int)getpid(), line);
    vfprintf(stderr, format, ap);
    va_end(ap);
    exit(1);
}

void
on_error_exit(int res, int lineno, const char *msg)
{
    if (res >= 0)
        return;
    ccndc_fatal(lineno, "fatal error, res = %d, %s\n", res, msg);
}
