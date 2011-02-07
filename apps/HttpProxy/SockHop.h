/**
 * @file SockHop.h
 * @brief Simple routines for sockets.
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

#ifndef SockHop_H
#define SockHop_H

#include <stdio.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>

#include "./ProxyUtil.h"

typedef struct SockBaseStruct *SockBase;
typedef struct SockEntryStruct *SockEntry;
typedef struct SockAddrStruct *SockAddr;


struct SockEntryStruct {
	SockBase base;
	SockEntry next;
	TimeMarker startTime;
	TimeMarker lastUsed;
	int fd;
	int owned;
	int forceClose;
	int keepAlive;
	int readActive;
	int writeActive;
	int errCount;
	struct sockaddr_storage addr;
	char *host;
	char *kind;
	int port;
	void *clientData;
};

struct SockAddrStruct {
	SockAddr next;
	char *host;
	char *kind;
	int port;
	TimeMarker startTime;
	TimeMarker lastUsed;
	struct sockaddr_storage addr;
};

struct SockBaseStruct {
	TimeMarker startTime;
	FILE *debug;
	int nSocks;
	SockEntry list;
	int nAddrs;
	SockAddr addrCache;
	int fdLen;
	fd_set readFDS;
	fd_set writeFDS;
	fd_set errorFDS;
	int robustTimeout;
	struct timeval selectTimeout;
	void *clientData;
};

// External routines for SockEntry objects

extern SockEntry
SH_NewSockEntry(SockBase base, int sockFD);

extern SockEntry
SH_FindSockEntry(SockBase base, int sockFD);

extern SockEntry
SH_FindSockEntryForAddr(SockBase base,
						struct sockaddr *sap,
						int owned);

extern SockEntry
SH_NewSockEntryForAddr(SockBase base, struct sockaddr *sap);

extern SockEntry
SH_FindSockEntryForName(SockBase base,
						char *host, char *kind, int port,
						int owned);

extern int
SH_CountSockEntryOwned(SockBase base,
					   char *host, char *kind, int port);

extern SockEntry
SH_NewSockEntryForName(SockBase base, char *host, char *kind, int port);

extern struct sockaddr *
SH_GetSockEntryAddr(SockEntry se);

extern void
SH_SetNoDelay(SockEntry se);

extern int
SH_TryConnect(SockEntry se);

extern void
SH_CloseConnection(SockEntry se);

extern SockEntry
SH_Destroy(SockEntry se);

extern SockBase
SH_NewSockBase(void);

extern void
SH_PruneAddrCache(SockBase base, int ageSecs, int usedSecs);
// prunes addrCache entries that are older than ageSecs seconds since creation
// OR have not been used in in usedSecs seconds

extern int
SH_CheckTimeouts(SockBase base);

extern SockBase
SH_DestroySockBase(SockBase);

extern void
SH_PrepSelect(SockBase base, uint64_t timeoutUsecs);

extern int
SH_DoSelect(SockBase base);

extern ssize_t
SH_RobustRecvmsg(SockEntry se, struct msghdr *mp);

extern ssize_t
SH_RobustSendmsg(SockEntry se, struct msghdr *mp);

extern int
SH_RobustConnect(SockEntry se, struct sockaddr *sap);

extern int
SH_RobustAccept(SockEntry se);

extern double
SH_TimeAlive(SockEntry se);

// Some useful utilities for addresses

extern int
SH_PrintSockAddr(FILE *f, struct sockaddr *sap);

extern int
SH_CopySockAddr(SockBase base, struct sockaddr *dst, struct sockaddr *src);

extern int
SH_CmpSockAddr(SockBase base, struct sockaddr *sa1, struct sockaddr *sa2);

#endif
