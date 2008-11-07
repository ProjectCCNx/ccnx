/*
 * Copyright (c) 2001  Motoyuki Kasahara
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the project nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE PROJECT AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE PROJECT OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 */

#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

#include <sys/types.h>
#include <stdio.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>

#ifdef HAVE_STRING_H
#include <string.h>
#endif

#ifdef HAVE_STDLIB_H
#include <string.h>
#endif

#include "getaddrinfo.h"

void
test_getaddrinfo(nodename, servname, flags)
    const char *nodename;
    const char *servname;
    int flags;
{
    struct addrinfo hints;
    struct sockaddr_in *sa_in;
    struct addrinfo *res;
    int ecode;

    printf("====\n");
    printf("nodename=%s, servname=%s\n",
	(nodename == NULL) ? "(null)" : nodename, 
	(servname == NULL) ? "(null)" : servname);
    printf("flags=");
    if (flags & AI_NUMERICHOST)
	printf("NUMERICHOST ");
    if (flags & AI_CANONNAME)
	printf("CANONNAME ");
    if (flags & AI_PASSIVE)
	printf("PASSIVE ");
    printf("\n");

    memset(&hints, 0, sizeof(struct addrinfo));
    hints.ai_family = AF_INET;
    hints.ai_flags = flags;
    
    ecode = getaddrinfo(nodename, servname, &hints, &res);

    if (ecode != 0) {
	printf("	error: %s\n", gai_strerror(ecode));
	return;
    }
    if (res->ai_canonname != NULL)
	printf("	canonname = %s\n", res->ai_canonname);
    else
	printf("	canonname = (null)\n");

    sa_in = (struct sockaddr_in *)res->ai_addr;
    printf("	host = %s\n", inet_ntoa(sa_in->sin_addr));
    printf("	port = %d\n", ntohs(sa_in->sin_port));

    freeaddrinfo(res);
}

#define TEST_HOSTNAME 		"localhost"
#define TEST_HOSTNAME_NONE	"not-exist"
#define TEST_HOSTADDR		"127.0.0.1"
#define TEST_HOSTADDR_NONE	"255.255.255.254"
#define TEST_SERVNAME		"telnet"
#define TEST_SERVNAME_NONE	"not-exist"
#define TEST_SERVPORT		"512"	/* "exec" on TCP, "biff" on UDP */
#define TEST_SERVPORT_NONE	"65534"

int
main(argc, argv)
    int argc;
    char *argv[];
{
    static int flags_array[] = {
	0,
	AI_PASSIVE,
	AI_CANONNAME,
	AI_NUMERICHOST | AI_NUMERICSERV,
	AI_PASSIVE | AI_CANONNAME,
	AI_PASSIVE | AI_NUMERICHOST  | AI_NUMERICSERV,
	AI_CANONNAME | AI_NUMERICHOST | AI_NUMERICSERV,
	AI_PASSIVE | AI_CANONNAME | AI_NUMERICHOST | AI_NUMERICSERV,
	-1
    };
    int i;

    test_getaddrinfo(TEST_HOSTNAME,      NULL, 0);
    test_getaddrinfo(TEST_HOSTNAME_NONE, NULL, 0);

    for (i = 0; flags_array[i] >= 0; i++) {
	test_getaddrinfo(TEST_HOSTNAME,      NULL, flags_array[i]);
	test_getaddrinfo(TEST_HOSTNAME_NONE, NULL, flags_array[i]);
	test_getaddrinfo(TEST_HOSTADDR,      NULL, flags_array[i]);
	test_getaddrinfo(TEST_HOSTADDR_NONE, NULL, flags_array[i]);

	test_getaddrinfo(NULL, TEST_SERVNAME,      flags_array[i]);
	test_getaddrinfo(NULL, TEST_SERVNAME_NONE, flags_array[i]);
	test_getaddrinfo(NULL, TEST_SERVPORT,      flags_array[i]);
	test_getaddrinfo(NULL, TEST_SERVPORT_NONE, flags_array[i]);
    }

    return 0;
}
