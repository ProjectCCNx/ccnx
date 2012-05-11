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
#include <netdb.h>
#include <sys/socket.h>
#include <sys/un.h>
#if defined(NEED_GETADDRINFO_COMPAT)
#include "getaddrinfo.h"
#include "dummyin6.h"
#endif
#ifndef AI_ADDRCONFIG
#define AI_ADDRCONFIG 0 /*IEEE Std 1003.1-2001/Cor 1-2002, item XSH/TC1/D6/20*/
#endif

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
 * @returns 0 on success, -1 on error
 */
int
ccn_setup_sockaddr_in(const char *name, struct sockaddr *result, int length)
{
    struct addrinfo hints = {0};
    struct addrinfo *ai = NULL;
    char *port;
    char *nameonly = strdup(name);
    int ans = -1;
    int res;

    port = strchr(nameonly, ':');
    if (port)
        *port++ = 0;
    if (port == NULL || port[0] == 0)
        port = getenv(CCN_LOCAL_PORT_ENVNAME);
    if (port == NULL || port[0] == 0)
        port = CCN_DEFAULT_UNICAST_PORT;
    memset(result, 0, length);
    hints.ai_family = AF_UNSPEC;
    if (strcasecmp(nameonly, "tcp6") == 0) hints.ai_family = AF_INET6;
    if (strcasecmp(nameonly, "tcp4") == 0) hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_flags = AI_ADDRCONFIG;
    hints.ai_protocol = 0;
    res = getaddrinfo(NULL, port, &hints, &ai);
    if (res != 0 || ai->ai_addrlen > length)
        goto Bail;
    memcpy(result, ai->ai_addr, ai->ai_addrlen);
    ans = 0;
Bail:
    free(nameonly);
    freeaddrinfo(ai);
    return (ans);
}
