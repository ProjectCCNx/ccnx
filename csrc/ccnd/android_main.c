/**
 * Copyright (C) 2009,2010 Palo Alto Research Center, Inc.
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

/**
 * This module replaces ccnd_main on the android platform.  It includes the
 * methods a JNI interface would use to start ccnd.
 */

#include <stdarg.h>
#include <android/log.h>
#include <ccnd_private.h>

static int
logger(void *loggerdata, const char *format, va_list ap)
{
    __android_log_vprint(ANDROID_LOG_INFO, "CCND", format, ap);
}

int
start_ccnd(void)
{
    struct ccnd_handle *h = NULL;
    
    h = ccnd_create("ccnd", &logger, NULL);
    ccnd_msg(h, "ccnd_create h=%p", h);
    ccnd_run(h);
    ccnd_msg(h, "exiting.");
}
