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
test_getnameinfo(addr, port, host, hostlen, serv, servlen, flags)
    const char *addr;
    int port;
    char *host;
    socklen_t hostlen;
    char *serv;
    socklen_t servlen;
    int flags;
{
    struct sockaddr_in sa;
    int ecode;

    printf("====\n");
    printf("sa->sin_addr=%s, sa->sin_port=%d\n", addr, port);
    printf("host=%s, hostlen=%d, serv=%s, servlen=%d\n",
	(host == NULL) ? "(null)" : "(non-null)", hostlen,
	(serv == NULL) ? "(null)" : "(non-null)", servlen);
    printf("flags=");
    if (flags & NI_NUMERICHOST)
	printf("NUMERICHOST ");
    if (flags & NI_NUMERICSERV)
	printf("NUMERICSERV ");
    if (flags & NI_DGRAM)
	printf("DGRAM ");
    if (flags & NI_NAMEREQD)
	printf("NAMEREQD ");
    printf("\n");

    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = inet_addr(addr);
    sa.sin_port = htons(port);
    ecode = getnameinfo((struct sockaddr *)&sa, sizeof(struct sockaddr_in),
	host, hostlen, serv, servlen, flags);

    if (ecode != 0) {
	printf("	error: %s\n", gai_strerror(ecode));
	return;
    }
    if (host != NULL)
	printf("	host = %s\n", host);
    else
	printf("	host = (null)\n");
    if (serv != NULL)
	printf("	serv = %s\n", serv);
    else
	printf("	serv = (null)\n");
}

#define TEST_HOSTNAME 		"localhost"
#define TEST_HOSTNAME_NONE	"not-exist"
#define TEST_HOSTADDR		"127.0.0.1"
#define TEST_HOSTADDR_NONE	"255.255.255.254"
#define TEST_SERVNAME		"telnet"
#define TEST_SERVNAME_NONE	"not-exist"
#define TEST_SERVPORT		512	/* "exec" on TCP, "biff" on UDP */
#define TEST_SERVPORT_NONE	65534

int
main(argc, argv)
    int argc;
    char *argv[];
{
    static int flags_array[] = {
	0,
	NI_NUMERICHOST | NI_NUMERICSERV,
	NI_NAMEREQD,
	NI_NUMERICHOST | NI_NUMERICSERV | NI_NAMEREQD,
	-1
    };
    char host[512];
    char serv[512];
    int i;

    test_getnameinfo(TEST_HOSTADDR, TEST_SERVPORT, host, 512, serv, 512,
	0);
    test_getnameinfo(TEST_HOSTADDR, TEST_SERVPORT, NULL, 512, NULL, 512,
	0);

    /*
     * TEST_HOSTADDR test.
     */
    for (i = 0; flags_array[i] >= 0; i++) {
	test_getnameinfo(TEST_HOSTADDR,      TEST_SERVPORT,
	    host, 512, NULL, 512, flags_array[i]);
	test_getnameinfo(TEST_HOSTADDR,      TEST_SERVPORT,
	    host, 1, NULL, 512, flags_array[i]);
	test_getnameinfo(TEST_HOSTADDR_NONE, TEST_SERVPORT,
	    host, 512, NULL, 512, flags_array[i]);
	test_getnameinfo(TEST_HOSTADDR_NONE, TEST_SERVPORT,
	    host, 1, NULL, 512, flags_array[i]);

	test_getnameinfo(TEST_HOSTADDR, TEST_SERVPORT,
	    NULL, 0, serv, 512, flags_array[i]);
	test_getnameinfo(TEST_HOSTADDR, TEST_SERVPORT,
	    NULL, 0, serv, 1, flags_array[i]);
	test_getnameinfo(TEST_HOSTADDR, TEST_SERVPORT_NONE,
	    NULL, 0, serv, 512, flags_array[i]);
	test_getnameinfo(TEST_HOSTADDR, TEST_SERVPORT_NONE,
	    NULL, 0, serv, 1, flags_array[i]);
    }

    return 0;
}
