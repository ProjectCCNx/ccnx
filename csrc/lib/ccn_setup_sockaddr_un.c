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
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/un.h>

#include <ccn/ccnd.h>
#include <ccn/ccn_private.h>
#include <ccn/charbuf.h>

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

/**
 * Set up a Internet socket address for contacting ccnd.
 *
 * The name must be of the form "tcp[4|6][:port]"
 * If there is no port specified, the environment variable CCN_LOCAL_PORT is
 * checked. Bad port specifications will result in the default port (9695)
 * being used.  If neither "4" nor "6" is present, the code will prefer the IPv4
 * localhost address.
 */
void
ccn_setup_sockaddr_in(const char *name, struct sockaddr *result)
{
    char *portstr;
    char *nameonly = strdup(name);
    int port = 0;

    portstr = strchr(nameonly, ':');
    if (portstr)
        *portstr++ = 0;
    if (portstr == NULL || portstr[0] == 0)
        portstr = getenv(CCN_LOCAL_PORT_ENVNAME);
    if (portstr != NULL)
        port = atoi(portstr);
    if (port <= 0 || port >= 65536)
        port = CCN_DEFAULT_UNICAST_PORT_NUMBER;
    if (strcasecmp(nameonly, "tcp6") == 0) {
        struct sockaddr_in6 *sa6 = (struct sockaddr_in6 *)result;
        memset(sa6, 0, sizeof(*sa6));
        sa6->sin6_family = AF_INET6;
        inet_pton(AF_INET6, "::1", &sa6->sin6_addr);
        sa6->sin6_port = htons(port);
    } else {
        struct sockaddr_in *sa = (struct sockaddr_in *)result;
        memset(sa, 0, sizeof(*sa));
        sa->sin_family = AF_INET;
        inet_pton(AF_INET, "127.0.0.1", &sa->sin_addr);
        sa->sin_port = htons(port);
    }
    free(nameonly);
}
