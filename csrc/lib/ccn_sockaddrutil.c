/**
 * @file ccn_sockaddrutil.c
 * @brief sockaddr utilities
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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
 
#include <string.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <ccn/charbuf.h>
#include <ccn/sockaddrutil.h>

/**
 * Append a printable representation of sa (sans any port info) to the charbuf.
 *
 * IPv6 addresses will be enclosed in square braces, as in the host part
 * of a URI.
 * @returns the port number (0 if no port number is available), or -1 for
 *        in case of an error.
 */
int
ccn_charbuf_append_sockaddr(struct ccn_charbuf *c, const struct sockaddr *sa)
{
    const unsigned char *rawaddr = NULL;
    const char *s = NULL;
    const char *closer = "";
    const struct sockaddr_in *addr4 = NULL;
    const struct sockaddr_in6 *addr6 = NULL;
    size_t savlen = c->length;
    socklen_t sz = 80;
    int port = 0;
    
    if (sa == NULL)
        return(-1);
    switch (sa->sa_family) {
        case AF_INET:
            addr4 = (struct sockaddr_in *)sa;
            rawaddr = (const unsigned char *)&addr4->sin_addr.s_addr;
            port = htons(addr4->sin_port);break;
        case AF_INET6:
            addr6 = (struct sockaddr_in6 *)sa;
            rawaddr = (const unsigned char *)&addr6->sin6_addr;
            port = htons(addr6->sin6_port);
            ccn_charbuf_append_string(c, "[");
            closer = "]";
            break;
        default:
            return(-1);
    }
    s = inet_ntop(sa->sa_family, rawaddr, 
                  (void *)ccn_charbuf_reserve(c, sz), sz);
    if (s == NULL) {
        c->length = savlen;
        return(-1);
    }
    c->length += strlen(s);
    ccn_charbuf_append_string(c, closer);
    return(port);
}
