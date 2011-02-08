/**
 * @file SockHop.c
 * @brief Simple routines for sockets.
 *
 * Copyright (C) 2010, 2011 Palo Alto Research Center, Inc.
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

#include "./SockHop.h"
#include "./ProxyUtil.h"

#include <sys/select.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>

#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <strings.h>
#include <time.h>
#include <unistd.h>

#define RobustMillis 20

static int
Gleep(FILE *f, char *where) {
	if (f == NULL) f = stdout;
	fprintf(f, "** Error: bad addr found in %s\n", where);
	return -1;
}

static int
SockAddrLen(struct sockaddr *sap) {
	sa_family_t fam = sap->sa_family;
	if (fam == PF_INET) return sizeof(struct sockaddr_in);
	if (fam == PF_INET6) return sizeof(struct sockaddr_in6);
	return 0;
}

static int
InnerConnect(SockBase base, struct sockaddr *sap) {
	int fd = socket(sap->sa_family,
					SOCK_STREAM,
					IPPROTO_TCP);
	if (fd >= 0) {
		// we've got a socket, so try for a connection
		int timeout = base->robustTimeout;
		uint64_t start = GetCurrentTime();
		for (;;) {
			int connRes = connect(fd, sap, SockAddrLen(sap));
			if (connRes >= 0) {
				// success!
				return fd;
			}
			MilliSleep(RobustMillis);
			uint64_t now = GetCurrentTime();
			double dt = DeltaTime(start, now);
			int e = errno;
			if (dt > timeout || (e != EAGAIN && e != EINTR)) {
				// failed, so give back the socket and report the failure
				close(fd);
				return connRes;
			}
		}
	}
	return fd;
}

///////////////////////////////////////////////////////
// External routines
///////////////////////////////////////////////////////

extern int
SH_PrintSockAddr(FILE *f, struct sockaddr *sap) {
	sa_family_t fam = sap->sa_family;
	if (fam == PF_INET) {
		struct sockaddr_in * sa4 = (struct sockaddr_in *) sap;
		uint8_t * p = (uint8_t *) &sa4->sin_addr;
		fprintf(f,
				"%d.%d.%d.%d:%d",
				p[0] & 255, p[1] & 255,
				p[2] & 255, p[3] & 255,
				ntohs(sa4->sin_port));
	} else if (fam == PF_INET6) {
		struct sockaddr_in6 * sa6 = (struct sockaddr_in6 *) sap;
		uint8_t * p = (uint8_t *) &sa6->sin6_addr;
		int i = 0;
		for (; i < 16; i++) {
			uint8_t b = p[i];
			if (i > 0) fprintf(f, ":");
			if (b != 0) fprintf(f, "%x", b);
		}
		fprintf(f, "!%d", ntohs(sa6->sin6_port));
	} else {
		return Gleep(f, "SH_PrintSockAddr");
	}
	return 0;
}

extern int
SH_CopySockAddr(SockBase base, struct sockaddr *dst, struct sockaddr *src) {
	int len = SockAddrLen(src);
	if (len <= 0) {
		return Gleep(base->debug, "SH_CopySockAddr");
	}
	memcpy(dst, src, len);
	return 0;
}

extern int
SH_CmpSockAddr(SockBase base, struct sockaddr *sa1, struct sockaddr *sa2) {
	sa_family_t fam = sa1->sa_family;
	if (fam != sa2->sa_family) return (fam - sa2->sa_family);
	int len1 = SockAddrLen(sa1);
	int len2 = SockAddrLen(sa2);
	if (len1 <= 0 || len2 <= 0) {
		return Gleep(base->debug, "SH_CmpSockAddr");
	}
	int delta = len1-len2;
	if (delta != 0) return delta;
	return memcmp(sa1, sa2, len1);
}

extern SockBase
SH_NewSockBase(void) {
	SockBase base = ProxyUtil_StructAlloc(1, SockBaseStruct);
	base->startTime = GetCurrentTime();
	base->robustTimeout = 10;
	return base;
}

extern int
SH_CheckTimeouts(SockBase base) {
	SockEntry se = base->list;
	int count = 0;
	for (;;) {
		if (se == NULL) break;
		SockEntry next = se->next;
		if (se->owned == 0) {
			if (se->forceClose || SH_TimeAlive(se) > se->keepAlive) {
				SH_Destroy(se);
				count++;
			}
		}
		se = next;
	}
	return count;
}

extern SockBase
SH_DestroySockBase(SockBase base) {
	for (;;) {
		SockEntry se = base->list;
		if (se == NULL) break;
		SH_Destroy(se);
	}
	SH_PruneAddrCache(base, 0, 0);
	free(base);
	return NULL;
}

/**
 * Prepares for SH_DoSelect, setting the timeout and clearing the
 * FDS vectors.  Prior to the SH_DoSelect one can add more FD's to the
 * FDS vectors (and set fdLen), which allows the SH_DoSelect call to wakeup
 * for other descriptors (e.g. the CCN handle socket). 
 */
extern void
SH_PrepSelect(SockBase base, uint64_t timeoutUsecs) {
	// prepare for a select
	// this is 2-phase to enable addition of external FD's to the select
	FD_ZERO(&base->readFDS);
	FD_ZERO(&base->writeFDS);
	FD_ZERO(&base->errorFDS);
	base->selectTimeout.tv_sec = (timeoutUsecs / 1000000);
	base->selectTimeout.tv_usec = (timeoutUsecs % 1000000);
	base->fdLen = 0;
}

/**
 * Sets up the FDS vectors (and fdLen) based on the existing sockets, then
 * performs a select call with the timeout provided by SH_PrepSelect.
 * Instantly returns 0 if no sockets are registered (and fdLen == 0).
 * @returns the result of the select call.
 */
extern int
SH_DoSelect(SockBase base) {
	// do a select based on the existing sockets
	int maxFD = -1;
	SockEntry se = base->list;
	while (se != NULL) {
		SockEntry next = se->next;
		int fd = se->fd;
		if (fd >= 0) {
			if (se->owned > 0) {
				// determine the enable bits
				if (fd > maxFD) maxFD = fd;
				if (se->readActive == 0) FD_SET(fd, &base->readFDS);
				if (se->writeActive > 0) FD_SET(fd, &base->writeFDS);
				FD_SET(fd, &base->errorFDS);
			} else {
				if (se->forceClose || SH_TimeAlive(se) >= se->keepAlive) {
					// enforce connection timeouts
					SH_Destroy(se);
				}
			}
		}
		se = next;
	}
	if (maxFD < 0) return 0;
	maxFD++;
	if (maxFD > base->fdLen)
		base->fdLen = maxFD;
	int res = select(base->fdLen,
					 &base->readFDS,
					 &base->writeFDS,
					 &base->errorFDS,
					 &base->selectTimeout
					 );
	return res;
}

/**
 * @returns a pointer to the address.
 */
extern struct sockaddr *
SH_GetSockEntryAddr(SockEntry se) {
	return (struct sockaddr *) &se->addr;
}

static SockEntry
SH_NewSockEntryNoCheck(SockBase base, int sockFD) {
	SockEntry ret = ProxyUtil_StructAlloc(1, SockEntryStruct);
	ret->base = base;
	ret->next = base->list;
	ret->fd = sockFD;
	ret->startTime = GetCurrentTime();
	ret->lastUsed = ret->startTime;
	base->list = ret;
	base->nSocks++;
	return ret;
}

/**
 * Finds an existing SockEntry for the given sockFD.
 * @returns the SockEntry if successful, otherwise NULL.
 */
extern SockEntry
SH_FindSockEntry(SockBase base, int sockFD) {
	if (sockFD < 0) return NULL;
	SockEntry se = base->list;
	while (se != NULL) {
		if (se->fd == sockFD) return se;
		se = se->next;
	}
	return NULL;
}

/**
 * Creates a new SockEntry for the given sockFD.
 * The given sockFD must not already be in the base list,
 * and we require that sockFD >= 0.
 * @returns the SockEntry if successful, otherwise NULL.
 * Also, se->owner == 0 && se->keepAlive == 0. 
 */
extern SockEntry
SH_NewSockEntry(SockBase base, int sockFD) {
	// initially check for invalid sockFD OR existing entry for the sockFD
	if (sockFD < 0) return NULL;
	SockEntry se = base->list;
	while (se != NULL) {
		if (se->fd == sockFD) return NULL;
		se = se->next;
	}
	SockEntry ret = SH_NewSockEntryNoCheck(base, sockFD);
	return ret;
}

/**
 * Finds an existing SockEntry for the address, which must match exactly.
 * The owned flag must also match, and forceClose must be 0, and the socket
 * must be open.
 * @returns the SockEntry if successful, otherwise NULL.
 */
extern SockEntry
SH_FindSockEntryForAddr(SockBase base, struct sockaddr *sap, int owned) {
	// find an existing entry for an address
	// never return an se where se->forceClose != 0
	SockEntry se = base->list;
	while (se != NULL) {
		struct sockaddr *sep = (struct sockaddr *) &se->addr;
		if (((owned <= 0) ? se->owned <= 0 : se->owned > 0)
			&& se->forceClose == 0
			&& se->fd >= 0
			&& SH_CmpSockAddr(base, sep, sap) == 0)
			return se;
		se = se->next;
	}
	return NULL;
}

/**
 * Creates a new SockEntry for the address, which is not checked.  No
 * connection will be attempted.  The new SockEntry will be chained into
 * the base.
 * @returns the new SockEntry.
 */
extern SockEntry
SH_NewSockEntryForAddr(SockBase base, struct sockaddr *sap) {
	// allocate a sock entry for the address
	// we do NOT yet have a socket FD or a host or clientData
	// read and write states start out null
	// duplicates are OK in the list
	SockEntry ret = SH_NewSockEntryNoCheck(base, -1);
	struct sockaddr *sep = (struct sockaddr *) &ret->addr;
	SH_CopySockAddr(base, sep, sap);
	return ret;
}

/**
 * Tries to make a socket connection for the SockEntry, based upon the address
 * already set.  Has no effect if the connection is already open.
 * @returns the fd if successful, otherwise a negative number (usually -1).
 */
extern int
SH_TryConnect(SockEntry se) {
	int fd = se->fd;
	if (fd >= 0) return fd;
	fd = InnerConnect(se->base, (struct sockaddr *) &se->addr);
	if (fd >= 0) {
		// we were able to connect
		se->fd = fd;
	}
	return fd;
}

/**
 * Finds an existing SockEntry for the given host, kind, and port.  The host
 * and kind must exactly match (case insensitive).  Both host and kind should
 * not be NULL.  If (port > 0) then the port must match, otherwise the first
 * host and kind match will be used.  The owned flag must match, and the socket
 * must be open.
 * @returns the existing SockEntry if successful, NULL otherwise.  Never returns
 * a SockEntry with forceClose != 0.
 */
extern SockEntry
SH_FindSockEntryForName(SockBase base, char *host, char *kind,
						int port, int owned) {
	// find an existing SockEntry by name
	// never return an se where se->forceClose != 0
	if (host == NULL) return NULL;
	if (kind == NULL) return NULL;
	SockEntry se = base->list;
	while (se != NULL) {
		SockEntry next = se->next;
		if (((owned <= 0) ? se->owned <= 0 : se->owned > 0)
			&& se->forceClose == 0 && se->fd >= 0
			&& strcasecmp(host, se->host) == 0
			&& strcasecmp(kind, se->kind) == 0
			&& (port <= 0 || port == se->port)) {
			return se;
		}
		se = next;
	}
	return NULL;
}

/**
 * @returns the count of owned sockets for the given host/kind/port.  Timeout
 * testing is not performed.
 */
extern int
SH_CountSockEntryOwned(SockBase base,
					   char *host, char *kind, int port) {
	// find an existing SockEntry by name
	// never return an se where se->forceClose != 0
	int count = 0;
	if (host == NULL) return 0;
	if (kind == NULL) return 0;
	SockEntry se = base->list;
	while (se != NULL) {
		if ((se->owned > 0)
			&& strcasecmp(host, se->host) == 0
			&& strcasecmp(kind, se->kind) == 0
			&& (port <= 0 || port == se->port))
			count++;
		se = se->next;
	}
	return count;
}

/**
 * Creates a new SockEntry for the given host, kind, and port.  The host is
 * looked up using getaddrinfo, which generates a list of IP addresses.  The
 * first IP address that we can connect to is used to generate the new
 * SockEntry.  Both IPv4 and IPv6 addresses are supported.  The port is used as
 * an override (if port > 0) for the port number implied by the kind field.
 * @returns the new SockEntry if successful, NULL otherwise.
 */
extern SockEntry
SH_NewSockEntryForName(SockBase base, char *host, char *kind, int port) {
	struct addrinfo *info = NULL;
	struct addrinfo hints;
	struct sockaddr_storage tempAddr;
	SockEntry se = NULL;
	int fd = -1;
	struct sockaddr *sap = (struct sockaddr *) &tempAddr;
	memset(&tempAddr, 0, sizeof(tempAddr));
	memset(&hints, 0, sizeof(hints));
	hints.ai_protocol = IPPROTO_TCP;
	SockAddr ac = NULL;
	
	// first, search the address cache
	for (ac = base->addrCache; ac != NULL; ac = ac->next) {
		if (SameHost(host, ac->host)
			&& SameHost(kind, ac->kind)
			&& port == ac->port) {
			// matching names, try to use the address
			struct sockaddr *ap = (struct sockaddr *) &ac->addr;
			fd = InnerConnect(base, ap);
			if (fd >= 0) {
				ac->lastUsed = GetCurrentTime();
				SH_CopySockAddr(base, sap, ap);
			}
		}
	}
	
	if (fd < 0) {
		// no matching cache entry, so try getaddrinfo
		
		int gaRes = getaddrinfo(host, kind, &hints, &info);
		if (gaRes < 0 || info == NULL) {
			// no such address for this kind
			return NULL;
		}
		
		// we have a list of possible addresses, try to open them in order
		int i = 0;
		for (; i < info->ai_addrlen; i++) {
			int gaRes = 0;
			struct sockaddr *tap = &info->ai_addr[i];
			if (tap->sa_family == PF_INET) {
				// IPv4 address
				memcpy(sap, tap, sizeof(struct sockaddr_in));
				struct sockaddr_in *sap4 = (struct sockaddr_in *) sap;
				if (port > 0)
					sap4->sin_port = htons(port);
				gaRes = 1;
			} else if (tap->sa_family == PF_INET6) {
				// IPv6 address
				memcpy(sap, tap, sizeof(struct sockaddr_in6));
				struct sockaddr_in6 *sap6 = (struct sockaddr_in6 *) sap;
				if (port > 0)
					sap6->sin6_port = htons(port);
				gaRes = 1;
			} else {
				// unsupported, so skip this address
			}
			if (gaRes) {
				// this address is worth trying
				fd = InnerConnect(base, sap);
				if (fd >= 0) {
					// we have a successful connection, so make a new cache entry
					ac = ProxyUtil_StructAlloc(1, SockAddrStruct);
					ac->next = base->addrCache;
					base->addrCache = ac;
					base->nAddrs++;
					ac->host = Concat(host, "");
					ac->kind = Concat(kind, "");
					ac->port = port;
					ac->startTime = GetCurrentTime();
					ac->lastUsed = ac->startTime;
					struct sockaddr *sep = (struct sockaddr *) &ac->addr;
					SH_CopySockAddr(base, sep, sap);
					break;
				}
			}
		}
		freeaddrinfo(info);
	}
	
	if (fd >= 0) {
		// we have a successful connection, so make the SockEntry to return
		se = SH_NewSockEntryForAddr(base, sap);
		se->fd = fd;
		se->host = Concat(host, "");
		se->kind = Concat(kind, "");
		se->port = port;
	}
	
	return se;
}


/**
 * Closes the underlying connection, regardless of its state.  Closing a closed
 * connection has no effect.  The forceClose and owned flags will be set to 0.
 * The entry will remain in the list.
 */
extern void
SH_CloseConnection(SockEntry se) {
	int fd = se->fd;
	se->fd = -1;
	if (fd >= 0) close(fd);
	se->forceClose = 0;
	se->owned = 0;
}

extern void
SH_PruneAddrCache(SockBase base, int ageSecs, int usedSecs) {
	SockAddr lag = NULL;
	SockAddr ac = base->addrCache;
	TimeMarker now = GetCurrentTime();
	while (ac != NULL) {
		SockAddr next = ac->next;
		double dAge = DeltaTime(ac->startTime, now);
		double dUse = DeltaTime(ac->lastUsed, now);
		if (dAge > ageSecs || dUse > usedSecs) {
			double dt = DeltaTime(base->startTime, now);
			// got a victim
			if (lag == NULL) base->addrCache = next;
			else lag->next = next;
			if (base->debug != NULL)
				fprintf(base->debug,
						"@%4.3f, SH_PruneAddrCache, %s, aged %4.1f, used %4.1f\n",
						dt, ac->host, dAge, dUse);
			Freestr(ac->host);
			Freestr(ac->kind);
			free(ac);
			base->nAddrs--;
		} else lag = ac;
		ac = next;
	}
}

/**
 * Closes the underlying connection, regardless of its state.  Closing a closed
 * connection has no effect.  Also removes this entry from the list
 * and reclaims the storage.
 */
extern SockEntry
SH_Destroy(SockEntry se) {
	if (se != NULL) {
		SockBase base = se->base;
		SH_CloseConnection(se);
		SockEntry each = base->list;
		if (se == each) {
			// head of the list is easy
			base->list = se->next;
			se->next = NULL;
		} else {
			for (;;) {
				SockEntry next = each->next;
				if (next == se) {
					each->next = se->next;
					se->next = NULL;
					break;
				}
				each = next;
				if (each == NULL) break;
			}
			
		}
		base->nSocks--;
		se->host = Freestr(se->host);
		se->kind = Freestr(se->kind);
		free(se);
	}
	return NULL;
}

/**
 * Sets the socket to have no delay.  No effect if not connected.
 */
extern void
SH_SetNoDelay(SockEntry se) {
	int xopt = 1;
	if (se->fd >= 0)
		setsockopt(se->fd, IPPROTO_TCP, TCP_NODELAY, &xopt, sizeof(xopt));
}

/**
 * Performs a robust recmsg, restarting when interrupted.
 * Requires a connection.
 */
extern ssize_t
SH_RobustRecvmsg(SockEntry se, struct msghdr *mp) {
	int timeout = se->base->robustTimeout;
	se->readActive = 1;
	uint64_t start = GetCurrentTime();
	for (;;) {
		ssize_t nb = recvmsg(se->fd, mp, 0);
		if (nb >= 0) {
			se->readActive = 0;
			se->lastUsed = GetCurrentTime();
			return nb;
		}
		int e = errno;
		if (e != EAGAIN && e != EINTR) break;
		MilliSleep(RobustMillis);
		uint64_t now = GetCurrentTime();
		double dt = DeltaTime(start, now);
		if (dt > timeout) break;
	}
	se->readActive = 0;
	se->errCount++;
	return -1;
}

/**
 * Performs a robust sendmsg, restarting when interrupted.
 * Requires a connection.
 */
extern ssize_t
SH_RobustSendmsg(SockEntry se, struct msghdr *mp) {
	int timeout = se->base->robustTimeout;
	uint64_t start = GetCurrentTime();
	for (;;) {
		ssize_t nb = sendmsg(se->fd, mp, 0);
		if (nb >= 0) {
			se->writeActive = 1;
			se->lastUsed = GetCurrentTime();
			return nb;
		}
		int e = errno;
		if (e != EAGAIN && e != EINTR) break;
		MilliSleep(RobustMillis);
		uint64_t now = GetCurrentTime();
		double dt = DeltaTime(start, now);
		if (dt > timeout) break;
	}
	se->errCount++;
	return -1;
}

/**
 * Performs a robust connect, restarting when interrupted.
 * Requires a valid socket (se->fd >= 0).
 */
extern int
SH_RobustConnect(SockEntry se, struct sockaddr *sap) {
	int timeout = se->base->robustTimeout;
	uint64_t start = GetCurrentTime();
	for (;;) {
		int connRes = connect(se->fd, sap, SockAddrLen(sap));
		if (connRes >= 0) {
			se->lastUsed = GetCurrentTime();
			return connRes;
		}
		int e = errno;
		if (e != EAGAIN && e != EINTR) break;
		MilliSleep(RobustMillis);
		uint64_t now = GetCurrentTime();
		double dt = DeltaTime(start, now);
		if (dt > timeout) break;
	}
	se->errCount++;
	return -1;
}

/**
 * Performs a robust accept, restarting when interrupted.
 * Requires a valid socket (se->fd >= 0).
 */
extern int
SH_RobustAccept(SockEntry se) {
	int timeout = se->base->robustTimeout;
	uint64_t start = GetCurrentTime();
	for (;;) {
		int connRes = accept(se->fd, NULL, NULL);
		if (connRes >= 0) {
			se->lastUsed = GetCurrentTime();
			return connRes;
		}
		int e = errno;
		if (e != EAGAIN && e != EINTR) break;
		MilliSleep(RobustMillis);
		uint64_t now = GetCurrentTime();
		double dt = DeltaTime(start, now);
		if (dt > timeout) break;
	}
	se->errCount++;
	return -1;
}

/**
 * @returns the seconds since the start.
 */
extern double
SH_TimeAlive(SockEntry se) {
	uint64_t now = GetCurrentTime();
	return DeltaTime(se->startTime, now);
}

/**
 * @returns the seconds since the last send/receive operation.
 */
extern double
SH_TimeSinceLastUsed(SockEntry se) {
	uint64_t now = GetCurrentTime();
	return DeltaTime(se->lastUsed, now);
}

