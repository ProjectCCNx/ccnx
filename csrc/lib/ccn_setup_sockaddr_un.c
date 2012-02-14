/**
 * @file ccn_setup_sockaddr_un.c
 * @brief
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */
 
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>

#include <ccn/ccnd.h>
#include <ccn/ccn_private.h>

/**
 * Set up a unix-domain socket address for contacting ccnd.
 *
 * If the environment variable CCN_LOCAL_SOCKNAME is set and
 * not empty, it supplies the name stem; otherwise the compiled-in
 * default is used.
 *
 * If portstr is NULL or empty, the environment variable CCN_LOCAL_PORT is
 * checked. If the portstr specifies something other than the ccnx registered
 * port number, the socket name is modified accordingly. 
 * @param portstr - numeric port; use NULL for default.
 */
void
ccn_setup_sockaddr_un(const char *portstr, struct sockaddr_un *result)
{
    struct sockaddr_un *sa = result;
    const char *sockname = getenv("CCN_LOCAL_SOCKNAME");
    if (sockname == NULL || sockname[0] == 0)
        sockname = CCN_DEFAULT_LOCAL_SOCKNAME; /* /tmp/.ccnd.sock */
    memset(sa, 0, sizeof(*sa));
    sa->sun_family = AF_UNIX;
    if (portstr == NULL || portstr[0] == 0)
        portstr = getenv(CCN_LOCAL_PORT_ENVNAME);
    if (portstr != NULL && atoi(portstr) > 0 &&
          atoi(portstr) != atoi(CCN_DEFAULT_UNICAST_PORT))
        snprintf(sa->sun_path, sizeof(sa->sun_path), "%s.%s",
                 sockname, portstr);
    else
        snprintf(sa->sun_path, sizeof(sa->sun_path), "%s",
                 sockname);
}
