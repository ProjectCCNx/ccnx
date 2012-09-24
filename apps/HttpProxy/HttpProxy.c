/*
 * HttpProxy/HttpProxy.c
 * 
 * A CCNx program.
 *
 * Copyright (C) 2010, 2011 Palo Alto Research Center, Inc.
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
 * Provides a proxy for HTTP that allows some traffic to be served via
 * the CCN protocol.
 */

#include "./ProxyUtil.h"
#include "./SockHop.h"
#include <ccn/fetch.h>

#include <sys/select.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

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


#define FetchBuffers 8
#define RobustMillis 200

#define CCN_CHUNK_SIZE 4096

#define BufferSize (4400*4)

typedef struct RequestBaseStruct *RequestBase;

typedef struct MainBaseStruct *MainBase;

typedef struct SelectDataStruct *SelectData;

struct SelectDataStruct {
	int fdLen;
	fd_set readFDS;
	fd_set writeFDS;
	fd_set errorFDS;
	struct timeval selectTimeout;
};

typedef struct ParseItemStruct *ParseItem;
struct ParseItemStruct {
	string buf;
	int strLen;
	int pos;
	int start;
	int len;
};

typedef enum {
	HostLine_None = 0,
	HostLine_NeedDot = 1,
	HostLine_NoCookie = 2,
	HostLine_NoReferer = 4,
	HostLine_NoQuery = 8,
	HostLine_SingleConn = 16,
	HostLine_Proxy = 32,
	HostLine_Translate = 64,
	HostLine_FailQuick = 256,
	HostLine_QueryHack = 512
} HostLineFlags;

typedef struct HostLineStruct *HostLine;
struct HostLineStruct {
	HostLine next;
	string pat;
	int patLen;
    string translate;
	HostLineFlags flags; 
};

struct StatsStruct {
	uint64_t requests;
	uint64_t replies;
	uint64_t repliesCCN;
	uint64_t replyReads;
	uint64_t replyBytes;
	uint64_t replyReadsCCN;
	uint64_t replyBytesCCN;
};

struct MainBaseStruct {
	FILE *debug;
    char *custom;
	int removeProxy;
	int removeHost;
	string ccnRoot;
	HostLine hostLines;
	double timeoutSecs;
	int defaultKeepAlive;
	int sockFD;
	int ccnFD;
    int usePort;
	struct ccn_fetch * fetchBase;
	SockEntry client;
	RequestBase requestList;
	SockBase sockBase;
    ccn_fetch_flags ccn_flags;
	int maxBusy;
	int maxConn;
	int nReady;
	int requestCount;
	int requestDone;
	int resolveFlags;
	int hostFromGet;
	uint64_t nChanges;
	uint64_t startTime;
	struct SelectDataStruct sds;
	struct StatsStruct stats;
};

typedef enum {
	HTTP_NONE,
	HTTP_HEAD,
	HTTP_GET,
	HTTP_POST,
	HTTP_PUT,
	HTTP_DELETE,
	HTTP_TRACE,
	HTTP_OPTIONS,
	HTTP_CONNECT
} HttpVerb;

struct ByteRange {
    struct ByteRange *next;
    ssize_t rangeStart;
    ssize_t rangeStop;
};

typedef struct HttpInfoStruct {
	HttpVerb httpVerb;
	int httpVersion;
	int httpSubVersion;
	int headerLen;
	int httpCode;
	int badHeader;
	int forceClose;
	int cookie;
    int64_t assertLength;       // asserted length from ContentLength:
    int hasRange;
    int hasContentLength;
    int hasContentRange;
    string contentType;
	struct ByteRange *rangeList;
	int queryHack;
	int hasReferer;
	int keepAlive;
	int proxyConn;
	int proxyKeepAlive;
	int transferEncoding;
	int transferChunked;
} *HttpInfo;


typedef enum {
	Chunk_None,			// not chunking
	Chunk_Done,			// chunking, found the end
	Chunk_Error,		// chunking, found an error
	Chunk_Skip,			// chunking, skipping forward
	Chunk_NeedNL1,		// chunking, found CR, need first NL
	Chunk_Accum,		// chunking, accum len
	Chunk_NeedNL2		// chunking, cound CR, need second NL
} ChunkState;

typedef struct ChunkInfoStruct {
	uint32_t chunkRem;
	uint32_t accum;
	int accumLen;
	ChunkState state;
	ChunkState prev;
} *ChunkInfo;

typedef enum {
	RB_None,
	RB_Start,
	RB_Wait,
	RB_NeedRead,
	RB_NeedWrite,
	RB_Error,
	RB_Done
} RequestBaseState;

struct RequestBaseStruct {
	MainBase mb;
	RequestBase next;
	RequestBase fwdPath;
	RequestBase backPath;
	RequestBaseState state;
	string request;
	string shortName;
	struct ccn_fetch_stream * fetchStream;
	int recvOff;
	int sendOff;
	int origin;
	int index;
	int maxConn;
	int removeHost;
	int rewriteHost;
	int forceFail;
	int fastOptions;
    int headerLenInit;         // initial header length (CheckHttpHeader)
    int headerLenReply;        // reply header length (CheckHttpHeader)
	HttpVerb parentVerb;
	int64_t accum;
	int64_t msgLen;
	int msgCount;
	int errorCount;
	struct ChunkInfoStruct chunkInfo;
	uint64_t startTime;
	uint64_t recentTime;
	uint64_t sockTime;
	struct HttpInfoStruct httpInfo;
	SockEntry seSrc;
	SockEntry seDst;
	HostLine hostLines;
	string host;
	string translate;
	int port;
    int fetchOff;
	void *buffer;
	int bufferLen;
	int bufferMax;
	struct msghdr msg;
	struct iovec iov;
};

////////////////////////////////
// Small utilities
////////////////////////////////

static void
flushLog(FILE *f) {
	if (f == NULL) f = stdout;
	fflush(f);
}

static int
retFail(MainBase mb, string msg) {
	string sysErr = strerror(errno);
	FILE *f = ((mb == NULL) ? NULL : mb->debug);
	if (f == NULL) f = stdout;
	fprintf(f, "** error: %s - %s\n", msg, sysErr);
	flushLog(f);
	return -1;
}

static int
retErr(MainBase mb, string msg) {
	FILE *f = ((mb == NULL) ? NULL : mb->debug);
	if (f == NULL) f = stdout;
	fprintf(f, "** error: %s\n", msg);
	flushLog(f);
	return -1;
}

////////////////////////////////
// string support
////////////////////////////////

static string globalNullString = "";

static string
newString(int n) {
	if (n <= 0) return globalNullString;
	string s = ProxyUtil_Alloc(n+1, char);
	return s;
}

static string
newStringPrefix(string src, int n) {
	if (n <= 0 || src == NULL) return globalNullString;
	string s = ProxyUtil_Alloc(n+1, char);
	if (src != NULL) strncpy(s, src, n);
	return s;
}

static string
newStringCopy(string src) {
	int n = ((src == NULL) ? 0 : strlen(src));
	if (n <= 0) return globalNullString;
	string s = ProxyUtil_Alloc(n+1, char);
	strncpy(s, src, n);
	return s;
}

static string
newStringCat(string x, string y) {
	int xn = ((x == NULL) ? 0 : strlen(x));
	int yn = ((y == NULL) ? 0 : strlen(y));
    int n = xn+yn;
	if (n <= 0) return globalNullString;
	string s = ProxyUtil_Alloc(n+1, char);
	if (xn > 0) strncpy(s, x, xn);
	if (yn > 0) strncpy(s+xn, y, yn);
	return s;
}

static string
freeString(string s) {
	if (s != NULL && s != globalNullString)
		free(s);
	return NULL;
}

static string
newDateString(void) {
    int pos = 0;
    time_t clk;
    struct tm tms;
    char ds[40];
    clk = time(NULL);
    gmtime_r(&clk, &tms);
    string dowArray[8] = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "??"};
    string monArray[13] = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        "??"};
    int wd = tms.tm_wday;
    int mon = tms.tm_mon;
    if (wd < 0 || wd > 7) wd = 7;
    if (mon < 0 || mon > 12) wd = 12;
    
    pos += snprintf(ds+pos, sizeof(ds)-pos, "%s, ", dowArray[wd]);
    pos += snprintf(ds+pos, sizeof(ds)-pos, "%02d %s %04d ",
                    tms.tm_mday, monArray[wd], tms.tm_year);
    pos += snprintf(ds+pos, sizeof(ds)-pos, "%02d:%02d:%02d GMT",
                    tms.tm_sec, tms.tm_min, tms.tm_hour);
    return newStringPrefix(ds, pos);
}

static void
SetRequestState(RequestBase rb, RequestBaseState state) {
	if (rb->state != RB_Error) rb->state = state;
	rb->mb->nChanges++;
}

static int
SetRequestErr(RequestBase rb, string msg, int err) {
	SetRequestState(rb, RB_Error);
	if (msg != NULL) {
		if (err != 0) {
			return retFail(rb->mb, msg);
		} else return retErr(rb->mb, msg);
	}
	return -1;
}

static HostLine
SelectHostSuffix(MainBase mb, string s) {
	HostLine h = mb->hostLines;
	int sLen = strlen(s);
	while (h != NULL) {
		string pat = h->pat;
		if (pat[0] == '*') {
			// suffix match
			int patLen = h->patLen-1;
			if (sLen >= patLen)
				if (SameHost(s+(sLen-patLen), pat+1))
					break;
			if (pat[1] == '.')
				if (SameHost(s, pat+2))
					break;
		} else {
			// exact match
			if (SameHost(s, pat))
				break;
		}
		h = h->next;
	}
	return h;
}


////////////////////////////////
// Print utilities
////////////////////////////////

static double
PutTimeMark(MainBase mb) {
	FILE *f = mb->debug;
	if (f == NULL) f = stdout;
	double dt = DeltaTime(mb->startTime, GetCurrentTime());
	fprintf(f, "@%4.3f, ", dt);
	return dt;
}

static void
PutRequestMark(RequestBase rb, string action) {
	FILE *f = rb->mb->debug;
	if (f == NULL) f = stdout;
	double dt = DeltaTime(rb->mb->startTime, GetCurrentTime());
	fprintf(f, "@%4.3f, #%d, %s", dt, rb->index, action);
}

#ifdef IncludeProxyDiag

static void
printMsgFlags(MainBase mb, int flags) {
	FILE *f = mb->debug;
	if (f == NULL) f = stdout;
	while (flags != 0) {
		if (flags & MSG_EOR) {
			fprintf(f, "MSG_EOR");
			flags = flags - MSG_EOR;
		} else if (flags & MSG_TRUNC) {
			fprintf(f, "MSG_TRUNC");
			flags = flags - MSG_TRUNC;
		} else if (flags & MSG_CTRUNC) {
			fprintf(f, "MSG_CTRUNC");
			flags = flags - MSG_CTRUNC;
		} else if (flags & MSG_OOB) {
			fprintf(f, "MSG_OOB");
			flags = flags - MSG_OOB;
		} else {
			fprintf(f, "??%d", flags);
			break;
		}
		if (flags != 0) fprintf(f, " ");
	}
}

static void
printsockopt(MainBase mb, int fd) {
	FILE *f = mb->debug;
	if (f == NULL) f = stdout;
	int xopt = 0;
	int res = 0;
	socklen_t xlen = sizeof(xlen);
	fprintf(f, "socket, fd %d", fd);
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_DEBUG %d", xopt);
	if (res < 0) retFail(mb, "getsockopt");
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_REUSEADDR %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_REUSEPORT %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_KEEPALIVE %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_DONTROUTE %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_LINGER %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_BROADCAST %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_OOBINLINE %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_SNDBUF %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_RCVBUF %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_SNDLOWAT %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_RCVLOWAT %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_SNDTIMEO %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_RCVTIMEO %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_TYPE %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_NOSIGPIPE %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_NREAD %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_NWRITE %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, SO_DEBUG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", SO_LINGER_SEC %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, TCP_NODELAY, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", TCP_NODELAY"); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, TCP_MAXSEG, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", TCP_MAXSEG %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, TCP_NOOPT, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", TCP_NOOPT"); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, TCP_NOPUSH, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", TCP_NOPUSH"); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, TCP_KEEPALIVE, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", TCP_KEEPALIVE %d", xopt); 
	
	xopt = 0;
	xlen = sizeof(xopt);
	res = getsockopt(fd, SOL_SOCKET, TCP_CONNECTIONTIMEOUT, &xopt, &xlen);
	if (xopt != 0) fprintf(f, ", TCP_CONNECTIONTIMEOUT %d", xopt);
	
}

#endif

////////////////////////////////
// Socket support
////////////////////////////////

static void
SetNoDelay(int sockFD) {
	int xopt = 1;
	setsockopt(sockFD, IPPROTO_TCP, TCP_NODELAY, &xopt, sizeof(xopt));
}

static int
SockAddrLen(struct sockaddr *sap) {
	sa_family_t fam = sap->sa_family;
	if (fam == PF_INET) return sizeof(struct sockaddr_in);
	if (fam == PF_INET6) return sizeof(struct sockaddr_in6);
	return 0;
}

static ssize_t
RobustRecvmsg(RequestBase rb, SockEntry se) {
	struct msghdr *mp = &rb->msg;
	int off = rb->recvOff;
	rb->recvOff = 0;
	char *ptr = ((char *) rb->buffer) + off;
	rb->iov.iov_base = ptr;
	int len = rb->bufferMax - off;
	memset(ptr, 0, len);
	rb->iov.iov_len = len;
	if (len <= 0) {
		SetRequestErr(rb, "BUG!  invalid length in RobustRecvmsg\n", 0);
		return 0;
	}
	for (;;) {
		ssize_t nb = recvmsg(se->fd, mp, 0);
		if (nb >= 0) {
			rb->bufferLen = nb + off;
			return nb;
		}
		int e = errno;
		switch (e) {
			case EAGAIN: {
				retFail(rb->mb, "RobustRecvmsg EAGAIN");
				break;
			}
			case EINTR: {
				retFail(rb->mb, "RobustRecvmsg EINTR");
				break;
			}
			default: {
				se->errCount++;
				SetRequestErr(rb, "RobustRecvmsg failed", 1);
				return -1;
			}
		}
		MilliSleep(RobustMillis);
	}
}

static ssize_t
RobustSendmsg(RequestBase rb, SockEntry se) {
	MainBase mb = rb->mb;
	FILE *f = mb->debug;
	if (f == NULL) f = stdout;
	struct msghdr *mp = &rb->msg;
	rb->iov.iov_base = ((char *) rb->buffer) + rb->sendOff;
	ssize_t len = rb->bufferLen - rb->sendOff;
	rb->iov.iov_len = len;
	if (len <= 0) {
		SetRequestErr(rb, "BUG! invalid length in RobustSendmsg", 0);
		return 0;
	}
	for (;;) {
		ssize_t nb = sendmsg(se->fd, mp, 0);
		if (nb >= 0) {
			if (nb < len) {
				// short write, note it and compensate
				fprintf(f, "-- Warning, only sent %zd bytes out of %zd\n",
                        nb, len);
				flushLog(f);
				rb->sendOff = rb->sendOff + nb;
			} else {
				// we finally sent everything
				rb->sendOff = 0;
			}
			return nb;
		}
		int e = errno;
		switch (e) {
			case EAGAIN: {
				retFail(mb, "RobustSendmsg EAGAIN");
				break;
			}
			case EINTR: {
				retFail(mb, "RobustSendmsg EINTR");
				break;
			}
			default: {
				se->errCount++;
				SetRequestErr(rb, "RobustSendmsg failed", 1);
				return -1;
			}
		}
		MilliSleep(RobustMillis);
	}
}

static int
copySockAddr(MainBase mb, struct sockaddr *dst, struct sockaddr *src) {
	sa_family_t fam = src->sa_family;
	int len = SockAddrLen(src);
	if (len <= 0) {
		FILE *f = mb->debug;
		if (f == NULL) f = stdout;
		fprintf(f, "<unsupported address type: %d>\n", fam);
		flushLog(f);
		return -1;
	}
	memcpy(dst, src, len);
	return 0;
}

static void
diagSockAddr(FILE *f, string prefix, string host, SockEntry se) {
	if (f == NULL) f = stdout;
	fprintf(f, "-- %s %s, ", prefix, host);
	struct sockaddr *sapDst = SH_GetSockEntryAddr(se);
	SH_PrintSockAddr(f, sapDst);
	fprintf(f, "\n");
	flushLog(f);
}

static void
InitSelectData(SelectData sd, uint64_t timeoutUsecs) {
	FD_ZERO(&sd->readFDS);
	FD_ZERO(&sd->writeFDS);
	FD_ZERO(&sd->errorFDS);
	sd->selectTimeout.tv_sec = (timeoutUsecs / 1000000);
	sd->selectTimeout.tv_usec = (timeoutUsecs % 1000000);
	sd->fdLen = 0;
}

static void
SetSockEntryAddr(SockEntry se, struct sockaddr *sap) {
	if (sap != NULL) {
		MainBase mb = (MainBase) se->clientData;
		copySockAddr(mb, (struct sockaddr *) &se->addr, sap);
	}
}

////////////////////////////////
// MainBase support
////////////////////////////////

static void
LinkSockEntry(RequestBase rb, SockEntry se) {
	MainBase mb = rb->mb;
	if (se->fd >= 0
		&& se->fd != mb->sockFD
		&& se->fd != mb->ccnFD) {
		if (rb->host != NULL && se->host == NULL)
			se->host = Concat(rb->host, "");
		if (se->kind == NULL)
			se->kind = Concat("http", "");
		se->port = 0; // do a better job here?
	}
	// address?
}

static SockEntry
NewSockEntry(MainBase mb, int fd, int rc) {
	SockEntry se = SH_NewSockEntry(mb->sockBase, fd);
	se->owned = rc;
	se->clientData = mb;
	return se;
}

static SockEntry
AlterSocketCount(MainBase mb, int fd, int delta) {
	// changes the reference count on the given socket
	// if delta > 0 then it is OK to create the socket in the table
	// if the RC goes to 0 then the socket is closed
	if (fd < 0) return NULL;
	SockEntry se = SH_FindSockEntry(mb->sockBase, fd);
	if (se == NULL) {
		// no such fd in the list
		if (delta > 0) {
			se = NewSockEntry(mb, fd, delta);
			return se;
		}
		return NULL;
	}
	int rc = se->owned + delta;
	if (rc < 0) return NULL;
	se->owned = rc;
	if (rc == 0) {
		SH_Destroy(se);
		return NULL;
	}
	return se;
}

static void
SetSockFD(MainBase mb, int sockFD) {
	mb->sockFD = sockFD;
	mb->client = AlterSocketCount(mb, sockFD, 1);
}

static string
RequestStateToString(RequestBase rb) {
	string msg = globalNullString;
	if (rb != NULL) switch (rb->state) {
		case RB_None: msg = "RB_None"; break;
		case RB_Start: msg = "RB_Start"; break;
		case RB_Wait: msg = "RB_Wait"; break;
		case RB_NeedRead: msg = "RB_NeedRead"; break;
		case RB_NeedWrite: msg = "RB_NeedWrite"; break;
		case RB_Error: msg = "RB_Error"; break;
		case RB_Done: msg = "RB_Done"; break;
	}
	return msg;
}

static void
ShowNameInfo(RequestBase rb, string prefix) {
	FILE *f = rb->mb->debug;
	if (f == NULL) f = stdout;
	string kind = "CCN";
	if (rb->fetchStream == NULL)
		kind = ((rb->origin > 0) ? "request" : "reply");
	fprintf(f, "%s%s, %s:%s", prefix, kind, rb->host, rb->shortName);
}

static void
PutRequestId(RequestBase rb) {
	FILE *f = rb->mb->debug;
	if (f == NULL) f = stdout;
	if (rb->seSrc != NULL)
		fprintf(f, ", src %d", rb->seSrc->fd);
	if (rb->seDst != NULL)
		fprintf(f, ", dst %d", rb->seDst->fd);
	ShowNameInfo(rb, ", ");
	if (rb->state != RB_None)
		fprintf(f, ", %s", RequestStateToString(rb));
}

static void
SetRequestHost(RequestBase rb, string host, int port) {
	string rh = rb->host;
	rb->host = newStringCopy(host);
	if (rh != NULL) freeString(rh);
	rb->port = port;
}

static void
SetMsgLen(RequestBase rb, int64_t len) {
	FILE *f = rb->mb->debug;
	rb->msgLen = len;
    if (len >= 0 && f != NULL) {
        fprintf(f, "-- SetMsgLen, %jd\n", (intmax_t) len);
    }
}

static RequestBase
NewRequestBase(MainBase mb,
			   int srcFD, int dstFD,
			   string host,
			   RequestBase parent) {
	RequestBase rb = ProxyUtil_StructAlloc(1, RequestBaseStruct);
	rb->mb = mb;
	SetRequestHost(rb, host, 0);
	
	// insert at tail to make debugging easier to read
	mb->requestCount++;
	RequestBase lag = mb->requestList;
	uint64_t now = GetCurrentTime();
	rb->startTime = now;
	rb->recentTime = now;
	rb->sockTime = now;
	for (;;) {
		if (lag == NULL) {mb->requestList = rb; break;}
		RequestBase next = lag->next;
		if (next == NULL) {lag->next = rb; break;}
		lag = next;
	}
	rb->index = mb->requestCount;
	if (srcFD >= 0) {
		rb->seSrc = AlterSocketCount(mb, srcFD, 1);
		LinkSockEntry(rb, rb->seSrc);
	}
	if (dstFD >= 0) {
		rb->seDst = AlterSocketCount(mb, dstFD, 1);
		LinkSockEntry(rb, rb->seDst);
	}
	rb->buffer = newString(BufferSize);
	rb->bufferMax = BufferSize;
    
	memset(&rb->msg, 0, sizeof(struct msghdr));
	rb->msg.msg_iovlen = 1;
	rb->msg.msg_iov = &rb->iov;
    
	if (parent != NULL) {
		// created in reply to the parent
		rb->request = newStringCopy(parent->request);
		rb->shortName = newStringCopy(parent->shortName);
		parent->backPath = rb;
		rb->fwdPath = parent;
		if (parent->httpInfo.keepAlive > rb->httpInfo.keepAlive)
			rb->httpInfo.keepAlive = parent->httpInfo.keepAlive;
        // transfer the range list from parent (remove from parent!)
        rb->httpInfo.rangeList = parent->httpInfo.rangeList;
        parent->httpInfo.rangeList = NULL;
        rb->httpInfo.assertLength = parent->httpInfo.assertLength;
        rb->parentVerb = parent->httpInfo.httpVerb;
	}
	FILE *f = mb->debug;
	if (f != NULL) {
		PutRequestMark(rb, "NewRequestBase");
		if (parent != NULL)
			fprintf(f, ", parent #%d", parent->index);
		PutRequestId(rb);
		fprintf(f, "\n");
        
		flushLog(f);
	}
	return rb;
}

static RequestBase
UnlinkRequestBase(RequestBase rb) {
	if (rb == NULL) return NULL;
	MainBase mb = rb->mb;
	if (mb == NULL) return NULL;
	RequestBase this = mb->requestList;
	RequestBase lag = NULL;
	
	RequestBase back = rb->backPath;
	RequestBase fwd = rb->fwdPath;
	if (fwd != NULL || back != NULL) {
		// break the association
		if (fwd != NULL) fwd->backPath = NULL;
		if (back != NULL) back->fwdPath = NULL;
		rb->fwdPath = NULL;
		rb->backPath = NULL;
	}
	
	// now scan to remove it from the chain
	this = mb->requestList;
	while (this != NULL) {
		RequestBase next = this->next;
		if (this == rb) {
			if (lag != NULL) {
				lag->next = next;
			} else {
				mb->requestList = next;
			}
			rb->next = NULL;
			return rb;
		}
		lag = this;
		this = next;
	}
	return NULL;
}

static int
DestroyRequestBase(RequestBase rb) {
	if (rb != NULL) {
		MainBase mb = rb->mb;
		
		FILE *f = mb->debug;
		if (f != NULL) {
			PutRequestMark(rb, "DestroyRequestBase");
			PutRequestId(rb);
			if (rb->request != NULL) fprintf(f, "; %s", rb->request);
			fprintf(f, "\n");
			flushLog(f);
		}
		
		RequestBase nrb = UnlinkRequestBase(rb);
		if (nrb != rb)
			// and why did we not find this?
			return retErr(mb, "RequestBase not found!");
		if (rb->seSrc != NULL)
			AlterSocketCount(mb, rb->seSrc->fd, -1);
		if (rb->seDst != NULL)
			AlterSocketCount(mb, rb->seDst->fd, -1);
		rb->buffer = freeString(rb->buffer);
		rb->host = freeString(rb->host);
		rb->request = freeString(rb->request);
		rb->shortName = freeString(rb->shortName);
		rb->backPath = NULL;
        struct ByteRange *rl = rb->httpInfo.rangeList;
        rb->httpInfo.rangeList = NULL;
        while (rl != NULL) {
            struct ByteRange *lag = rl;
            rl = rl->next;
            free(lag);
        }
		free(rb);
		mb->requestDone++;
		mb->nChanges++;
	}
	return 0;
}

static void
TrySelect(MainBase mb) {
	int sockFD = mb->sockFD;
	int timeout = 20;
	InitSelectData(&mb->sds, timeout);
	
	// first, make sure that CCN wakes up
	int max = mb->ccnFD;
	FD_SET(max, &mb->sds.readFDS);
	FD_SET(max, &mb->sds.errorFDS);
    
	// gather up all of the potential bits we need
	if ((mb->requestCount - mb->requestDone) < mb->maxBusy) {
		// we could start a new request
		if (sockFD > max) max = sockFD;
		FD_SET(sockFD, &mb->sds.readFDS);
		FD_SET(sockFD, &mb->sds.errorFDS);
	}
	RequestBase rb = mb->requestList;
	while (rb != NULL) {
		RequestBaseState state = rb->state;
		if (rb->seSrc != NULL && rb->fetchStream == NULL) {
			int fd = rb->seSrc->fd;
			if (fd >= 0) {
				if (state == RB_NeedRead || state == RB_Start)
					FD_SET(fd, &mb->sds.readFDS);
				FD_SET(fd, &mb->sds.errorFDS);
			}
			if (fd > max) max = fd;
		}
		if (rb->seDst != NULL) {
			int fd = rb->seDst->fd;
			if (fd >= 0) {
				if (state == RB_NeedWrite)
					FD_SET(fd, &mb->sds.writeFDS);
				FD_SET(fd, &mb->sds.errorFDS);
			}
			if (fd > max) max = fd;
		}
		rb = rb->next;
	}
	mb->sds.fdLen = max+1;
	
	if (max < 0) return; // no need to select if no interest
	
	// now do the selection over all the current fd's
	int res = select(mb->sds.fdLen,
					 &mb->sds.readFDS,
					 &mb->sds.writeFDS,
					 &mb->sds.errorFDS,
					 &mb->sds.selectTimeout
					 );
	mb->nReady = res;
	FILE *f = mb->debug;
	if (f != NULL) {
		int seen = 0;
		int i = 0;
		for (; i < mb->sds.fdLen; i++) {
			int bitR = FD_ISSET(i, &mb->sds.readFDS);
			int bitW = FD_ISSET(i, &mb->sds.writeFDS);
			int bitE = FD_ISSET(i, &mb->sds.errorFDS);
			if (bitR | bitW | bitE) {
				if (seen == 0) {
					int busy = mb->requestCount - mb->requestDone;
					fprintf(f, "\n");
					PutTimeMark(mb);
					fprintf(f, "select, sockFD %d, ccnFD %d, busy %d, ready %d:",
                            mb->sockFD, mb->ccnFD, busy, res);
				}
				fprintf(f, " %d ", i);
				if (bitR) fprintf(f, "r");
				if (bitW) fprintf(f, "w");
				if (bitE) fprintf(f, "e");
				seen++;
			}
		}
		if (seen) {
			fprintf(f, "\n");
			RequestBase rb = mb->requestList;
			while (rb != NULL) {
				fprintf(f, "  #%d", rb->index);
				PutRequestId(rb);
				fprintf(f, "\n");
				rb = rb->next;
			}
		}
		flushLog(f);
	}
}

static int
SetNameCCN(MainBase mb,
		   struct ccn_charbuf *cb,
		   char *ccnRoot, char *dir, char *name) {
	FILE *f = mb->debug;
	char temp[NameMax];
	snprintf(temp, sizeof(temp), "ccnx:/%s/", ccnRoot);
	int res = ccn_name_from_uri(cb, temp);
	if (dir != NULL) {
		// use our HTTP convention
		res = res | ccn_name_append_str(cb, (const char *) "http");
		res = res | ccn_name_append_str(cb, (const char *) dir);
	}
	// name
	res = res | ccn_name_append_str(cb, (const char *) name);
	if (res < 0) return retErr(mb, "SetNameCCN bad name");
    if (f != NULL) {
        struct ccn_charbuf *uri = ccn_charbuf_create();
        ccn_uri_append(uri, cb->buf, cb->length, 0);
        fprintf(f, "-- SetNameCCN, %s\n", ccn_charbuf_as_string(uri));
        ccn_charbuf_destroy(&uri);
    }
	return 0;
}

static int
MaybeNewRequestBase(MainBase mb) {
	// when rb->sockFD can receive something we run this step
	int sockFD = mb->sockFD;
	FD_CLR(sockFD, &mb->sds.readFDS);
	int connFD = SH_RobustAccept(mb->client);
	if (connFD < 0) return connFD;
	int res = fcntl(connFD, F_SETFL, O_NONBLOCK);
	if (res < 0) {
		return retFail(mb, "connFD fcntl failed");
	}
	struct sockaddr_storage sa;
	struct sockaddr *sap = (struct sockaddr *) &sa;
	socklen_t slen = sizeof(sa);
	int gsnRes = getsockname(connFD, sap, &slen);
	if (gsnRes < 0) {
		return retFail(mb, "error getsockname failed");
	} else if (sap->sa_family != PF_INET
			   && sap->sa_family != PF_INET6) {
		return retErr(mb, "not IP4 or IP6");
	} else {
		RequestBase rb = NewRequestBase(mb, connFD, -1, NULL, NULL);
		SetSockEntryAddr(rb->seSrc, sap);
		rb->origin = 1;
		SetRequestState(rb, RB_Start);
	}
	return 0;
}

static HttpVerb
StringToVerb(string verb) {
	if (strcmp(verb, "HEAD") == 0) {
		return HTTP_HEAD;
	} else if (strcmp(verb, "GET") == 0) {
		return HTTP_GET;
	} else if (strcmp(verb, "POST") == 0) {
		return HTTP_POST;
	} else if (strcmp(verb, "PUT") == 0) {
		return HTTP_PUT;
	} else if (strcmp(verb, "DELETE") == 0) {
		return HTTP_DELETE;
	} else if (strcmp(verb, "TRACE") == 0) {
		return HTTP_TRACE;
	} else if (strcmp(verb, "OPTIONS") == 0) {
		return HTTP_OPTIONS;
	} else if (strcmp(verb, "CONNECT") == 0) {
		return HTTP_CONNECT;
	} else {
		return HTTP_NONE;
	}
}

static string
VerbToString(HttpVerb verb) {
    switch (verb) {
        case HTTP_NONE:     return "NONE";
        case HTTP_HEAD:     return "HEAD";
        case HTTP_GET:      return "GET";
        case HTTP_POST:     return "POST";
        case HTTP_PUT:      return "PUT";
        case HTTP_DELETE:   return "DELETE";
        case HTTP_TRACE:    return "TRACE";
        case HTTP_OPTIONS:  return "OPTIONS";
        case HTTP_CONNECT:  return "CONNECT";
        default:            return "??";
    }
}    

static int
AdvanceChunks(MainBase mb, string buf, int pos, int len, ChunkInfo info) {
	for (;;) {
		ChunkState state = info->state;
		pos = pos + info->chunkRem;
		if (pos >= len) {
			// continue with next buffer
			info->chunkRem = pos - len;
			return len;
		}
		info->chunkRem = 0;
		info->prev = state;
		char c = buf[pos];
		switch (state) {
			case Chunk_Skip: {
				if (c != '\r') {
					retErr(mb, "Chunk_Error, Chunk_Skip");
					info->state = Chunk_Error;
					return pos;
				}
				info->state = Chunk_NeedNL1;
				pos++;
				break;
			}
			case Chunk_NeedNL1: {
				if (c != '\n') {
					retErr(mb, "Chunk_Error, Chunk_NeedNL1");
					info->state = Chunk_Error;
					return pos;
				}
				info->state = Chunk_Accum;
				info->accum = 0;
				info->accumLen = 0;
				pos++;
				break;
			}
			case Chunk_Accum: {
				// accumulating the next length
				// allow for leading blanks?
				for (;;) {
					if (c == ' ') {
						// we have seen some cases where blanks are present
						// TBD: check more closely?
					} else {
						int h = HexDigit(c);
						if (h < 0) {
							// not hex, but apparently blanks are OK?
							// not hex, not blank, only legal terminator is CR
							if (c != '\r' || info->accumLen == 0) {
								retErr(mb, "Chunk_Error, Chunk_Accum");
								info->state = Chunk_Error;
								return pos;
							}
							info->state = Chunk_NeedNL2;
							pos++;
							break;
						}
						uint32_t next = info->accum * 16 + h;
						if ((next >> 4) != info->accum) {
							// overflow, which is bad news
							retErr(mb, "Chunk_Error, Chunk_Accum");
							info->state = Chunk_Error;
							return pos;
						}
						info->accum = next;
						info->accumLen++;
					}
					pos++;
					if (pos >= len) {
						return pos;
					}
					c = buf[pos];
				}
				break;
			}
			case Chunk_NeedNL2: {
				// we have the length, need the terminator
				if (c != '\n') {
					info->state = Chunk_Error;
					retErr(mb, "Chunk_Error, Chunk_NeedNL2");
					return pos;
				}
				pos++;
				uint32_t acc = info->accum;
				if (acc == 0) {
					info->state = Chunk_Done;
					return pos;
				}
				info->state = Chunk_Skip;
				info->chunkRem = acc;
				info->accum = 0;
				break;
			}
			default: {
				// not in an active state, so don't advance
				return pos;
			}
		}
	}
}

static int
SkipOverVerb(RequestBase rb) {
	string buf = rb->buffer;
	int len = rb->bufferLen;
	int pos = 0;
	// scan over the verb
	while (pos < len) {
		char c = buf[pos];
		pos++;
		if (c == ' ') break;
		if (IsAlpha(c) == 0) return -1;
	}
	// skip any blanks
	while (pos < len) {
		if (buf[pos] != ' ') break;
		pos++;
	}
	return pos;
}

static int
SkipOverHost(RequestBase rb) {
	HttpInfo httpInfo = &rb->httpInfo;
	if (httpInfo->httpVersion != 1) return 0;
	if (httpInfo->httpSubVersion > 1) return 0;
	if (rb->host == NULL) return 0;
	int hostLen = strlen(rb->host);
	if (hostLen <= 0) return 0;
	string buf = rb->buffer;
	int addrStart = SkipOverVerb(rb);
	if (addrStart > 0) {
		string proto = "http://";
		int protoLen = strlen(proto);
		string ss = buf+addrStart;
		int len = rb->bufferLen-addrStart;
		if (HasPrefix(ss, len, proto)) {
			if (HasPrefix(ss+protoLen, len, rb->host)) {
				// found the host! report the start of the short name
				int pos = addrStart+protoLen+hostLen;
				if (buf[pos] == ':') {
					pos++;
					for (;;) {
						char c = buf[pos];
						if (c < '0' || c > '9') break;
						pos++;
					}
				}
				return pos;
			}
		}
	}
	return -1;
}

static string
ExtractShortName(RequestBase rb) {
	string buf = rb->buffer;
	int len = rb->bufferLen;
	int start = SkipOverVerb(rb);
	if (start < 0)
		return NULL;
	if (buf[start] == '/') {
		// no method, no host, but name may be OK
	} else {
		// should have a host spec
		start = SkipOverHost(rb);
	}
	int pos = start;
	while (pos < len) {
		char c = buf[pos] & 255;
		if (c <= ' ') break;
		pos++;
	}
	len = pos-start;
	if (len < 1)
		return NULL;
	string s = newStringPrefix(buf+start, len);
	return s;
}

static int
TryHostHack(RequestBase rb) {
	HttpInfo httpInfo = &rb->httpInfo;
	if (httpInfo->httpVersion != 1) return 0;
	if (httpInfo->httpSubVersion > 1) return 0;
	if (rb->host == NULL) return 0;
	int hostLen = strlen(rb->host);
	if (hostLen <= 0) return 0;
	string buf = rb->buffer;
	int len = rb->bufferLen;
	int addrStart = -1;
	// scan over the verb
	int i = 0;
	for (; i < len; i++) {
		char c = buf[i];
		if (c <= ' ') {
			// the address starts just after the first space
			if (c == ' ') addrStart = i+1;
			break;
		}
	}
	if (addrStart > 0) {
		string proto = "http://";
		int protoLen = strlen(proto);
		if (HasPrefix(buf+addrStart, protoLen, proto)) {
			int pos = addrStart+protoLen;
			if (HasPrefix(buf+pos, hostLen, rb->host)) {
				// found the host, so check for port
				pos = pos + hostLen;
				if (buf[pos] == ':') {
					// skip over the port
					pos++;
					for (;;) {
						char c = buf[pos];
						if (c < '0' || c > '9') break;
						pos++;
					}
				}
			} else if (rb->rewriteHost > 0) {
                // can't match on the name, so just accept the first name
                // (note: will not work for multiple levels of proxy/translate)
                while (pos < len) {
                    char c = buf[pos];
                    if (ShortNameChar(c) == 0) break;
                    if (c == '/') break;
                    pos++;
                }
            }
            int delta = pos-addrStart;
            if (delta > 0) {
                memmove(buf+addrStart, buf+pos, len-pos);
                len = len - delta;
                buf[len] = 0;
                rb->bufferLen = len;
                return delta;
            }
		}
	}
	return 0;
}

static void
ExtractHttpVersion(HttpInfo httpInfo, string s, int len) {
	httpInfo->httpVersion = 0;
	httpInfo->httpSubVersion = 0;
	if (TokenPresent(s, len, "HTTP/1.1")) {
		httpInfo->httpVersion = 1;
		httpInfo->httpSubVersion = 1;
	} else if (TokenPresent(s, len, "HTTP/1.0")) {
		httpInfo->httpVersion = 1;
		httpInfo->httpSubVersion = 0;
	}
}

static int
CheckHttpHeader(RequestBase rb) {
	// return -1 if invalid, 0 if incomplete, header length if valid
	MainBase mb = rb->mb;
	FILE *f = mb->debug;
	string buf = rb->buffer;
	int len = rb->bufferLen;
	int pos = 0;
	int line = 0;
	int lineLen = 0;
	int lineStart = 0;
	int verPos = 0;
	char lag = 0;
	int reportBinary = 0;
    rb->headerLenInit = -1;
	while (pos < len) {
		char c = buf[pos];
		pos++;
		if (c == '\n' && lag == '\r') {
			// we have a line
			if (line == 0 && lineLen > 8) {
				// first line is special
				char *tok = buf;
				if (rb->origin) tok = tok + verPos;
				if (TokenPresent(tok, len, "HTTP/1.1")
					|| TokenPresent(tok, len, "HTTP/1.0")) {
					// first line has proper HTTP version info
				} else
					return -1;
			} else if (line > 0 && lineLen == 0) {
				// there is a blank line, so we have found the end of the header
				rb->headerLenInit = pos;
                return pos;
            }
			line++;
			lineLen = 0;
			lineStart = pos;
		} else if (c == '\r') {
			// skip over CR for now
		} else if (c == ' ') {
			lineLen++;
			verPos = pos;
		} else if (c < ' ' && reportBinary) {
			// binary not OK
			retErr(mb, "binary in header?");
			if (f != NULL) {
				fprintf(f, "-- pos %d, len %d, char %d\n", pos, len, c);
				fwrite(buf, sizeof(char), pos, stdout);
				if (lag != '\n') fprintf(f, "\n");
				flushLog(f);
			}
			return -1;
		} else {
			// seems to be an OK char
			lineLen++;
		}
		lag = c;
	}
    rb->headerLenInit = 0;
	return 0;
}

static int
RewriteBuffer(RequestBase rb, int start, int len, string replace) {
    string buf = (string) rb->buffer;
    int bufLen = rb->bufferLen;
    int repLen = ((replace != NULL) ? strlen(replace) : 0);
    int delta = repLen - len;
    int newLen = bufLen + delta;
    int sPos = start+len;
    int dPos = start+repLen;
    int rem = bufLen - sPos;
    if (len < 0 || sPos > bufLen || newLen > rb->bufferMax)
        // failed sanity check
        return -1;
    if (len != repLen && rem > 0) {
        // create hole for the replacement and shift the remaining bytes
        memmove(buf+dPos, buf+sPos, rem);
    }
    if (repLen > 0) {
        // insert the replacement string
        memmove(buf+start, replace, repLen);
    }
    rb->bufferLen = newLen;
    return 0;
}

static int
AcceptByteRange(RequestBase rb, string s, int len) {
    int res = 0;
	MainBase mb = rb->mb;
	FILE *f = mb->debug;
    static string rKind = "bytes";
    struct ByteRange *rangeTail = NULL;
    int pos = 0;
    if (!TokenPresent(s, len, rKind)) {
        res = -__LINE__;
    } else {
        pos = pos + strlen(rKind);
        pos = SkipOverBlank(s, pos, len);
        if (s[pos] != '=')
            res = -__LINE__;
        pos++;
    }
    
    while (pos < len && res == 0) {
        struct ByteRange *rl = NULL;
        ssize_t rStart = 0;
        ssize_t rStop = -1;
        pos = SkipOverBlank(s, pos, len);
        if (!IsNumeric(s[pos])) {
            // a rangeStart number is required
            res = -__LINE__;
            break;
        }
        for (;;) {
            int c = IsNumeric(s[pos]);
            if (c == 0) break;
            rStart = rStart*10 + (c - '0');
            pos++;
        }
        pos = SkipOverBlank(s, pos, len);
        if (s[pos] != '-') {
            res = -__LINE__;
            break;
        }
        pos++;
        pos = SkipOverBlank(s, pos, len);
        // a rangeStop number is optional
        if (IsNumeric(s[pos])) {
            rStop = 0;
            for (;;) {
                int c = IsNumeric(s[pos]);
                if (c == 0) break;
                rStop = rStop*10 + (c - '0');
                pos++;
            }
            if (rStop < rStart) {
                // if both number are present, require valid range
                res = -__LINE__;
                break;
            }
        }
        // now, make up a new entry to hold the new range
        // TBD: canonicalize the ranges
        rl = ProxyUtil_StructAlloc(1, ByteRange);
        rl->rangeStart = rStart;
        rl->rangeStop = rStop;
        if (f != NULL) {
            fprintf(f, "-- found range spec: %jd-%jd\n", 
                    (intmax_t) rStart,
                    (intmax_t) rStop);
        }
        
        if (rangeTail == NULL) rb->httpInfo.rangeList = rl;
        else rangeTail->next = rl;
        rangeTail = rl;
        pos = SkipOverBlank(s, pos, len);
        if (s[pos] != ',') break;
        pos++;
    }
    if (res < 0) {
        // must remove the request
        if (f != NULL) {
            char *line = newStringPrefix(s, len);
            fprintf(f, "** Invalid range spec: %d, %s\n",
                    res, line);
            flushLog(f);
            freeString(line);
        }
    }
    return res;
}

static int
ExtractHTTPInfo(RequestBase rb, HttpVerb httpVerb) {
	MainBase mb = rb->mb;
	FILE *f = mb->debug;
	string buf = rb->buffer;
	int len = rb->bufferLen;
	int lines = 0;
	int pos = 0;
	int contentLen = -1; // unknown at first
	char host[NameMax+4];
	int hostLen = 0;
	char lag = 0;
	int reportBinary = 0;
	HttpInfo h = &rb->httpInfo;
	
	rb->chunkInfo.state = Chunk_None;
    SetMsgLen(rb, -1);
	
    h->assertLength = -1;
	for (;;) {
		int lineLen = 0; // does not include termination
		int lineStart = pos;
		int colonPos = -1;  // pos just after the colon
		int queryPos = -1;  // pos just after the query
		if (pos == len) {
			// this is not a good sign, non-terminating header
			h->badHeader = 1;
		}
		while (pos < len) {
			char c = buf[pos];
			pos++;
			if (c == '\r') {
				// should be followed by '\n'
				// TBD: how forgiving to be?
			} else if (c == '\n') {
				// end of line 
				lag = c;
				break;
			} else if (c < ' ' && reportBinary) {
				// bad line
				lineLen = 0;
				pos--;
				break;
			} else {
				if (c == ':' && colonPos < 0) colonPos = pos;
				if (c == '?' && queryPos < 0) queryPos = pos;
				lineLen++;
			}
			lag = c;
		}
		if (lineLen == 0) break;
		lines++;
		string key = buf+lineStart;
		if (lines == 1) {
			// first line
			// TBD: handle first line options
			// this is the original request
			// capture the request for later debugging
			rb->request = newStringPrefix(key, lineLen);
			if (httpVerb != HTTP_NONE) {
				// this is the request, version ID at the tail
				int lastBlank = lineLen;
				while (lastBlank > 0) {
					lastBlank--;
					if (key[lastBlank] == ' ') {
						ExtractHttpVersion(h,
										   key+lastBlank+1,
										   lineLen-lastBlank);
						break;
					}
				}
			} else {
				// this is the reply, version ID at front
				ExtractHttpVersion(h, key, lineLen);
				int bpos = SkipToBlank(key, 0, lineLen);
				bpos = SkipOverBlank(key, bpos, lineLen);
				h->httpCode = EvalUint(key, bpos);
			}
		} else if (colonPos > lineStart) {
			// at this point we have a candidate line
            int keyLen = colonPos - lineStart - 1;
			string postKey = buf+colonPos;
			int postLen = lineLen - (colonPos - lineStart) + 1;
            // strip leading and trailing key blanks
            while (key[0] == ' ' && keyLen > 0) {
                key++;
                keyLen--;
            }
            while (keyLen > 0 && key[keyLen-1] == ' ') {
                keyLen--;
            }
            // skip post-colon blanks
            while (postKey[0] == ' ') {
                postKey++;
                postLen--;
            }
			int remove = 0;
			char replace[NameMax+16];
			replace[0] = 0;
			int keepAlive = mb->defaultKeepAlive;
			if (h->keepAlive > keepAlive) keepAlive = h->keepAlive;
            if (f != NULL) {
                fprintf(f, "-- key(%d): ", keyLen);
                fwrite(key, sizeof(char), keyLen, f);
                fprintf(f, ", post(%d): ", postLen);
                fwrite(postKey, sizeof(char), postLen, f);
            }
			if (TokenPresent(key, keyLen, "Content-Length")) {
				// we have an alleged length for the content
				contentLen = EvalUint(postKey, 0);
                h->assertLength = contentLen;
                h->hasContentLength++;
			} else if (TokenPresent(key, keyLen, "Content-Range")) {
                h->hasContentRange++;
			} else if (TokenPresent(key, keyLen, "Content-Type")) {
                h->contentType = newStringPrefix(postKey, postLen);
			} else if (TokenPresent(key, keyLen, "Connection")) {
				if (TokenPresent(postKey, postLen, "close")) {
					h->forceClose = 1;
				} else if (TokenPresent(postKey, postLen, "Keep-Alive")) {
					remove = (keepAlive < 0);
					h->keepAlive = keepAlive;
				} else if (TokenPresent(postKey, postLen, "keep-alive")) {
					remove = (keepAlive < 0);
					h->keepAlive = keepAlive;
				}
			} else if (TokenPresent(key, keyLen, "Transfer-Encoding")) {
				h->transferEncoding = 1;
				if (TokenPresent(postKey, postLen, "chunked"))
					h->transferChunked = 1;
			} else if (TokenPresent(key, keyLen, "Proxy-Connection")) {
				remove = mb->removeProxy;
				h->proxyConn = 1;
				if (TokenPresent(postKey, postLen, "keep-alive"))
					h->proxyKeepAlive = keepAlive;
			} else if (TokenPresent(key, keyLen, "Cookie")) {
				h->cookie = 1;
			} else if (TokenPresent(key, keyLen, "Range")) {
                if (AcceptByteRange(rb, postKey, postLen) < 0)
                    remove = 1;
			} else if (TokenPresent(key, keyLen, "Accept-Ranges: ")) {
				h->hasRange = 2;
			} else if (TokenPresent(key, keyLen, "Referer: ")) {
				h->hasReferer = 1;
			} else if (TokenPresent(key, keyLen, "Keep-Alive: ")) {
				remove = (keepAlive < 0);
				h->keepAlive = keepAlive;
				if (postLen > 0) {
					char c = postKey[0];
					if (IsNumeric(c)) {
						h->keepAlive = EvalUint(postKey, 0);
					} else {
						while (IsAlpha(c)) {
							if (TokenPresent(postKey, postLen, "timeout=")) {
								postKey = postKey + 8;
								postLen = postLen - 8;
								c = postKey[0];
								h->keepAlive = EvalUint(postKey, 0);
							} else if (TokenPresent(postKey, postLen, "max=")) {
								postKey = postKey + 4;
								postLen = postLen - 4;
								c = postKey[0];
								h->keepAlive = EvalUint(postKey, 0);
							} else {
								break;
							}
						}
						while (IsNumeric(c)) {
							postKey++;
							postLen--;
							c = postKey[0];
						}
					}
					
					// TBD: handle options better
				}
			} else if (TokenPresent(key, keyLen, "Host: ")) {
				// interpret the host spec as an override
				host[0] = 0;
				hostLen = AcceptHostName(postKey, 0, host, NameMax);
                HostLine lookup = SelectHostSuffix(mb, host);
                if (lookup != NULL && lookup->translate != NULL) {
                    // replace the host here
                    SetRequestHost(rb, lookup->translate, rb->port);
                    remove = 1;
                    snprintf(replace, sizeof(replace),
                             "Host: %s\r\n",
                             rb->host);
                    rb->rewriteHost = 1;
                } else if (rb->rewriteHost == 0
                           && (mb->hostFromGet == 0
                               || rb->host == NULL
                               || strlen(rb->host) == 0) ) {
                               // in this case we get the host from the Host: line
                               if (hostLen > 0) {
                                   int port = 0;
                                   int pLen = AcceptHostPort(postKey, hostLen, &port);
                                   if (pLen <= 0)
                                       // good idea to inherit this?
                                       port = rb->port;
                                   SetRequestHost(rb, host, port);
                               }
                           } else if (strcasecmp(rb->host, host) != 0) {
                               // we need to remove this line
                               // the GET host is not like the Host:
                               // HOWEVER, we need to replace the bogus host!
                               rb->rewriteHost = 1;
                               remove = 1;
                               snprintf(replace, sizeof(replace),
                                        "Host: %s\r\n",
                                        rb->host);
                           }
			} else {
				// TBD: what to do about unrecognized cases?  anything?
			}
			// test for replacing/removing the line
			int rem = len-pos;
			if (remove) {
				// remove the current line
				int add = strlen(replace);
				if (f != NULL) {
                    int scp = pos-2;
					char sc = buf[scp];
					buf[scp] = 0;
					fprintf(f, "  removing line %d, %s\n", lines, key);
					buf[scp] = sc;
                    if (add > 0) {
						fprintf(f, "  replacing with %s\n", replace);
					}
                    flushLog(f);
				}
				memmove(key+add, buf+pos, rem);
				if (add > 0) {
					// we have something to insert (includes cr-lf)
					memmove(key, replace, add);
				}
				pos = lineStart+add;
				len = len - (lineLen+2) + add;
				rb->bufferLen = len;
			}
		}
	}
	if (lag != '\n') h->badHeader = 1;
	
	if (httpVerb == HTTP_GET || httpVerb == HTTP_HEAD || httpVerb == HTTP_OPTIONS) {
		// we have a supported verb
		rb->shortName = ExtractShortName(rb);
		if (rb->removeHost) {
			// may need to hack GET line to remove host
			int delta = TryHostHack(rb);
			pos = pos - delta;
			len = rb->bufferLen;
			buf = rb->buffer;
		}
	}
	
	h->headerLen = pos;
	
	if (h->badHeader) {
		// non-terminating header line?
		SetMsgLen(rb, h->headerLen);
		return retErr(mb, "bad header");
	}
	if (h->httpVersion != 1) {
		SetMsgLen(rb, h->headerLen);
		return retErr(mb, "bad HTTP version");
	}
	if (h->transferEncoding) contentLen = -1;
	if (h->headerLen > 0 && contentLen >= 0) {
		SetMsgLen(rb, h->headerLen+contentLen);
		if (rb->msgLen < len) {
			if (f != NULL) {
				fprintf(f, "-- truncating buffer, msgLen %jd len %d\n",
                        (intmax_t) rb->msgLen, len);
				pos = len;
				for (;;) {
					int nPos = NextLine(buf, pos, len);
					int nLen = nPos-pos;
					if (nLen <= 0) break;
					fwrite(buf+pos, sizeof(char), nLen, stdout);
					pos = nPos;
				}
				flushLog(f);
			}
			len = rb->msgLen;
			rb->bufferLen = len;
			h->forceClose = 1;
			pos = len;
		}
	}
	if (h->httpSubVersion != 1) {
		// keep it really simple, probably HTTP/1.0
		h->forceClose = 1;
	}
	if (h->httpCode >= 500) {
		// protocol error?
		SetMsgLen(rb, len);
		h->forceClose = 1;
	} else if (h->httpCode >= 400) {
		// error code, force single packet
		SetMsgLen(rb, len);
		h->forceClose = 1;
	} else {
		switch (h->httpCode) {
			case 304:
				// not modified, force single packet
			case 204:
				// no content
				SetMsgLen(rb, len);
		}
	}
	if (f != NULL) {
		fprintf(f, "-- http: ");
		fprintf(f, "%d.%d", h->httpVersion, h->httpSubVersion);
		if (h->httpCode > 0)
			fprintf(f, " code %d", h->httpCode);
		if (hostLen > 0)
			fprintf(f, " host %s", host);
		if (contentLen >= 0)
			fprintf(f, " len %d", contentLen);
		if (h->headerLen >= 0)
			fprintf(f, " header %d", h->headerLen);
		if (h->transferEncoding)
			fprintf(f, " tfr");
		if (h->transferChunked)
			fprintf(f, " chunked");
		if (h->keepAlive >= 0)
			fprintf(f, " keepAlive %d", h->keepAlive);
		if (h->proxyConn)
			fprintf(f, " proxyConn %d", h->proxyKeepAlive);
		if (h->forceClose)
			fprintf(f, " close");
		
		fprintf(f, " pos %d, len %d, bufferLen %d", pos, len, rb->bufferLen);
		fprintf(f, "\n");
		flushLog(f);
	}
	
	if (h->transferChunked) {
		ChunkInfo info = &rb->chunkInfo;
		info->state = Chunk_Accum;
		info->chunkRem = h->headerLen;
		AdvanceChunks(mb, buf, 0, len, info);
		switch (info->state) {
			case Chunk_Done: {
				if (f != NULL) {
					fprintf(f, "-- chunking done\n");
					flushLog(f);
				}
				SetMsgLen(rb, len);
				break;
			}
			case Chunk_Error: {
				if (f != NULL) {
					fprintf(f, "-- chunking error, assume last packet\n");
					flushLog(f);
				}
				SetMsgLen(rb, len);
				break;
			}
			default: {
				if (f != NULL) {
					fprintf(f, "-- chunking in progress, chunkRem %u\n",
                            info->chunkRem);
					flushLog(f);
				}
			}
		}
		
	} else if (httpVerb == HTTP_GET || httpVerb == HTTP_HEAD || httpVerb == HTTP_OPTIONS) {
		// these should not have content
		if (rb->msgLen < h->headerLen)
            SetMsgLen(rb, h->headerLen);
	}
	return 0;
}

static RequestBase
FindWaiter(RequestBase rb) {
	MainBase mb = rb->mb;
	string host = rb->host;
	uint64_t now = GetCurrentTime();
	RequestBase each = mb->requestList;
	while (each != NULL) {
		if (each->fetchStream == NULL
			&& each->state == RB_Wait
			&& rb->httpInfo.keepAlive > 0
			&& rb->httpInfo.forceClose == 0
			&& SameHost(host, each->host)
			) {
			double dt = DeltaTime(now, rb->sockTime);
			if (dt < rb->httpInfo.keepAlive) {
				return each;
			}
		}
		each=each->next;
	}
	return NULL;
}

static int
RequestBaseContinue(RequestBase rb, SockEntry seDst) {
	// we've determined the destination
	// the socket to be used is passed in if we are reusing the socket
	// otherwise it is NULL, which means that we create a new socket
	MainBase mb = rb->mb;
	
	ssize_t sb = 0;
	if (seDst != NULL) {
		// try to use the suggested socket
		AlterSocketCount(mb, seDst->fd, 1);
		if (mb->debug != NULL) {
			diagSockAddr(mb->debug, "Reusing", rb->host, seDst);
		}
		sb = RobustSendmsg(rb, seDst);
		if (sb <= 0) {
			// can't reuse this socket, try to recover by
			// killing it and trying for a new socket
			retErr(mb, "reuse failed");
			AlterSocketCount(mb, seDst->fd, -1);
			seDst = NULL;
		}
	}
	
	if (seDst == NULL) {
		// we try to get a new socket for the address
		string kind = "http";
		seDst = SH_NewSockEntryForName(mb->sockBase, rb->host, kind, rb->port);
		if (seDst == NULL) {
			// can't get a new socket
			char temp[NameMax+16];
			snprintf(temp, sizeof(temp), "no socket for %s", rb->host);
			return SetRequestErr(rb, temp, 0);
		}
		if (mb->debug != NULL) {
			diagSockAddr(mb->debug, "Connecting to", rb->host, seDst);
		}
		seDst->owned = 1;
		seDst->clientData = mb;
		seDst->keepAlive = -1;
		if (rb->httpInfo.forceClose == 0 && rb->httpInfo.keepAlive > 0)
			seDst->keepAlive = rb->httpInfo.keepAlive;
		SetNoDelay(seDst->fd);
		sb = RobustSendmsg(rb, seDst);
		if (sb <= 0) {
			AlterSocketCount(mb, seDst->fd, -1);
			return SetRequestErr(rb, "message not sent", 0);
		}
	}
	// Now we know the socket to send to
	rb->seDst = seDst;
	if (seDst != NULL) {
		uint64_t now = GetCurrentTime();
		NewRequestBase(mb, seDst->fd, rb->seSrc->fd, rb->host, rb);
		SetRequestState(rb, RB_NeedWrite);
		rb->recentTime = now;
		return 0;
	}
	return -1;
}

struct ShortNameInfo {
	int len;
	int count;
	int query;
	int dots;
};

static void
ExamShortName(struct ShortNameInfo *info, char *s) {
	int count = 0;
	int pos = 0;
	int query = 0;
	int dots = 0;
	if (s != NULL) {
        char lag = 0;
		for (;;) {
			char c = s[pos];
			if (c == 0) {
                // found the end, clip off any trailing slash
                // (correct for brain-dead Apple use of OPTIONS)
                if (lag == '/' && pos > 1) {
                    s[pos-1] = 0;
                    count--;
                }
                break;
            }
			if (IsAlpha(c) || IsNumeric(c)) count = count + 1;
			else {
				count = count + 3;
				if (c == '?') query++;
				if (c == '.') dots++;
			}
			pos++;
            lag = c;
		}
	}
	info->count = count;
	info->len = pos;
	info->query = query;
	info->dots = dots;
}

static int
StripQuery(string s, int len) {
    int i = 0;
    while (i < len) {
        if (s[i] == '?') {
            s[i] = 0;
            return 1;
        }
        i++;
    }
    return 0;
}


static int
WriteNotFoundReply(RequestBase rb) {
    string buf = (string) rb->buffer;
    int max = rb->bufferMax;
    int pos = 0;
    pos += snprintf(buf+pos, max-pos, "HTTP/1.1 404\r\n");
    pos += snprintf(buf+pos, max-pos, "\r\n");
    buf[pos] = 0;
    rb->bufferLen = pos;
    return 0;
}

static int
WriteOptionsReply(RequestBase rb) {
    FILE *f = rb->mb->debug;
    HttpInfo h = &rb->httpInfo;
    string buf = (string) rb->buffer;
    int max = rb->bufferMax;
    int pos = 0;
    string dateStr = newDateString();
    pos += snprintf(buf+pos, max-pos, "HTTP/1.1 200 OK\r\n");
    pos += snprintf(buf+pos, max-pos, "Date: %s\r\n", dateStr);
    pos += snprintf(buf+pos, max-pos, "Server: Bruce Radicchio\r\n");
    pos += snprintf(buf+pos, max-pos, "Allow: GET,HEAD,OPTIONS\r\n");
    pos += snprintf(buf+pos, max-pos, "Content-Length: 0\r\n");
    if (h->contentType != NULL) {
        // we got a reply
        pos += snprintf(buf+pos, max-pos,
                        "Content-Type: %s\r\n", h->contentType);
    }
    pos += snprintf(buf+pos, max-pos, "\r\n");
    buf[pos] = 0;
    rb->bufferLen = pos;
    if (f != NULL) {
        fprintf(f, "-- OPTIONS reply, %u\n%s", pos, buf);
        flushLog(f);
    }
    return 0;
}

static int
RequestBaseStart(RequestBase rb) {
	// come here to get a request from the client
	// rb->seSrc is set, and we need to get an HTTP request from that socket
	MainBase mb = rb->mb;
	FILE *f = mb->debug;
	SockEntry seSrc = rb->seSrc;
	int connFD = seSrc->fd;
	rb->startTime = GetCurrentTime();
	rb->recentTime = rb->startTime;
	rb->maxConn = mb->maxConn;
	rb->removeHost = mb->removeHost;
	ssize_t nb = RobustRecvmsg(rb, seSrc);
    HttpInfo h = &rb->httpInfo;
	
	if (nb <= 0) {
		rb->state = ((nb < 0) ? RB_Error : RB_Done);
		if (nb == 0 && f != NULL) {
			fprintf(f, "-- RequestBaseStart, nothing??\n");
			flushLog(f);
		}
		h->keepAlive = -1;
		return 0; // nothing to receive
	}
	nb = rb->bufferLen;
	
	mb->stats.requests++;
	
	int ck = CheckHttpHeader(rb);
    if (f != NULL) {
        fprintf(f, "-- RequestBaseStart, initial request, %d\n%s",
                ck, (char *) rb->buffer);
        flushLog(f);
    }
	if (ck < 0) {
		// bad header
		SetRequestErr(rb, "Invalid header", 0);
		return -1;
	}
	if (ck == 0) {
		// header not complete, go back and get another line
		rb->recvOff = nb;
		if (nb + 1000 > rb->bufferMax) {
			SetRequestErr(rb, "Header too long", 0);
			return -1;
		}
		return 0;
	}
    SetMsgLen(rb, -1); // negative means unknown
	mb->nChanges++;
	
	struct sockaddr_storage sa;
	struct sockaddr *sap = (struct sockaddr *) &sa;
	socklen_t slen = sizeof(sa);
	int gsnRes = getsockname(connFD, sap, &slen);
	if (gsnRes != 0) {
		return SetRequestErr(rb, "error getsockname failed", 1);
	}
	
	if (sap->sa_family != PF_INET && sap->sa_family != PF_INET6)
		return SetRequestErr(rb, "unsupported protocol", 0);
	
	if (mb->debug != NULL) {
		fprintf(f, "-- #%d, new request, %zd bytes\n", rb->index, nb);
		flushLog(f);
	}
	
	string buf = (string) rb->buffer;
	char verb[PartMax+1];
	int pos = AcceptPart(buf, 0, verb, PartMax);
	int tryHost = 0;
	while (buf[pos] == ' ') pos++;
	
	h->httpVerb = StringToVerb(verb);
	
	char kind[PartMax+1];
	switch (h->httpVerb) {
		case HTTP_CONNECT: {
			// no XXX: part
			tryHost = 1;
			break;
		}
		case HTTP_HEAD:
		case HTTP_GET:
		case HTTP_POST:
		case HTTP_PUT:
		case HTTP_TRACE:
		case HTTP_OPTIONS: {
			// look for XXX:YYY
			int npos = AcceptPart(buf, pos, kind, PartMax);
			if (npos > pos) {
				// we MAY have a XXX: part
				if (buf[npos] != ':' || buf[npos+1] != '/')
					return SetRequestErr(rb, "bad protocol syntax", 0);
				pos = npos + 2;
				if (buf[pos] == '/') pos++;
				tryHost = 1;
			} else {
				// not kind, so use default
				strcpy(kind, "http");
			}
			break;
		}
		default: {
			// die now, no attempt made to parse the rest
			return SetRequestErr(rb, "unregognized HTTP verb", 0);
		}
	}
	
	int port = 0;
	int hostLen = 0;
	if (tryHost) {
		char host[NameMax+4];
		hostLen = AcceptHostName(buf, pos, host, NameMax);
		if (hostLen <= 0) return SetRequestErr(rb, "bad host name", 0);
		int pLen = AcceptHostPort(buf, pos+hostLen, &port);
		pos = pos + hostLen + pLen;
		// we think that we have a host name
		SetRequestHost(rb, host, port);
	}
	
	// parse the header
	ExtractHTTPInfo(rb, h->httpVerb);
	rb->msgCount++;
	
	if (h->httpVerb == HTTP_CONNECT) {
		if (port == 443) {
			strcpy(kind, "https");
			// TBD: figure out how to do https?
			return SetRequestErr(rb, "unsupported HTTP verb CONNECT", 0);
		} else
			return SetRequestErr(rb, "unsupported HTTP verb CONNECT", 0);
	}
	
	// skip over the rest of the name
	int i = 0;
	for (; i < NameMax; i++) {
		char c = buf[pos];
		if (c == 0) return SetRequestErr(rb, "bad name", 0);
		pos++;
		if (c == ' ') break;
	}
	
	// translate the dest name
    
	int firstLineLen = NextLine(buf, 0, nb);
	HostLine hostLine = NULL;
	int failQuick = 0;
	if (mb->ccnRoot != NULL
        && (h->httpVerb == HTTP_GET
            || h->httpVerb == HTTP_HEAD
            || h->httpVerb == HTTP_OPTIONS)) {
            // determine if this is an acceptable host for CCN
            string effectiveHost = rb->host;
            string effectiveName = rb->shortName;
            struct ShortNameInfo info;
            char tempHost[NameMax+4];
            // first, remove any prefixes that are marked as proxies
            for (;;) {
                ExamShortName(&info, effectiveName);
                hostLine = SelectHostSuffix(mb, effectiveHost);
                if (hostLine == NULL) break;
                if ((hostLine->flags & (HostLine_Proxy | HostLine_Translate)) == 0)
                    break;
                if (effectiveName[0] == '/') effectiveName++;
                int hLen = AcceptHostName(effectiveName, 0, tempHost, NameMax);
                if (hLen == 0) break;
                // we have a place to split the line to extract the host
                if (hostLine->translate == NULL) {
                    effectiveHost = tempHost;
                    effectiveName = effectiveName + hLen;
                } else {
                    effectiveHost = hostLine->translate;
                    effectiveName = effectiveName + hLen;
                }
                if (f != NULL) {
                    fprintf(f, "-- proxy name, host %s, name %s\n",
                            effectiveHost, effectiveName);
                    flushLog(f);
                }
                string lag = rb->host;
                rb->host = newStringCopy(effectiveHost);
                freeString(lag);
                lag = rb->shortName;
                rb->shortName = newStringCopy(effectiveName);
                freeString(lag);
                rb->rewriteHost++;
            }
            if (rb->shortName == NULL || (rb->shortName[0] != '/')) {
                // force a leading slash in the name
                string lag = rb->shortName;
                rb->shortName = newStringCat("/", rb->shortName);
                freeString(lag);
            }
            // now, we have an effective host and we have flags for the name
            while (hostLine != NULL) {
                // maybe CCN, so process the flags for this host
                int flags = hostLine->flags;
                if (f != NULL) {
                    fprintf(f, "-- SelectHostSuffix, host %s, flags %d\n",
                            rb->host, flags);
                    flushLog(f);
                }
                if (flags & HostLine_FailQuick) {
                    // these never win 
                    failQuick = 1;
                    hostLine = NULL;
                    break;
                }
                if (flags & HostLine_NeedDot && info.dots <= 0) {
                    // requires a dot in the name
                    hostLine = NULL;
                    break;
                }
                if ((flags & HostLine_NoCookie) && h->cookie != 0) {
                    // prohibits Cookie:
                    hostLine = NULL;
                    break;
                }
                if ((flags & HostLine_NoReferer) && h->hasReferer != 0) {
                    // prohibits Referer:
                    hostLine = NULL;
                    break;
                }
                if ((flags & HostLine_NoQuery) && info.query > 0) {
                    // prohibits queries ('?') in name 
                    hostLine = NULL;
                    break;
                }
                if (flags & HostLine_SingleConn) {
                    // force a single connection
                    hostLine = NULL;
                    rb->maxConn = 1;
                    break;
                }
                if (info.count < 0 || firstLineLen >= NameMax/2) {
                    // CCN can't handle excessively long names
                    hostLine = NULL;
                    break;
                }
                if (flags & HostLine_QueryHack) {
                    // force a Range interpretation, if a query is present
                    int i = 0;
                    while (i < firstLineLen) {
                        char c = buf[i];
                        i++;
                        if (c == '?') {
                            // the query is parsed as a byte range, then deleted
                            AcceptByteRange(rb, buf+i, firstLineLen-i);
                            i--;
                            RewriteBuffer(rb, i, firstLineLen-i, "\r\n");
                            StripQuery(rb->shortName, strlen(rb->shortName));
                            break;
                        }
                    }
                }
                break;
            }
            if (hostLine == NULL && failQuick == 0) {
                fprintf(f, "-- Prevent CCN for %s:%s; using HTTP\n",
                        rb->host, rb->shortName);
            }
        }
    
	if (failQuick) {
        // make a failing reply
        RequestBaseContinue(rb, NULL);
        RequestBase reply = rb->backPath;
        if (reply != NULL) {
            if (f != NULL) {
                fprintf(f, "-- Fail force for #%d, %s:%s\n",
                        rb->index, rb->host, rb->shortName);
                flushLog(f);
            }
            reply->forceFail++;
            reply->parentVerb = h->httpVerb;
            SetRequestState(reply, RB_NeedRead);
            SetRequestState(rb, RB_Done);
            return 0;
        } else {
            if (f != NULL) {
                fprintf(f, "-- Fail quick for #%d, %s:%s\n",
                        rb->index, rb->host, rb->shortName);
                flushLog(f);
            }
            rb->httpInfo.forceClose = 1;
            SetRequestState(rb, RB_Done);
            return -1;
        }
    } else if (h->httpVerb == HTTP_OPTIONS
               && rb->shortName != NULL
               && strcmp(rb->shortName, "*") == 0) {
        // fast OPTIONS reply
        RequestBaseContinue(rb, NULL);
        RequestBase reply = rb->backPath;
        if (reply != NULL) {
            if (f != NULL) {
                fprintf(f, "-- Fast OPTIONS for #%d, %s:%s\n",
                        rb->index, rb->host, rb->shortName);
                flushLog(f);
            }
            reply->fastOptions++;
            reply->parentVerb = h->httpVerb;
            SetRequestState(reply, RB_NeedRead);
            SetRequestState(rb, RB_Done);
            return 0;
        } else {
            if (f != NULL) {
                fprintf(f, "-- OPTIONS failed for #%d, %s:%s\n",
                        rb->index, rb->host, rb->shortName);
                flushLog(f);
            }
            rb->httpInfo.forceClose = 1;
            SetRequestState(rb, RB_Done);
            return -1;
        }
    } else if (hostLine != NULL) {
		// for this host we use CCN
		
		// make up the name
		struct ccn_charbuf *cb = ccn_charbuf_create();
		SetNameCCN(mb, cb, mb->ccnRoot, rb->host, rb->shortName);
		
		struct ccn_fetch_stream * fs = ccn_fetch_open(mb->fetchBase, cb,
													  rb->shortName,
													  NULL,
													  FetchBuffers,
													  mb->resolveFlags,
													  1);
		ccn_charbuf_destroy(&cb);
		
		if (fs == NULL) {
			// failed to open, so report, then fall through
			// and use HHTP
			fprintf(f, "-- Could not use CCN for %s:%s; using HTTP\n",
                    rb->host, rb->shortName);
			flushLog(f);
		} else {
			fprintf(f, "-- Using CCN for %s:%s\n",
                    rb->host, rb->shortName);
			flushLog(f);
			rb->fetchStream = fs;
			rb->msgCount = 0;
			
			// dest socket is the src connection socket
			int connFD = rb->seSrc->fd;
			SockEntry seDst = AlterSocketCount(mb, connFD, 1);
			rb->seDst = seDst;
			SetRequestState(rb, RB_NeedRead);
			rb->recentTime = GetCurrentTime();
			// convert this request into a reply handler
			SetMsgLen(rb, -1);
			rb->origin = 0;
            rb->parentVerb = h->httpVerb;
			mb->stats.repliesCCN++;
			return 0;
		}
	}
	
	// this is where we come to send the header, appropriately rewritten,
    // to the designated host
    if (f != NULL && h->headerLen > 0 && h->headerLen <= rb->bufferLen) {
        PutRequestMark(rb, "Sending Request Header\n");
        fwrite(rb->buffer, sizeof(char), h->headerLen, f);
        flushLog(f);
    }
	
	SetRequestState(rb, RB_Wait);
	return 0;
	
}

static void
NoteDone(RequestBase rb) {
	MainBase mb = rb->mb;
	FILE *f = mb->debug;
	SetRequestState(rb, RB_Done);
	if (f != NULL) {
		double dt = DeltaTime(rb->startTime, GetCurrentTime());
		intmax_t accum = rb->accum;
		PutRequestMark(rb, "NoteDone");
		ShowNameInfo(rb, ", ");
		fprintf(f, ", %jd bytes", accum);
		if (accum > 0 && dt > 0.0) {
			fprintf(f, " in %4.3f secs (%4.3f MB/sec)",
                    dt, accum*1.0e-6/dt);
		}
		fprintf(f, "\n");
		flushLog(f);
	}
}

// we have just read a buffer, but we have to adjust for byte ranges
static int
AdjustForRanges(RequestBase rb) {
    FILE *f = rb->mb->debug;
    HttpInfo h = &rb->httpInfo;
    if (h->rangeList != NULL
        && h->hasContentRange == 0
        && rb->msgCount == 0) {
        // first time through, need to rewrite the header
        
        // rewrite header to use 206 code
        string buf = (string) rb->buffer;
        int pos = NextLine(buf, 0, rb->bufferLen);
        string replace = "HTTP/1.1 206 Partial content\r\n";
        if (RewriteBuffer(rb, 0, pos, replace) < 0) {
            return retErr(rb->mb, "initial rewrite failed");
        }
        // we probably changed the header length
        h->headerLen = h->headerLen + strlen(replace) - pos;
        
        int64_t rStart = h->rangeList->rangeStart;
        int64_t rStop = h->rangeList->rangeStop;
        int64_t rLen = rStop - rStart + 1;
        int hLen = h->headerLen;
        
        while (pos < hLen) {
            int npos = NextLine(buf, pos, hLen);
            if (npos <= pos) break;
            int cpos = pos;
            while (cpos < npos) {
                char c = buf[cpos];
                if (c == ' ' || c == ':') break;
                cpos++;
            }
            if (TokenPresent(buf+pos, cpos-pos, "Content-Length")) {
                // note: this rewrite assumes that the requested range can be satisfied
                // if not, we won't know until it is too late
                char temp[256];
                int tpos = 0;
                while (cpos < npos) {
                    char c = buf[cpos];
                    if (c == ' ' || c == ':') cpos++;
                    else break;
                }
                h->assertLength = EvalUint(buf, cpos);
                if (h->assertLength < 1)
                    return retErr(rb->mb, "invalid Content-Length");
                if (rStop < 0) {
                    // unknown length originally, so now fill it in
                    rStop = h->assertLength-1;
                    h->rangeList->rangeStop = rStop;
                    rLen = rStop - rStart + 1;
                }
                tpos =+ snprintf(temp+tpos, sizeof(temp) - tpos,
                                 "Content-Length: %jd\r\n",
                                 (intmax_t) rLen
                                 );
                tpos =+ snprintf(temp+tpos, sizeof(temp) - tpos,
                                 "Content-Range: bytes %ju-%ju/%ju\r\n",
                                 (intmax_t) rStart,
                                 (intmax_t) rStop,
                                 (intmax_t) h->assertLength
                                 );
                if (RewriteBuffer(rb, pos, npos-pos, temp) < 0)
                    return retErr(rb->mb, "range rewrite failed");
                int deltaChars = strlen(temp) - (npos-pos);
                hLen = hLen + deltaChars;
                h->headerLen = hLen;
                int64_t clamp = hLen+rLen;
                if (rLen > 0 && clamp < rb->bufferLen) {
                    // clamp the buffer length to reflect the range
                    rb->bufferLen = clamp;
                }
                // now we know what the delivered number of bytes should be
                rb->headerLenReply = hLen;
                SetMsgLen(rb, clamp);
                if (f != NULL) {
                    fprintf(f, "-- in AdjustForRanges");
                    fprintf(f, ", headerLen %d", hLen);
                    fprintf(f, ", headerLenInit %d", rb->headerLenInit);
                    fprintf(f, ", bufferLen %d", (int) rb->bufferLen);
                    fprintf(f, ", clamp %jd,", (intmax_t) clamp);
                    fprintf(f, ", rLen %jd\n", (intmax_t) rLen);
                    fflush(f);
                }
                break;
            }
            pos = npos;    
        }
        
        int off = h->headerLen;
        string ptr = buf+off;
        
        int rem = rb->bufferLen - off;
        
        if (rStart < rem) {
            // the current buffer at least the start of the content
            if (rStart > 0) {
                // have to get rid of a few bytes
                rem = rem - rStart;
                memmove(ptr, ptr + rStart, rem);
                rb->bufferLen = off + rem;
            }
            if (rLen <= rem) {
                // done, as of this buffer
                rb->bufferLen = off + rLen;
                SetMsgLen(rb, rb->bufferLen);
            }
        } else {
            // we have the header, but no bytes
            // seek to the desired place and append what we can get
            int64_t seekTo = rb->headerLenInit+rStart;
            if (f != NULL) {
                fprintf(f, "-- seek to %jd: rStart %jd, rStop %jd\n", 
                        (intmax_t) seekTo,
                        (intmax_t) rStart,
                        (intmax_t) rStop);
            }
            ccn_fetch_seek(rb->fetchStream, seekTo);
            rb->bufferLen = off;
            rb->fetchOff = off;
            return 0;
        }
        
    } else {
        // subsequent fetches just get delivered (possibly truncated at the end)
        int64_t rStop = h->rangeList->rangeStop;
        int len = rb->bufferLen;
        if (rStop >= 0) {
            int64_t nextAccum = rb->accum + len;
            int64_t limAccum = rStop+1+h->headerLen;
            if (nextAccum >= limAccum) {
                // we have reached the range end inside of this buffer
                int delta = nextAccum - limAccum;
                len = len - delta;
                rb->bufferLen = len;
                SetMsgLen(rb, limAccum);
                if (f != NULL) {
                    fprintf(f, "-- in AdjustForRanges, delta %jd, bufferLen %d\n",
                            (intmax_t) delta, (int) rb->bufferLen);
                }
            }
        }
    }
    return 1;
}

static int
RequestBaseStep(RequestBase rb) {
	MainBase mb = rb->mb;
	FILE *f = mb->debug;
	uint64_t now = GetCurrentTime();
	
	// since we only have one buffer, we have to be careful about the state
	switch (rb->state) {
		case RB_Start: {
			// this only happens once for the outbound path
			return RequestBaseStart(rb);
		}
		case RB_Wait: {
			// here's where we wait for an available socket
			int match = 0;
			RequestBase each = mb->requestList;
			for (; each != NULL; each=each->next) {
				if (each->seDst != NULL) {
					switch (each->state) {
						case RB_NeedRead:
						case RB_NeedWrite:
							if (SameHost(rb->host, each->host))
								match++;
							break;
						default: {}
					}
				}
			}
			if (match < rb->maxConn)
				return RequestBaseContinue(rb, NULL);
			break;
		}
		case RB_NeedRead: {
			SockEntry se = rb->seSrc;
			intmax_t nb = -1;
            HttpInfo h = &rb->httpInfo;
            string buf = (string) rb->buffer;
            if (rb->msgCount == 0)
                mb->stats.replies++;
            if (rb->forceFail) {
                // synthetic not found
                WriteNotFoundReply(rb);
                nb = rb->bufferLen;
                if (f != NULL) {
                    fprintf(f, "-- in RequestBaseStep, rb->forcefail\n%s", buf);
                    flushLog(f);
                }
            } else if (rb->fastOptions) {
                // reply for OPTIONS, no server probe
                WriteOptionsReply(rb);
                nb = rb->bufferLen;
                if (f != NULL) {
                    fprintf(f, "-- in RequestBaseStep, fast OPTIONS reply\n%s", buf);
                    flushLog(f);
                }
            } else if (rb->fetchStream != NULL) {
				// we get this buffer through CCN
				int off = rb->fetchOff;
                string buf = (string) rb->buffer;
                nb = ccn_fetch_read(rb->fetchStream, buf+off, CCN_CHUNK_SIZE);
				if (nb < 0)
					// nothing to do, no characters available
					return 0;
				if (nb > 0) {
					mb->stats.replyReadsCCN++;
					mb->stats.replyBytesCCN = mb->stats.replyBytesCCN + nb;
                    rb->bufferLen = nb + off;
                    rb->fetchOff = 0;
				}
			} else {
				// we get this buffer through HTTP
				int fd = se->fd;
				int bit = FD_ISSET(fd, &mb->sds.readFDS);
				if (bit) {
					// this fd has something to read
					FD_CLR(fd, &mb->sds.readFDS);
					nb = RobustRecvmsg(rb, se);
				} else {
					// nothing to do here, the fd is not ready to read
					return 0;
				}
			}
			double dt = DeltaTime(rb->recentTime, now);
            
			if (nb <= 0) {
				if (nb == 0) {
					// no more from this socket
					NoteDone(rb);
					return 0;
				}
				return SetRequestErr(rb, "RequestBaseStep not received", 0);
			}
			nb = rb->bufferLen;
			if (rb->origin == 0) {
				// bump the global stats
				mb->stats.replyReads++;
				mb->stats.replyBytes = mb->stats.replyBytes + nb;
			}
			if (f != NULL) {
				PutRequestMark(rb, "read");
				if (rb->fetchStream == NULL) {
					fprintf(f, " %jd bytes on sock %d, dt %4.3f, %s\n",
                            nb, se->fd, dt, rb->host);
				} else {
					intmax_t pos = ccn_fetch_position(rb->fetchStream) - nb;
					intmax_t seg = pos / CCN_CHUNK_SIZE;
					fprintf(f, " %jd bytes via CCN, seg %jd, dt %4.3f, %s\n",
                            nb, seg, dt, rb->host);
				}
				flushLog(f);
			}
            if (f != NULL) {
                fprintf(f, "-- in RequestBaseStep, rb->msgCount %d, parentVerb %s\n",
                        (int) rb->msgCount, VerbToString(rb->parentVerb));
                flushLog(f);
            }
			if (rb->msgCount == 0 && rb->forceFail == 0) {
				int ck = CheckHttpHeader(rb);
				if (ck < 0) {
					// bad header
					SetRequestErr(rb, "Invalid header", 0);
                    fwrite(rb->buffer, sizeof(char), rb->bufferLen, f);
                    flushLog(f);
					return -1;
				}
				if (ck == 0) {
					// header not complete, go back and get another line
					rb->recvOff = nb;
					if (nb + 1000 > rb->bufferMax) {
						SetRequestErr(rb, "Header too long", 0);
						return -1;
					}
					if (f != NULL) {
						fprintf(f, "-- need additional header bytes\n");
						flushLog(f);
					}
					return 0;
				}
			}
			ChunkInfo info = &rb->chunkInfo;
            
			if (rb->msgCount == 0) {
				// first time through, should get the info
				// verb == HTTP_NONE marks a reply
                ExtractHTTPInfo(rb, HTTP_NONE);
			} else if (info->state >= Chunk_Skip) {
				AdvanceChunks(mb, rb->buffer, 0, nb, info);
				FILE *f = mb->debug;
				switch (info->state) {
					case Chunk_Done: {
						SetMsgLen(rb, rb->accum + nb);
						if (f != NULL) {
							fprintf(f, "-- chunking done, msgLen %jd\n",
                                    (intmax_t) rb->msgLen);
							flushLog(f);
						}
						break;
					}
					case Chunk_Error: {
						SetMsgLen(rb, rb->accum + nb);
						if (f != NULL) {
							fprintf(f, "-- chunking error, chunkRem %u, msgLen %jd\n",
                                    info->chunkRem, (intmax_t) rb->msgLen);
							flushLog(f);
						}
						rb->httpInfo.forceClose = 1;
						break;
					}
					default: {
						if (f != NULL) {
							fprintf(f, "-- chunking in progress, chunkRem %u\n",
                                    info->chunkRem);
							flushLog(f);
						}
					}
				}
			}
            if (rb->parentVerb == HTTP_GET
                && rb->fetchStream != NULL && h->rangeList != NULL) {
                // we have a range request
                int adj = AdjustForRanges(rb);
                if (adj == 0) return 0;
            }
            nb = rb->bufferLen;
            int hLen = h->headerLen;
            if (rb->headerLenReply == 0)
                rb->headerLenReply = hLen;
            switch (rb->parentVerb) {
                case HTTP_HEAD: 
                    // reply to HEAD request, so truncate without contents
                    rb->bufferLen = hLen;
                    if (f != NULL) {
                        fprintf(f, "-- HEAD truncation %u\n", hLen);
                        flushLog(f);
                    }
                    SetMsgLen(rb, hLen);
                    break;
                case HTTP_OPTIONS:
                    // reply to OPTIONS request, completely rewrite the header
                    WriteOptionsReply(rb);
                    hLen = rb->bufferLen;
                    nb = hLen;
                    SetMsgLen(rb, hLen);
                    break;
                case HTTP_GET:
                    // reply to GET request
                    if (rb->msgCount == 0 && hLen > 0) {
                        if (f != NULL) {
                            string buf = (string) rb->buffer;
                            int i = 0;
                            int run = nb - hLen;
                            if (run > 16) run = 16;
                            PutRequestMark(rb, "Sending Reply Header\n");
                            fwrite(buf, sizeof(char), hLen, f);
                            if (run > 0) {
                                while (i < run) {
                                    char c = buf[hLen+i];
                                    if ((i & 15) == 0) {
                                        if (i > 0) fprintf(f, "\n");
                                        fprintf(f, "-- %4d:", i);
                                    }    
                                    i++;
                                    fprintf(f, " %02x", c & 255);
                                }
                                fprintf(f, "\n");
                            }
                            flushLog(f);
                        }
                    }
                    break;
                default:  
                    // should not happen
                    break;
            }
            
			if (rb->msgLen <= rb->accum + nb
				&& rb->origin == 0
				&& rb->seSrc != NULL
				&& rb->httpInfo.forceClose == 0
				&& rb->httpInfo.keepAlive > 0) {
				RequestBase waiter = FindWaiter(rb);
				if (waiter != NULL) {
					waiter->sockTime = rb->sockTime;
					RequestBaseContinue(waiter, rb->seSrc);
				}
			}
			rb->msgCount++;
			rb->recentTime = now;
			
			// now pass along the reply
			RobustSendmsg(rb, rb->seDst);
			SetRequestState(rb, RB_NeedWrite);
			break;
		}
		case RB_NeedWrite: {
			if (rb->seDst == NULL) {
				// this is really bogus!
				return SetRequestErr(rb, "RequestBaseStep rb->seDst == NULL", 0);
			}
			int fd = rb->seDst->fd;
			int bit = FD_ISSET(fd, &mb->sds.writeFDS);
			FD_CLR(fd, &mb->sds.writeFDS);
			if (bit == 0) {
				// we should not have reached here, but it's not fatal
			} else if (rb->sendOff > 0) {
				// the write was incomplete, so try to finish it
				RobustSendmsg(rb, rb->seDst);
				SetRequestState(rb, RB_NeedWrite);
			} else {
				// the write has completed, so we can turn around and read
				double dt = DeltaTime(rb->recentTime, now);
				int nb = rb->bufferLen;
				rb->accum = rb->accum + nb;
				rb->bufferLen = 0;
				SetRequestState(rb, RB_NeedRead);
				rb->recentTime = now;
				RequestBase reply = rb->backPath;
				if (rb->fetchStream != NULL) {
					// we get this buffer through CCN
				} else if (reply != NULL && reply->state == RB_None) {
					// allow the replies to start coming back
					// once the write has worked
					SetRequestState(reply, RB_NeedRead);
					reply->recentTime = now;
				}
				
				FILE *f = mb->debug;
				if (f != NULL) {
					PutRequestMark(rb, "wrote");
					fprintf(f, " %d bytes on %d, dt %4.3f", nb, fd, dt);
					if (rb->msgLen >= 0)
						fprintf(f, ", msgLen %jd", (intmax_t) rb->msgLen);
					fprintf(f, ", accum %jd\n", (intmax_t) rb->accum);
					flushLog(f);
				}
				if ((rb->msgLen >= 0) && (rb->accum >= rb->msgLen)) {
					// done with the transfers
					NoteDone(rb);
				}
			}
			break;
		}
		default: {
		}
	}
	return 0;
}

static MainBase
NewMainBase(FILE *f, int maxBusy, struct ccn_fetch * fetchBase) {
	MainBase mb = ProxyUtil_StructAlloc(1, MainBaseStruct);
	TimeMarker now = GetCurrentTime();
	mb->startTime = now;
	SockBase sb = SH_NewSockBase();
	sb->debug = f;
	mb->sockBase = sb;
	sb->clientData = mb;
	sb->startTime = now; // keep them sync'd
	if (maxBusy < 2) maxBusy = 2;
	if (maxBusy > 20) maxBusy = 20;
	mb->maxBusy = maxBusy;
	mb->fetchBase = fetchBase;
	mb->ccnFD = ccn_get_connection_fd(ccn_fetch_get_ccn(fetchBase));
	mb->usePort = 8080;
	mb->ccn_flags = (ccn_fetch_flags_NoteAll);
	mb->debug = f;
    mb->custom = "./HttpProxy.list";
    return mb;
}

static int
StartMainBase(MainBase mb) {
	FILE *pf = fopen(mb->custom, "r");
	if (pf == NULL) {
		fprintf(mb->debug, "** No HttpProxy.list file found!\n");
		flushLog(mb->debug);
        return -1;
	} else {
		char buf[1024+4];
		int maxLen = 1024;
		for (;;) {
			char *line = fgets(buf, maxLen, pf);
			if (line == NULL) break;
			int lineLen = strlen(line);
			int start = SkipOverBlank(line, 0, lineLen);
			int pos = SkipToBlank(line, start, lineLen);
			if (pos > start && line[start] != '#') {
				// we have a host spec, 1 per line
				HostLine h = ProxyUtil_StructAlloc(1, HostLineStruct);
				h->pat = newStringPrefix(line+start, pos-start);
				h->patLen = pos-start;
				// try to extract any flag specs
				for (;;) {
					start = SkipOverBlank(line, pos, lineLen);
					pos = SkipToBlank(line, start, lineLen);
					if (start >= pos) break;
					string tok = line+start;
					int tokLen = pos-start;
					if (tokLen > 1) {
						if (SwitchPresent(tok, tokLen, "-noCookie")) {
							h->flags |= HostLine_NoCookie;
						} else if (SwitchPresent(tok, tokLen, "-noReferer")) {
							h->flags |= HostLine_NoReferer;
						} else if (SwitchPresent(tok, tokLen, "-needDot")) {
							h->flags |= HostLine_NeedDot;
						} else if (SwitchPresent(tok, tokLen, "-noQuery")) {
							h->flags |= HostLine_NoQuery;
						} else if (SwitchPresent(tok, tokLen, "-single")) {
							h->flags |= HostLine_SingleConn;
						} else if (SwitchPresent(tok, tokLen, "-proxy")) {
							h->flags |= HostLine_Proxy;
						} else if (SwitchPresent(tok, tokLen, "-translate")) {
							h->flags |= HostLine_Translate;
                            start = SkipOverBlank(line, pos, lineLen);
                            pos = SkipToBlank(line, start, lineLen);
                            if (start >= pos) break;
                            h->translate = newStringPrefix(line+start, pos-start);
						} else if (SwitchPresent(tok, tokLen, "-fail")) {
							h->flags |= HostLine_FailQuick;
						} else if (SwitchPresent(tok, tokLen, "-queryHack")) {
							h->flags |= HostLine_QueryHack;
						}
					}
				}
				// append to the end
				HostLine tail = mb->hostLines;
				if (mb->hostLines == NULL)
					mb->hostLines = h;
				else {
					while (tail->next != NULL) tail = tail->next;
					tail->next = h;
				}
			}
		}
		fclose(pf);
	}
	return 0;
}

static void
DestroyMainBase(MainBase mb) {
	if (mb != NULL) {
		RequestBase rb = mb->requestList;
		while (rb != NULL) {
			RequestBase next = rb->next;
			if (rb->backPath != NULL) DestroyRequestBase(rb);
			rb = next;
		}
		rb = mb->requestList;
		while (rb != NULL) {
			RequestBase next = rb->next;
			DestroyRequestBase(rb);
			rb = next;
		}
		if (mb->fetchBase != NULL)
			ccn_fetch_destroy(mb->fetchBase);
		
		
		HostLine h = mb->hostLines;
		mb->hostLines = NULL;
		while (h != NULL) {
			HostLine next = h->next;
			freeString(h->pat);
			free(h);
			h = next;
		}
        
		free(mb);
	}
}

static void
ScanRequestsCCN(MainBase mb) {
	ccn_fetch_poll(mb->fetchBase);
	RequestBase rb = mb->requestList;
	while (rb != NULL) {
		RequestBaseState state = rb->state;
		RequestBase next = rb->next;
		if (rb->fetchStream != NULL) {
			switch (state) {
				case RB_NeedRead: {
					RequestBaseStep(rb);
					break;
				}
				case RB_NeedWrite: {
					RequestBaseStep(rb);
					break;
				}
				case RB_Error:
				case RB_Done: {
					DestroyRequestBase(rb);
					next = mb->requestList;
					break;
				}
				default: {
				}
			}
			
		}
		rb = next;
	}
}

static void
ScanTimeouts(MainBase mb) {
	RequestBase rb = mb->requestList;
	uint64_t now = GetCurrentTime();
	double timeoutSecs = mb->timeoutSecs; // can we do better here?
	FILE *f = mb->debug;
	while (rb != NULL) {
		RequestBase next = rb->next;
		RequestBaseState state = rb->state;
		double dt = DeltaTime(rb->recentTime, now);
		if (dt > timeoutSecs && state == RB_NeedRead) {
			// this is not going to make progress
			rb->httpInfo.forceClose = 1;
			if (f != NULL) {
				PutRequestMark(rb, "Timeout");
				fprintf(f, ", %4.3f > %1.0f\n", dt, timeoutSecs);
				flushLog(f);
			}
			DestroyRequestBase(rb);
			next = mb->requestList;
		}
		rb = next;
	}
}

static void
ScanWaiting(MainBase mb) {
	RequestBase rb = mb->requestList;
	while (rb != NULL) {
		RequestBase next = rb->next;
		if (rb->state == RB_Wait)
			RequestBaseStep(rb);
		rb = next;
	}
}

static void
ScanRequestsHttp(MainBase mb) {
	// first, check for initial requests
	int sockFD = mb->sockFD;
	if (FD_ISSET(sockFD, &mb->sds.readFDS)) {
		// initial connection request from the client
		FD_CLR(sockFD, &mb->sds.readFDS);
		MaybeNewRequestBase(mb);
	}
	
	// now, check for HTTP requests being done
	RequestBase rb = mb->requestList;
	while (rb != NULL) {
		RequestBase next = rb->next;
		if (rb->fetchStream == NULL) {
			// this connection is via HTTP
			RequestBaseStep(rb);
			RequestBaseState state = rb->state;
			if (state == RB_Done || state == RB_Error) {
				// we need to remove this request
				RequestBase fwd = rb->fwdPath;
				RequestBase back = rb->backPath;
				DestroyRequestBase(rb);
				if (fwd != NULL) {
					// the forward link is not useful any more
					// whether it was normal or abnormal termination
					DestroyRequestBase(fwd);
				} else if (state == RB_Error) {
					if (back != NULL)
						// abort the back path as well
						// but only for abnormal termination
						DestroyRequestBase(back);
				}
				// start scan over (infrequent)
				next = mb->requestList;
			}
		}
		rb = next;
	}
	
}

static void
ShowStats(MainBase mb) {
	FILE *f = mb->debug;
	if (f == NULL) return;
	PutTimeMark(mb);
	fprintf(f, "stats, socks %d", mb->sockBase->nSocks);
	fprintf(f, ", req %jd, rep %jd, reads %jd, bytes %jd",
            (intmax_t) mb->stats.requests,
            (intmax_t) mb->stats.replies,
            (intmax_t) mb->stats.replyReads,
            (intmax_t) mb->stats.replyBytes);
	fprintf(f, ", repCCN %jd, readsCCN %jd, bytesCCN %jd",
            (intmax_t) mb->stats.repliesCCN,
            (intmax_t) mb->stats.replyReadsCCN,
            (intmax_t) mb->stats.replyBytesCCN);
	fprintf(f, "\n");
	flushLog(f);
}

static int
DispatchLoop(MainBase mb) {
	
	int waitMillis = 1;
	int res = 0;
	for (;;) {
		uint64_t nChanges = mb->nChanges;
		
		TrySelect(mb);
		
		ScanRequestsCCN(mb);
		if (mb->nReady > 0) {
			
			// next, try any HTTP traffic
			ScanRequestsHttp(mb);
			
		}
		
		ScanTimeouts(mb);
		ScanWaiting(mb);
		
		// wait for a change
		if (nChanges == mb->nChanges) {
			// nothing much is changing so sleep and increase the wait
			MilliSleep(waitMillis);
			if (waitMillis < 64) waitMillis++;
			SH_CheckTimeouts(mb->sockBase);
			SH_PruneAddrCache(mb->sockBase, 600, 300);
		} else {
			// we saw a change, so no wait and reset the sleep time
			waitMillis = 1;
			ShowStats(mb);
		}
		
    }
	return res;
}

static int
ExecMainBase(MainBase mb) {
    int res = 0;
    signal(SIGPIPE, SIG_IGN);
    
    struct sockaddr_in sa;
    struct sockaddr *sap = (struct sockaddr *) &sa;
    int sockFD = socket(PF_INET, SOCK_STREAM, IPPROTO_TCP);
    
    if (-1 == sockFD)
        return retFail(mb, "can not create socket");
    
    sa.sin_family = PF_INET;
    sa.sin_port = htons(mb->usePort);
    sa.sin_addr.s_addr = INADDR_ANY;
    
    int it = 0;
    for (;;) {
        int bindRes = bind(sockFD, sap, sizeof(struct sockaddr_in));
        if (bindRes < 0) {
            int e = errno;
            if (e == EADDRINUSE && it <= 120) {
                // may be useful to wait for timeout of the old bind
                // we probably did not close it properly due to an error
                if (it == 0) fprintf(mb->debug, "Waiting for proxy socket...\n");
                flushLog(mb->debug);
                MilliSleep(1000);
            } else {
                close(sockFD);
                return retFail(mb, "error bind failed");
            }
        } else break;
        it++;
    }
    if (-1 == listen(sockFD, 10)) {
        close(sockFD);
        return retFail(mb, "error listen failed");
    }
    
    double bt = DeltaTime(0, GetCurrentTime());
    fprintf(mb->debug, "Socket listening, fd %d, baseTime %7.6f\n", sockFD, bt);
    flushLog(mb->debug);
    
    res = StartMainBase(mb);
    if (res < 0) return res;
    if (mb->fetchBase == NULL)
        // should not happen unless ccnd is not answering
        return retErr(mb, "Init failed!  No ccnd?");
    SetSockFD(mb, sockFD);
    SetSockEntryAddr(mb->client, sap);
    mb->ccnRoot = "TestCCN";
    mb->removeProxy = 0;
    mb->removeHost = 1;
    mb->defaultKeepAlive = 13;
    mb->timeoutSecs = 30;
    mb->maxConn = 2; // RFC 2616 (pg. 2)
    mb->resolveFlags = CCN_V_HIGHEST;
    
    if (mb->debug != NULL && mb->ccn_flags != ccn_fetch_flags_None)
        ccn_fetch_set_debug(mb->fetchBase, mb->debug, mb->ccn_flags);
    
    return DispatchLoop(mb);
}

int
main(int argc, string *argv) {
	
	FILE *f = stdout;
	
	struct ccn_fetch * fetchBase = ccn_fetch_new(NULL);
	if (fetchBase == NULL) {
		fprintf(stdout, "** Can't connect to ccnd\n");
		return -1;
	}
    
	MainBase mb = NewMainBase(f, 16, fetchBase);
    int res = 0;
	
	int i = 1;
	for (; i <= argc; i++) {
        string arg = argv[i];
		if (arg == NULL || arg[0] == 0) {
		} else if (arg[0] == '-') {
			if (strcasecmp(arg, "-ccnRoot") == 0) {
				i++;
				arg = argv[i];
				mb->ccnRoot = arg;
			} else if (strcasecmp(arg, "-remProxy") == 0) {
				mb->removeProxy = 1;
			} else if (strcasecmp(arg, "-remHost") == 0) {
				mb->removeHost = 1;
			} else if (strcasecmp(arg, "-keepProxy") == 0) {
				mb->removeProxy = 0;
			} else if (strcasecmp(arg, "-keepHost") == 0) {
				mb->removeHost = 0;
			} else if (strcasecmp(arg, "-noDebug") == 0) {
				mb->debug = NULL;
				mb->ccn_flags = ccn_fetch_flags_None;
			} else if (strcasecmp(arg, "-absTime") == 0) {
				mb->startTime = 0;
			} else if (strcasecmp(arg, "-resolveHigh") == 0) {
				mb->resolveFlags = CCN_V_HIGH;
			} else if (strcasecmp(arg, "-resolveHighest") == 0) {
				mb->resolveFlags = CCN_V_HIGHEST;
			} else if (strcasecmp(arg, "-hostFromGet") == 0) {
				mb->hostFromGet = 1;
				mb->removeHost = 1;
			} else if (strcasecmp(arg, "-keepAlive") == 0) {
				i++;
				int n = 0;
				if (i <= argc) n = atoi(argv[i]);
				if (n < 1 || n > 120) {
					fprintf(stdout, "** bad keepAlive: %d\n", n);
                    res = -1;
                    break;
				}
				mb->defaultKeepAlive = n;
			} else if (strcasecmp(arg, "-timeoutSecs") == 0) {
				i++;
				int n = 0;
				if (i <= argc) n = atoi(argv[i]);
				if (n < 1 || n > 120) {
					fprintf(stdout, "** bad timeoutSecs: %d\n", n);
                    res = -1;
                    break;
				}
				mb->timeoutSecs = n;
			} else if (strcasecmp(arg, "-usePort") == 0) {
				i++;
				int n = 0;
				if (i <= argc) n = atoi(argv[i]);
				if (n < 1 || n >= 64*1024) {
					fprintf(stdout, "** bad port: %d\n", n);
                    res = -1;
                    break;
				}
				mb->usePort = n;
			} else if (strcasecmp(arg, "-custom") == 0) {
				i++;
				if (i <= argc)
                    mb ->custom = argv[i];
			} else if (strcasecmp(arg, "-maxConn") == 0) {
				i++;
				int n = 0;
				if (i <= argc) n = atoi(argv[i]);
				if (n < 1 || n > 16) {
					fprintf(stdout, "** bad maxConn: %d\n", n);
                    res = -1;
                    break;
				}
				mb->maxConn = n;
			} else {
				fprintf(stdout, "** bad arg: %s\n", arg);
                fprintf(stdout, "Usage: %s -remProxy -remHost -keepProxy -keepHost -noDebug -addTime\n"
                        "          -resolveHigh -resolveHighest -hostFromGet\n"
                        "          -keepAlive <n> -timeoutSecs <n> -usePort <n> -custom <txt> -maxConn <n>\n",
                        argv[0]);
                res = -1;
                break;
			}
		}
    }
    if (res < 0)
        exit(1);
    
    res = ExecMainBase(mb);
	
    DestroyMainBase(mb);
	return res;
	
}

