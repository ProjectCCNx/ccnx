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

#ifndef CCNDC_LOG_H
#define CCNDC_LOG_H

#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/time.h>

/**
 * @brief Issue warning on stderr
 * @param lineno Line number where problem happened
 * @param format printf-style format line
 */
void
ccndc_warn(int lineno, const char *format, ...);

/**
 * @brief Issue error message on stderr and terminate execution of the app
 * @param lineno Line number where problem happened
 * @param format printf-style format line
 */
void
ccndc_fatal(int line, const char *format, ...);

extern int verbose;

#define ON_ERROR_CLEANUP(resval)                                        \
    {                                                                   \
        if ((resval) < 0) {                                             \
            if (verbose > 0) ccndc_warn (__LINE__, "OnError cleanup\n"); \
            goto Cleanup;                                               \
        }                                                               \
    }

#define ON_NULL_CLEANUP(resval)                                         \
    {                                                                   \
        if ((resval) == NULL) {                                         \
            if (verbose > 0) ccndc_warn(__LINE__, "OnNull cleanup\n");  \
            goto Cleanup;                                               \
        }                                                               \
    }

#define ON_ERROR_EXIT(resval, msg)                                      \
    {                                                                   \
        if (resval < 0) {                                               \
            ccndc_fatal(__LINE__, "fatal error, res = %d, %s\n", resval, msg); \
        }                                                               \
    }
    

#endif // CCNDC_LOG_H
