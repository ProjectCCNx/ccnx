/**
 * @file ccn_sockcreate.c
 * Setting up a socket from a text-based description.
 */

#include <stdlib.h>
#include <stdarg.h>
#include <errno.h>
#include <string.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <net/if.h>
#include <netdb.h>

#include <ccn/sockcreate.h>

#if defined(NEED_GETADDRINFO_COMPAT)
    #include "getaddrinfo.h"
    #include "dummyin6.h"
#endif


#define LOGGIT if (logger) (*logger)
/**
 * Utility for setting up a socket (or pair of sockets) from a text-based
 * description.
 * 
 * @param descr hold the information needed to create the socket(s).
 * @param logger should be used for reporting errors, printf-style.
 * @param logdat must be passed as first argument to logger().
 * @param socks should be filled in with the pair of socket file descriptors.
 * @returns 0 for success, -1 for error.
 */
int
ccn_setup_socket(const struct ccn_sockdescr *descr,
                 void (*logger)(void *, const char *, ...),
                 void *logdat,
                 struct ccn_sockets *socks)
{
    int result = -1;
    char *cp = NULL;
    struct addrinfo hints = {0};
    int res;
    struct addrinfo *mcast_source_addrinfo = NULL;
    unsigned int if_index = 0;
    const char *source_port = "4485"; //< @bug XXX FIXTHIS - should be passwd in (not in descr) ?
    
    if (descr->port == NULL ||
        strspn(descr->port, "0123456789") != strlen(descr->port)) {
        LOGGIT(logdat, "must specify numeric port");
        goto Finish;
    }
    if (descr->source_address != NULL) {
	hints.ai_family = PF_INET;
	hints.ai_socktype = SOCK_DGRAM;
	hints.ai_flags =  AI_NUMERICHOST;
	res = getaddrinfo(descr->source_address, source_port, &hints, &mcast_source_addrinfo);
	if (res != 0 || mcast_source_addrinfo == NULL) {
	    LOGGIT(logdat, "getaddrinfo(\"%s\", ...): %s\n",
                   descr->source_address, gai_strerror(res));
            goto Finish;
	}
    }
    if (descr->mcast_ttl >= 0) {
        if (descr->mcast_ttl < 1 || descr->mcast_ttl > 255) {
            LOGGIT(logdat, "mcast_ttl(%d) out of range", descr->mcast_ttl);
            goto Finish;
        }
    }
    if (descr->address == NULL) {
        if (logger)
                ((*logger)(logdat, "must specify remote address\n"));
        goto Finish;
    }
#ifdef IPV6_JOIN_GROUP
    cp = strchr(descr->address, '%');
    if (cp != NULL) {
        cp++;
        errno = 0;
        if_index = atoi(cp);
        if (if_index == 0) {
            if_index = if_nametoindex(cp);
            if (if_index == 0 && errno != 0) {
                LOGGIT(logdat, "Invalid interface name %s", cp);
                goto Finish;
            }
        }
    }
#endif
Finish:
    if (mcast_source_addrinfo != NULL)
        freeaddrinfo(mcast_source_addrinfo);
    return(result);
}

