/*
 * HttpProxy/NetFetch.c
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
 * Serves chunks of data to ccn from a file directory, with missing files
 * fetched using a simple HTTP protocol.
 *
 */

#include "./ProxyUtil.h"
#include "./SockHop.h"

#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <strings.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>
#include <ccn/keystore.h>
#include <ccn/signing.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>

// TBD: the following constants should be more principled
#define CCN_CHUNK_SIZE 4096
#define CCN_MAP_SIZE (64*CCN_CHUNK_SIZE)
#define MaxFileName 1024
#define MainPollMillis 10
#define KeepAliveDefault 115
#define DefaultFreshness (-1)
// default is not to go stale
#define TempSegments 4

#define UsePread 1

typedef struct ccn_charbuf *MyCharbuf;
typedef intmax_t seg_t;

static void
flushLog(void) {
	fflush(stdout);
	fflush(stderr);
}

static int
retFail(char *msg) {
	string sysErr = strerror(errno);
	fprintf(stdout, "** error: %s - %s\n", msg, sysErr);
	return -1;
}

static int
retErr(char *msg) {
	fprintf(stdout, "** error: %s\n", msg);
	flushLog();
	return -1;
}

static char *hex = "0123456789abcdef";

static char
isNiceChar(char c) {
	if (c <= ' ') return 0;
	if (c >= '0' && c <= '9') return c;
	if (c >= 'A' && c <= 'Z') return c;
	if (c >= 'a' && c <= 'z') return c;
	if (c == '-' || c == '.') return c;
	return 0;
}

static int
UnPercName(char *buf, int pos, int lim, char *s) {
	int i = 0;
	int start = pos;
	for (;;) {
		char c = s[i];
		if (c == 0) break;
		i = i + 1;
		if (c == '%') {
			c = HexDigit(s[i+0]) * 16 + HexDigit(s[i+1]);
			i = i + 2;
		}
		if (pos < lim) { buf[pos] = c; pos++; };
	}
	buf[pos] = 0;
	return pos-start;
}

static char *
MakeId(char *host, char *name) {
	char id[MaxFileName+4];
	snprintf(id, MaxFileName, "%s:%s", host, name);
	char *ret = Concat(id, "");
	return ret;
}

static char *
NewUnPercName(char *s) {
	char us[MaxFileName+4];
	UnPercName(us, 0, sizeof(us), s);
	char *ret = Concat(us, "");
	return ret;
}

static int
HostMatch(char *host, char *nrHost) {
	if (host == nrHost) return 1;
	if (host == NULL || nrHost == NULL) return 0;
	return (strcasecmp(host, nrHost) == 0);
}

////////////////////////////////
// File Node support
////////////////////////////////

typedef struct MainDataStruct *MainData;
typedef struct FileNodeStruct *FileNode;
typedef struct InterestDataStruct *InterestData;
typedef struct NetRequestStruct *NetRequest;
typedef struct HttpInfoStruct *HttpInfo;


struct StatsStruct {
	uint64_t filesCreated;
	uint64_t fileBytes;
	uint64_t interestsSeen;
	uint64_t segmentsPut;
	uint64_t bytesPut;
};

struct MainDataStruct {
	SockBase sockBase;
	NetRequest requests;
	struct ccn *ccn;
	FileNode files;
	int nFiles;
	struct ccn_keystore *keystore;
	char *progname;
	int64_t mapped;
	int debug;
	int verbose;
	int recentPort;
	int maxBusySameHost;
	int keepAliveDefault;
	uint64_t startTime;
	uint64_t changes;
	char *recentHost;
	char *fsRoot;
	char *ccnRoot;
	struct StatsStruct stats;
};

typedef struct SegListStruct *SegList;
struct SegListStruct {
	SegList next;
	seg_t seg;
};

struct FileNodeStruct {
	MainData md;
	FileNode next;
	int fd;
	int final;
	int marked;
	void *mapAddr;
	off_t mapLen;
	seg_t mapOff;
	int create;
	int fresh;
	off_t fileSize;
	seg_t nSegs;
	struct timespec modTime;
	char *root;
	char *dir;
	char *fileName;
	char *shortName;
	char *unPercName;
	char *id;
	uint64_t firstUsed;
	uint64_t lastUsed;
	uint64_t nSegsPut;
	seg_t maxSegPut;
	int nTemp;
	void * *tempBufs;
	seg_t *tempSegs;
	int *tempLengths;
    struct ccn_signing_params signing_params; // must be per-file
};

struct InterestDataStruct {
	MainData md;
	struct ccn_charbuf *rootName;
	char *fsRoot;
	char *ccnRoot;
};

struct NetRequestStruct {
	MainData md;
	NetRequest next;
	char *ccnRoot;
	char *fsRoot;
	char *host;
	char *kind;
	int port;
	int error;
	char *shortName;
	char *unPercName;
	char *id;
	SockEntry se;  // != NULL for a busy request, NULL for pending connection
	FileNode fn;
	void *buf;
	int bufSize;
	int endSeen;
	struct iovec iov;
	struct msghdr msg;
	SegList segRequests;
	seg_t maxSegRequest;
	seg_t maxSegStored;
	uint64_t startTime;
	HttpInfo httpInfo;
};

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

struct HttpInfoStruct {
	NetRequest nr;
	struct ChunkInfoStruct chunkInfo;
	int version;
	int subversion;
	int code;
	int error;
	int chunked;
	int forceClose;
	ssize_t headerLen;
	ssize_t contentLen;
	ssize_t totalLen;
};

static int
RemSegRequest(NetRequest nr, seg_t seg) {
	SegList lag = NULL;
	SegList each = nr->segRequests;
	while (each != NULL) {
		if (each->seg == seg) {
			if (lag == NULL) nr->segRequests = each->next;
			else lag->next = each->next;
			return 1;
		}
		lag = each;
		each=each->next;
	}
	return 0;
}

static int
AddSegRequest(NetRequest nr, seg_t seg) {
	// adds the seg to the request list in sorted order
	// duplicates are suppressed, search is linear
	SegList lag = NULL;
	SegList each = nr->segRequests;
	while (each != NULL) {
		if (each->seg == seg) return 0;
		if (each->seg > seg) break;
		lag = each;
		each=each->next;
	}
	SegList req = ProxyUtil_StructAlloc(1, SegListStruct);
	req->seg = seg;
	if (lag == NULL) {
		req->next = nr->segRequests;
		nr->segRequests = req;
	} else {
		req->next = lag->next;
		lag->next = req;
	}
	return 0;
}

static int
ExpandTempSegments(FileNode fn) {
	int oTemp = fn->nTemp;
	int nTemp = fn->nTemp+1;
	void * *newBufs = ProxyUtil_Alloc(nTemp, void *);
	seg_t *newSegs = ProxyUtil_Alloc(nTemp, seg_t);
	int *newLengths = ProxyUtil_Alloc(nTemp, int);
	int i = 0;
	for (; i < fn->nTemp; i++) {
		newBufs[i] = fn->tempBufs[i];
		newSegs[i] = fn->tempSegs[i];
		newLengths[i] = fn->tempLengths[i];
	}
	if (oTemp > 0) {
		free(fn->tempBufs);
		free(fn->tempSegs);
		free(fn->tempLengths);
	}
	fn->tempBufs = newBufs;
	fn->tempSegs = newSegs;
	fn->tempLengths = newLengths;
	fn->nTemp = nTemp;
	newBufs[oTemp] = ProxyUtil_Alloc(CCN_CHUNK_SIZE, char);
	newSegs[oTemp] = -1;
	newLengths[oTemp] = 0;
	return oTemp;
}

static int
FillTempSegments(FileNode fn, void *buf, int n) {
	off_t fileSize = fn->fileSize;
	seg_t seg = fileSize / CCN_CHUNK_SIZE;
	int off = fileSize % CCN_CHUNK_SIZE;
	// first, try to transfer bytes to the 
	while (n > 0) {
		int vic = -1;
		int i = 0;
		for (; i < fn->nTemp; i++) {
			seg_t tSeg = fn->tempSegs[i];
			if (seg == tSeg) {
				vic = i;
				break;
			}
			if (tSeg + TempSegments < seg) {
				// old enough to retire, so make this entry available
				tSeg = -1;
				fn->tempSegs[i] = -1;
				fn->tempLengths[i] = 0;
			}
			if (tSeg < 0) vic = i;
		}
		if (vic < 0) {
			// not enough temp segments, so make more
			vic = ExpandTempSegments(fn);
		}
		if (vic < 0)
			return retErr("FillTempSegments bad state, vic < 0");
		seg_t vSeg = fn->tempSegs[vic];
		if (vSeg < 0) fn->tempSegs[vic] = seg;
		// found a match, check for room
		int pos = fn->tempLengths[vic];
		if (pos != off) {
			// we have stuff already written?
			return retErr("FillTempSegments bad state");
		}
		int rem = CCN_CHUNK_SIZE - pos;
		if (rem > 0) {
			// non-zero to move
			if (n < rem) rem = n;
			string src = ((string) buf) + off;
			string dst = ((string) fn->tempBufs[vic]) + off;
			memcpy(dst, src, rem);
			if (fn->md->debug) {
				fprintf(stdout,
						"-- FillTempSegments, fd %d, n %d, rem %d, vic %d, seg %jd, off %d\n",
						fn->fd, n, rem, vic, seg, off);
                
			}
			off = off + rem;
			seg = seg + (off / CCN_CHUNK_SIZE);
			off = off % CCN_CHUNK_SIZE;
			n = n - rem;
			fn->tempLengths[vic] = pos + rem;
		}
	}
	return 0;
}

static int
AdvanceChunks(string buf, int pos, int len, ChunkInfo info) {
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
					retErr("Chunk_Error, Chunk_Skip");
					info->state = Chunk_Error;
					return pos;
				}
				info->state = Chunk_NeedNL1;
				pos++;
				break;
			}
			case Chunk_NeedNL1: {
				if (c != '\n') {
					retErr("Chunk_Error, Chunk_NeedNL1");
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
								info->state = Chunk_Error;
								retErr("Chunk_Error, Chunk_Accum");
								return pos;
							}
							info->state = Chunk_NeedNL2;
							pos++;
							break;
						}
						uint32_t next = info->accum * 16 + h;
						if ((next >> 4) != info->accum) {
							// overflow, which is bad news
							info->state = Chunk_Error;
							retErr("Chunk_Error, Chunk_Accum");
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
					retErr("Chunk_Error, Chunk_NeedNL2");
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

static struct ccn_charbuf *
NewSegBlob(seg_t seg) {
	// make a new charbuf that is a blob for a seg number
	// (especially as needed for the final segment)
	// see doc/technical/NameConventions.html
	// see cc_name_util.c:ccn_name_append_numeric for an alternate impl
	unsigned char junk[32];
	unsigned char *jp = junk+sizeof(junk);
	int nj = 0;
	if (seg < 0) seg = 0;
	for (;;) {
		jp--;
		nj++;
		*jp = seg % 256;
		seg = seg >> 8;
		if (seg == 0) break;
	}
	struct ccn_charbuf *blob = ccn_charbuf_create();
	ccn_charbuf_append_tt(blob, nj, CCN_BLOB);
    ccn_charbuf_append(blob, jp, nj);
	return blob;
}

static int
HaveSegment(FileNode fn, seg_t seg) {
	if (fn == NULL) return 0;
	seg_t nSegs = fn->nSegs;
	seg_t safeSeg = nSegs;
	if (fn->final == 0) safeSeg--;
	if (seg < 0 || seg >= safeSeg)
		// not in the current valid range
		return 0;
	return 1;
}

static int
AssertFinalSize(FileNode fn, off_t fileSize) {
    struct ccn_charbuf *templ;
    struct ccn_charbuf *finalBlock;
    int res;
	// only to be used when the file size was not previously known
	// once the final size is set it cannot be changed
	if (fn->final == 1) return 0;
	MainData md = fn->md;
    // new file, so accum the new bytes
    fn->final = 1;
    fn->fileSize = fileSize;
    md->stats.fileBytes = md->stats.fileBytes + fileSize;
	seg_t nSegs = (fileSize + CCN_CHUNK_SIZE - 1) / CCN_CHUNK_SIZE;
	fn->nSegs = nSegs;
	fn->lastUsed = GetCurrentTime();
    templ = ccn_charbuf_create();
    res = ccnb_element_begin(templ, CCN_DTAG_SignedInfo);
    finalBlock = NewSegBlob(nSegs - 1);
    ccnb_element_begin(templ, CCN_DTAG_FinalBlockID);
    res |= ccn_charbuf_append_charbuf(templ, finalBlock);
    res |= ccnb_element_end(templ);
    res |= ccnb_element_end(templ);    /* </SignedInfo> */
    fn->signing_params.sp_flags |= CCN_SP_TEMPL_FINAL_BLOCK_ID;
    fn->signing_params.template_ccnb = templ;
	if (md->debug) {
		fprintf(stdout, "-- AssertFinalSize, %s, fileSize %jd, final %jd\n",
				fn->id, (intmax_t) fileSize, (intmax_t) nSegs-1);
        flushLog();
	};
    ccn_charbuf_destroy(&finalBlock);
	return (res);
}

static int
MakePath(char *dir, int pos) {
	int count = 0;
	for (;;) {
		char c = dir[pos];
		if ((c == 0 || c == '/') && pos > 0) {
			dir[pos] = 0;
			int res = mkdir(dir, S_IRWXU | S_IRWXG);
			dir[pos] = c;
			if (res < 0) {
				// maybe this already exists
				int e = errno;
				if (e != EEXIST) {
					// does not exist, and can't create it
					char msg[MaxFileName];
					snprintf(msg, sizeof(msg),
							 "MakePath failed for %s; %s",
							 dir, strerror(errno));
					return retErr(msg);
				}
			}
		}
		if (c == 0) break;
		pos++;
	}
	return count;
}

static int
UnMapBig(FileNode fn) {
	MainData md = fn->md;
	int len = fn->mapLen;
	void *addr = fn->mapAddr;
	if (len > 0 && addr != NULL && addr != MAP_FAILED) {
		// get rid of the big map address
		int res = munmap(fn->mapAddr, len);
		if (res < 0) retErr("UnMapBig - munmap");
		md->mapped = md->mapped - len;
		fn->mapAddr = NULL;
		fn->mapLen = 0;
		return res;
	}
	return 0;
}

static void *
MapBig(FileNode fn, seg_t seg) {
	MainData md = fn->md;
	seg_t nSegs = fn->nSegs;
	if (seg < 0 || seg >= nSegs) return NULL;
	off_t off = seg * CCN_CHUNK_SIZE;
	off_t lim = off + CCN_CHUNK_SIZE;
	off_t mapLim = fn->mapOff + fn->mapLen;
	off_t maxOff = nSegs * CCN_CHUNK_SIZE;
	if (off >= maxOff || lim > maxOff) return NULL;
	if (fn->mapLen > 0 && off >= fn->mapOff && off <= mapLim) {
		// we have a valid mapping, maybe including this segment
		if (lim <= mapLim) {
			// current mapped region contains desired segment
			char *s = (char *) fn->mapAddr;
			return (void *) (s + (off - fn->mapOff));
		}
		// current mapped region only contains part of desired segment
		// so don't claim that it can hold the whole thing
	}
	// we don't have the right stuff, so get rid of it
	int umRes = UnMapBig(fn);
	if (umRes < 0) {
		string sysErr = strerror(errno);
		char msg[256];
		int pos = snprintf(msg, sizeof(msg), "UnMapBig, %s", sysErr);
		msg[pos] = 0;
		retErr(msg);
	}
	
	seg_t mseg = seg;
	seg_t dseg = seg - fn->maxSegPut;
	if (dseg > 1 && dseg <= 8) {
		// adjust to fit the gap
		mseg = fn->maxSegPut + 1;
		off = mseg * CCN_CHUNK_SIZE;
		lim = off + CCN_CHUNK_SIZE;
		dseg = seg - mseg;
	}
	
	off_t d = fn->fileSize - off;
	if (d < CCN_CHUNK_SIZE && fn->final == 0)
		// we only have a partial, and the file is not final
		return 0;
	if (d > CCN_MAP_SIZE) d = CCN_MAP_SIZE;
	void *addr = mmap(NULL,
					  d,
					  PROT_READ,
#ifdef _DARWIN_C_SOURCE
					  MAP_FILE | MAP_PRIVATE,
#else
					  MAP_PRIVATE,
#endif
					  fn->fd,
					  off);
	if (addr != NULL && addr != MAP_FAILED) {
		// it's mapped
		fn->mapOff = off;
		fn->mapLen = d;
		fn->mapAddr = addr;
		md->mapped = md->mapped + d;
		fprintf(stdout, "-- MapBig, seg %jd for %d bytes (mapped %jd)\n",
				seg, (int) d, (intmax_t) md->mapped);
		fflush(stdout);
		if (mseg != seg)
			// the returned address should be for the initially requested seg
			addr = (void *) ((char *) addr + dseg*CCN_CHUNK_SIZE);
		return addr;
	}
	fprintf(stdout, "** %s: MapBig, %jd\n", strerror(errno), seg);
	fflush(stdout);
	// we failed!
	return NULL;
}

static FileNode
OpenFileNode(MainData md, char *root, char *dir, char *shortName,
			 int create, int fresh) {
	// first, try to find it in the main data
	// this will also find any files we are building
	FileNode each = md->files;
	while (each != NULL) {
		if (strcmp((char *) shortName, (char *) each->shortName) == 0
			&& HostMatch(dir, each->dir))
			// found one!
			return each;
		each=each->next;
	}
	// now, try to open the file in the file system
	char fileName[MaxFileName];
	char dirName[MaxFileName];
	if (dir != NULL) {
		snprintf(dirName, sizeof(dirName), "%s%s", root, dir);
	} else {
		snprintf(dirName, sizeof(dirName), "%s", root);
	}
	snprintf(fileName, sizeof(fileName),
			 "%s/%s",
			 dirName, shortName);
	off_t fileSize = 0;
	int fd = open(fileName, O_RDONLY);
	struct stat ss;
	if (fd >= 0) {
		// file exists
		fstat(fd, &ss);
		fileSize = ss.st_size;
		if (fileSize == 0) {
			// pretend that zero-length files don't exist
			close(fd);
			fd = -1;
		}
	}
	if (fd < 0 && create) {
		// no such file, so make one
		fd = open(fileName, O_RDWR | O_CREAT, S_IRUSR | S_IWUSR | S_IRGRP | S_IWGRP);
		if (fd < 0) {
			// could not create, maybe because we need a mkdir?
			int nd = MakePath(dirName, strlen(root));
			if (nd < 0) {
				string sysErr = strerror(errno);
				fprintf(stdout, "** %s - Could not create dir %s\n", sysErr, dirName);
				flushLog();
				return NULL;
			}
			fd = open(fileName, O_RDWR | O_CREAT, S_IRUSR | S_IWUSR | S_IRGRP | S_IWGRP);
			if (fd < 0) {
				string sysErr = strerror(errno);
				fprintf(stdout, "** %s - Could not create %s\n", sysErr, fileName);
				flushLog();
				return NULL;
			}
		}
	} else {
		// an existing file, so don't create a new one
		create = 0;
	}
	if (fd >= 0) {
        struct ccn_signing_params sp = CCN_SIGNING_PARAMS_INIT;
		// looks ok from here, so return the new node
		fstat(fd, &ss);
		FileNode fn = ProxyUtil_StructAlloc(1, FileNodeStruct);
		fn->md = md;
		fn->next = md->files;
		md->files = fn;
		md->nFiles++;
		md->stats.filesCreated++;
		fn->fd = fd;
		fn->maxSegPut = -1;
		fn->fresh = fresh;
		fn->fileName = Concat(fileName, "");
		fn->fileSize = fileSize;
		fn->modTime.tv_sec = ss.st_mtime;
		fn->modTime.tv_nsec = 0;
        fn->signing_params = sp;
        fn->signing_params.freshness = fresh;
		fn->lastUsed = GetCurrentTime();
		fn->firstUsed = fn->lastUsed;
		fn->root = Concat(root, "");
		if (dir != NULL) fn->dir = Concat(dir, "");
		fn->shortName = Concat(shortName, "");
		fn->unPercName = NewUnPercName(shortName);
		fn->id = MakeId(dir, fn->unPercName);
		fn->create = create;
		if (md->debug) {
			double dt = DeltaTime(md->startTime, GetCurrentTime());
            seg_t nSegs = (fileSize + CCN_CHUNK_SIZE - 1) / CCN_CHUNK_SIZE;
			if (create)
				fprintf(stdout, "@%4.3f, CreateFile %s\n",
						dt, fn->id);
			else if (nSegs > 1)
				fprintf(stdout, "@%4.3f, OpenFile %s, %jd bytes, %jd segs\n",
						dt, fn->id,
						(seg_t) fileSize, nSegs);
			else
				fprintf(stdout, "@%4.3f, OpenFile %s, %jd bytes\n",
						dt, fn->id,
						(seg_t) fileSize);
			flushLog();
		}
		if (fileSize > 0)
			// should only be true if whole file is here
			// TBD: what to do about damaged files?
			AssertFinalSize(fn, fileSize);
		return fn;
	}
	// no luck
	return NULL;
}

static FileNode
CloseFileNode(FileNode fn) {
	MainData md = fn->md;
	if (md != NULL && md->nFiles > 0) {
		FileNode lag = NULL;
		FileNode each = md->files;
		for (; each != NULL; each=each->next) {
			if (each == fn) {
				// found it
				if (lag == NULL) md->files = fn->next;
				else lag->next = fn->next;
				char *fileName = fn->fileName;
				UnMapBig(fn);
				md->nFiles--;
				if (md->debug) {
					double dt = DeltaTime(md->startTime, GetCurrentTime());
					fprintf(stdout,
							"@%4.3f, CloseFile %s, mapped %jd, files %d\n",
							dt,
							fn->id,
							(intmax_t) md->mapped,
							md->nFiles);
					fflush(stdout);
				}
				fn->root = Freestr(fn->root);
				fn->dir = Freestr(fn->dir);
				fn->shortName = Freestr(fn->shortName);
				fn->unPercName = Freestr(fn->unPercName);
				fn->id = Freestr(fn->id);
				if (fn->nTemp > 0) {
					// we have some temp bufs to handle
					int i = 0;
					for (; i < fn->nTemp; i++) {
						void *buf = fn->tempBufs[i];
						if (buf != NULL) free(buf);
					}
					free(fn->tempBufs);
					free(fn->tempSegs);
					free(fn->tempLengths);
				}
                ccn_charbuf_destroy(&fn->signing_params.template_ccnb);
				struct timeval tv[2];
				tv[0].tv_sec = fn->modTime.tv_sec;
				tv[0].tv_usec = fn->modTime.tv_nsec / 1000;
				tv[1] = tv[0];
				
				close(fn->fd);
				fn->md = NULL;
				
				// just after closing, before leaving, force the access times
				utimes(fileName, tv);
				fileName = Freestr(fileName);
				free(fn);
				return NULL;
			}
			lag = each;
		}
	}
	return NULL;
}

static void *
MapSeg(FileNode fn, seg_t seg) {
	MainData md = fn->md;
	seg_t nSegs = fn->nSegs;
	if (seg < 0 || seg >= nSegs) return NULL;
	if (CCN_CHUNK_SIZE == CCN_MAP_SIZE) {
		off_t off = seg * CCN_CHUNK_SIZE;
		
		void *addr = mmap(NULL,
						  CCN_CHUNK_SIZE,
						  PROT_READ,
#ifdef _DARWIN_C_SOURCE
						  MAP_FILE | MAP_PRIVATE,
#else
						  MAP_PRIVATE,
#endif
						  fn->fd,
						  off);
		if (addr != NULL && addr != MAP_FAILED) {
			// it's mapped
			int len = CCN_CHUNK_SIZE;
			fn->mapLen = len;
			md->mapped = md->mapped + len;
			return addr;
		}
		fprintf(stdout, "** %s: MapSeg\n", strerror(errno));
		fflush(stdout);
		// could not get it
		return NULL;
	} else {
		return MapBig(fn, seg);
	}
}

static int
UnMapSeg(FileNode fn) {
	if (CCN_CHUNK_SIZE == CCN_MAP_SIZE) {
		void *addr = fn->mapAddr;
		int len = fn->mapLen;
		if (len > 0 && addr != NULL && addr != MAP_FAILED) {
			MainData md = fn->md;
			int res = munmap(addr, len);
			if (res == 0) retErr("UnMapSeg - munmap");
			md->mapped = md->mapped - len;
			fn->mapAddr = NULL;
			fn->mapLen = 0;
			return res;
		}
	}
	return 0;
}

static MainData
NewMainData(struct ccn *h) {
	MainData md = ProxyUtil_StructAlloc(1, MainDataStruct);
	md->startTime = GetCurrentTime();
	md->ccn = h;
	md->changes = 1;
	return md;
}

static MainData
CloseMainData(MainData md) {
	// shut down any open files
	while (md->files != NULL) {
		CloseFileNode(md->files);
	}
	// finally, get rid of self
	free(md);
	return NULL;
}


////////////////////////////////////////////////////////////////
// Keystore support, adapted from ccnd_internal_client.c
////////////////////////////////////////////////////////////////


#ifndef CCN_PATH_VAR_TMP
#define CCN_PATH_VAR_TMP "/var/tmp"
#endif

/*
 * This is used to shroud the contents of the keystore, which mainly serves
 * to add integrity checking and defense against accidental misuse.
 * The file permissions serve for restricting access to the private keys.
 */
#ifndef CCNK_KEYSTORE_PASS
#define CCNK_KEYSTORE_PASS "\010\103\043\375\327\237\051\152\155\347"
#endif

static int
init_internal_keystore(MainData md) {
    struct ccn_charbuf *temp = NULL;
    char *culprit = NULL;
    struct stat statbuf;
    char *dir = NULL;
    int res = -1;
    size_t save;
    char *keystore_path = NULL;
    
    temp = ccn_charbuf_create();
    dir = getenv("CCNK_KEYSTORE_DIRECTORY");
    if (dir != NULL && dir[0] == '/')
        ccn_charbuf_putf(temp, "%s/", dir);
    else
        ccn_charbuf_putf(temp, CCN_PATH_VAR_TMP "/.ccnx-user%d/", (int)geteuid());
    res = stat(ccn_charbuf_as_string(temp), &statbuf);
    if (res == -1) {
        if (errno == ENOENT)
            res = mkdir(ccn_charbuf_as_string(temp), 0700);
        if (res != 0) {
            culprit = ccn_charbuf_as_string(temp);
            goto Finish;
        }
    }
    save = temp->length;
	char *kPrefix = "ccnk";
    ccn_charbuf_putf(temp, ".%s_keystore", kPrefix);
    keystore_path = Concat(ccn_charbuf_as_string(temp), "");
    res = ccn_load_default_key(md->ccn, keystore_path, CCNK_KEYSTORE_PASS);
    if (res != 0) {
        culprit = keystore_path;    
        goto Finish;
    }
Finish:
    if (culprit != NULL) {
        fprintf(stdout,
				"** %s: %s\n",
				culprit, strerror(errno));
		flushLog();
        culprit = NULL;
    }
    ccn_charbuf_destroy(&temp);
    if (keystore_path != NULL)
        free(keystore_path);
    return res;
}

////////////////////////////////////////////////////////////////
// Segment support
////////////////////////////////////////////////////////////////

static int
SetNameCCN(struct ccn_charbuf *cb, char *ccnRoot, char *dir, char *name) {
	char temp[MaxFileName];
	snprintf(temp, sizeof(temp), "ccnx:/%s/", ccnRoot);
	int res = ccn_name_from_uri(cb, temp);
	if (dir != NULL) {
		// use our HTTP convention
		res = res | ccn_name_append_str(cb, (const char *) "http");
		res = res | ccn_name_append_str(cb, (const char *) dir);
	}
	// name
	res = res | ccn_name_append_str(cb, (const char *) name);
	if (res < 0) return retErr("SetNameCCN bad name");
	return 0;
}

static int
PutSegment(FileNode fn, char *ccnRoot, seg_t seg) {
	// PutSegment takes the indicated segment from the file and stores it
	// as a CCN segment
	// returns -1 on failure, 0 on success
	MainData md = fn->md;
	char *dir = fn->dir;
	int ret = 0;
	if (md->debug) {
		if (fn->nSegsPut > 0) {
			uint64_t now = GetCurrentTime();
			double rate = ((fn->nSegsPut)*CCN_CHUNK_SIZE
						   / (1.0e6*DeltaTime(fn->firstUsed, now)));
			fprintf(stdout, "-- PutSegment, %s, seg %jd, %4.3f MB/s\n",
					fn->id, seg, rate);
		} else {
			fprintf(stdout, "-- PutSegment, %s, seg %jd\n",
					fn->id, seg);
		}
		flushLog();
	}
	if (seg < 0 || seg >= fn->nSegs) {
		fprintf(stdout, "** PutSegment, %s, invalid seg %jd\n",
				fn->id, seg);
		
		flushLog();
		return -1;
	}
	int segLen = CCN_CHUNK_SIZE;
	if (seg+1 == fn->nSegs) {
		// last part may not be a complete segment
		int mod = fn->fileSize % CCN_CHUNK_SIZE;
		if (mod > 0) segLen = mod;
	}
	int usePRead = 0;
	void *addr = NULL;
	
	if (fn->final == 0) {
		// the last write might not have finished
		seg_t safeSeg = fn->nSegs - 2;
		if (seg > safeSeg) usePRead = 1;
	}
	if (usePRead) {
		// this is the slower, but possibly safer, way to read near
		// the end of the file as currently written
		addr = ProxyUtil_Alloc(CCN_CHUNK_SIZE, char);
		ssize_t nr = pread(fn->fd, addr, segLen, seg*CCN_CHUNK_SIZE);
		if (md->debug) {
			fprintf(stdout, "-- PutSegment, pread, seg %jd, nr %zd\n",
					seg, nr);
			flushLog();
		}
		if (nr < segLen) {
			fprintf(stdout, "** can't read file %s, seg %jd\n",
					fn->id, seg);
			flushLog();
			free(addr);
			return -1;
		}
	} else {
		addr = MapSeg(fn, seg);
		if (addr == NULL) {
			fprintf(stdout, "** can't map file %s, seg %jd\n",
					fn->id, seg);
			flushLog();
			return -1;
		}
	}
	fn->lastUsed = GetCurrentTime();
	
	// serve up the segment
    struct ccn_charbuf *cb = ccn_charbuf_create();
	int res = SetNameCCN(cb, ccnRoot, dir, fn->unPercName);
	if (res < 0) {
		// don't let this get farther
		ccn_charbuf_destroy(&cb);
		return retErr("bad name?");
	}
	
	// see ccn_versioning.c: ccn_create_version
	ccn_create_version(md->ccn, cb, 0,
					   fn->modTime.tv_sec, fn->modTime.tv_nsec);
	ccn_name_append_numeric(cb, CCN_MARKER_SEQNUM, seg);
	
    struct ccn_charbuf *temp = ccn_charbuf_create();
    ret = ccn_sign_content(md->ccn, temp, cb, &fn->signing_params, addr, segLen);
	if (ret != 0) {
		fprintf(stdout, "** ccn_sign_content failed (res == %d)\n",
				ret);
		ret = -1;
	} else {
		// OK, try to put the data
		ret = ccn_put(md->ccn, temp->buf, temp->length);
		if (ret < 0) {
			fprintf(stdout,
					"** ccn_put failed (%s, %jd, res == %d)\n",
					fn->id, seg, ret);
			ret = -1;
		} else {
			fn->nSegsPut++;
			if (seg > fn->maxSegPut) fn->maxSegPut = seg;
		}
	}
    // Only the first segment gets the key locator
    fn->signing_params.sp_flags |= CCN_SP_OMIT_KEY_LOCATOR;
	// cleanup
	if (usePRead) {
		free(addr);
	} else {
		UnMapSeg(fn);
	}
	flushLog();
	ccn_charbuf_destroy(&cb);
	ccn_charbuf_destroy(&temp);
	md->stats.segmentsPut++;
	md->stats.bytesPut = md->stats.bytesPut + segLen;
	md->changes++;
	return ret;
}

static seg_t
GetSegmentNumber(struct ccn_upcall_info *info) {
	// gets the current segment number for the info
	// returns -1 if not known
	if (info == NULL) return -1;
	const unsigned char *ccnb = info->content_ccnb;
	struct ccn_indexbuf *cc = info->content_comps;
	if (cc == NULL || ccnb == NULL) {
		// go back to the interest
		cc = info->interest_comps;
		ccnb = info->interest_ccnb;
		if (cc == NULL || ccnb == NULL) return -1;
	}
	int ns = cc->n;
	if (ns > 2) {
		// assume that the segment number is the last component
		int start = cc->buf[ns - 2];
		int stop = cc->buf[ns - 1];
		if (start < stop) {
			size_t len = 0;
			const unsigned char *data = NULL;
			ccn_ref_tagged_BLOB(CCN_DTAG_Component, ccnb, start, stop, &data, &len);
			if (len > 0 && data != NULL) {
				// parse big-endian encoded number
				// TBD: where is this in the library?
				if (data[0] != CCN_MARKER_SEQNUM) return -1;
				seg_t n = 0;
				size_t i = 1;
				for (; i < len; i++) {
					n = n * 256 + data[i];
				}
				return n;
			}
		}
	}
	return -1;
}

// GetShortName returns the short file name given by the upcall info.  This name
// does not contain the version information or the segment number, and it is
// the translated to the short name in the cache file system, which includes
// translating any weird characters into using percent hex encoding (like %00).

// The following conventions are used for components (numbering from 1):
// C1: protocol marker (ccnPrefix)
// C2: kind (most likely "http")
// C3: host (a string)
// C4: short name (any characters at all)

static char *
GetShortName(MainData md,
			 struct ccn_upcall_info *info,
			 char *ccnPrefix) {
	// extracts the short name from the interest
	// as a side effect, stores the host, kind, and port to md
	// ignores the version ID and segment number
	// TBD: handle the version and segment#
	if (info == NULL) return NULL;
	const unsigned char *ccnb = info->content_ccnb;
	struct ccn_indexbuf *cc = info->content_comps;
	if (cc == NULL || ccnb == NULL) {
		// go back to the interest
		cc = info->interest_comps;
		ccnb = info->interest_ccnb;
		if (cc == NULL || ccnb == NULL) return NULL;
	}
	char temp[1024+4]; // TBD: make this limit more principled
	static int maxTemp = sizeof(temp)-4;
	int ns = cc->n;
	int start = 0;
	size_t len = 0;
	md->recentHost = Freestr(md->recentHost);
	md->recentPort = 0;
	
	int compCase = 0;
	
	int i = 0;
	for (; i < ns; i++) {
		int pos = 0;
		int stop = cc->buf[i];
		const unsigned char *data = NULL;
		len = 0;
		ccn_ref_tagged_BLOB(CCN_DTAG_Component, ccnb, start, stop, &data, &len);
		size_t j = 0;
		for (; j < len; j++) {
			char c = (char) data[j];
			char cc = isNiceChar(c);
			if (cc == 0) {
				// shift to percent encoding
				if (pos+2 >= maxTemp) break;
				temp[pos] = '%';
				pos++;
				temp[pos] = hex[(c / 16) & 15];
				pos++;
				temp[pos] = hex[c & 15];
				pos++;
			} else {
				// nice char for a name
				if (pos >= maxTemp) break;
				temp[pos] = c;
				pos++;
			}
		}
		temp[pos] = 0;
		if (len > 0) {
			compCase++;
			switch (compCase) {
				case 1: {
					if (strcmp(ccnPrefix, temp) != 0)
						// prefix mismatch
						return NULL;
					break;
				}
				case 2: {
					// kind
					if (strcasecmp("http", temp) != 0)
						// kind mismatch, assume fileOnly
						return Concat((char *) temp, "");
					break;
				}
				case 3: {
					// host
					// TBD: sanity check?
					md->recentHost = Concat((char *) temp, "");
					break;
				}
				case 4: {
					// short name
					return Concat((char *) temp, "");
				}
			}
		}
		
		start = stop;
	}
	return NULL;
}

static HttpInfo
ParseReplyHeader(NetRequest nr) {
	MainData md = nr->md;
	HttpInfo h = nr->httpInfo;
	if (h == NULL) h = ProxyUtil_StructAlloc(1, HttpInfoStruct);
	h->nr = nr;
	nr->httpInfo = h;
	char *buf = nr->buf;
	int pos = 0;
	int lim = nr->iov.iov_len;
	int lagLen = NextLine(buf, pos, lim);
	if (lagLen > 9 && HasPrefix(buf, lagLen, "HTTP/1.")) {
		h->version = 1;
		h->subversion = EvalUint(buf, 7);
		h->code = EvalUint(buf, 9);
	}
	if (h->version != 1
		|| h->subversion < 0 || h->subversion > 1
		|| h->code != 200) {
		// NOT a good sign, we will not get the contents
		h->error = 1;
		nr->endSeen = 1;
		return h;
	}
	if (h->subversion == 0) h->forceClose = 1;
	
	char *contentKey = "Content-Length: ";
	int contentKeylen = strlen(contentKey);
	
	char *tfrKey = "Transfer-Encoding:";
	char *connKey = "Connection:";
	
	h->contentLen = -1;
	h->totalLen = -1;
	
	int line = 1;
	for (;;) {
		int npos = NextLine(buf, pos, lim);
		char *lineStr = buf+pos;
		int lineLen = npos-pos;
		pos = npos;
		if (lineLen <= 2) {
			// blank line, if the previous one was blank then header is done
			h->headerLen = pos;
			char save = buf[pos];
			buf[pos] = 0;
			if (md->debug)
				fprintf(stdout, "-- ParseReplyHeader, headerLen %d\n%s", pos, buf);
			buf[pos] = save;
			break;
		} else if (HasPrefix(lineStr, lineLen, contentKey)) {
			// the content length is known
			h->contentLen = EvalUint(lineStr, contentKeylen);
			if (md->debug)
				fprintf(stdout, "-- ParseReplyHeader, contentLen %d\n",
						(int) h->contentLen);
		} else if (HasPrefix2(lineStr, lineLen, tfrKey, "chunked")) {
			// we appear to be chunking
			h->chunked = 1;
			if (md->debug)
				fprintf(stdout, "-- ParseReplyHeader, chunked\n");
		} else if (HasPrefix2(lineStr, lineLen, connKey, "close")) {
			// we appear to be chunking
			if (md->debug)
				h->forceClose = 1;
			fprintf(stdout, "-- ParseReplyHeader, forceClose\n");
		} else {
			// TBD: more cases
		}
		lagLen = lineLen;
		line++;
	}
	if (h->contentLen >= 0) h->totalLen = h->contentLen+h->headerLen;
	if (h->chunked) {
		ChunkInfo info = &h->chunkInfo;
		info->state = Chunk_Accum;
		info->chunkRem = h->headerLen;
	}
	if (md->debug) flushLog();
	return h;
}

static void
InitBuffer(NetRequest nr) {
	// alloc the buffer and init the msg
	int sz = 8800;
	nr->buf = ProxyUtil_Alloc(sz+4, char);
	nr->bufSize = sz;
	nr->iov.iov_base = nr->buf;
	nr->iov.iov_len = sz;
	nr->msg.msg_name = NULL;
	nr->msg.msg_namelen = 0;
	nr->msg.msg_iovlen = 1;
	nr->msg.msg_iov = &nr->iov;
}

static int
StartHttpStream(NetRequest nr) {
	// we call this when we think that we can open a connection
	// and send the initial request for the file
	MainData md = nr->md;
	SockBase sockBase = md->sockBase;
	if (nr->buf == NULL) InitBuffer(nr);
	SockEntry se = nr->se;
	char *host = (char *) nr->host;
	char *kind = (char *) nr->kind;
	if (se == NULL) se = SH_NewSockEntryForName(sockBase,
												host,
												kind,
												nr->port);
	if (se == NULL)
		return retErr("StartHttpStream no connect");
	int needExtras = 0;
	int pos = 0;
	int lim = CCN_CHUNK_SIZE;
	char *buf = nr->buf;
	if (pos < lim) {
		char *upn = nr->unPercName;
		if (upn[0] == '/') upn++;
		pos = pos + snprintf(buf+pos, lim-pos,
							 "GET /%s HTTP/1.1\r\n",
							 upn);
	}
	if (pos < lim)
		pos = pos + snprintf(buf+pos, lim-pos, "Host: %s\r\n", host);
	if (pos < lim)
		pos = pos + snprintf(buf+pos, lim-pos,
							 "User-Agent: CCNx-Bridge/0.1\r\n");
	if (pos < lim)
		pos = pos + snprintf(buf+pos, lim-pos,
							 "Keep-Alive: %d\r\n",
							 KeepAliveDefault);
	if (needExtras) {
		if (pos < lim)
			pos = pos + snprintf(buf+pos, lim-pos,
								 "Accept: */*\r\n");
		if (pos < lim)
			pos = pos + snprintf(buf+pos, lim-pos,
								 "Accept-Language: en-us,en;q=0.5\r\n");
		if (pos < lim)
			pos = pos + snprintf(buf+pos, lim-pos,
								 "Accept-Encoding: gzip,deflate\r\n");
		if (pos < lim)
			pos = pos + snprintf(buf+pos, lim-pos,
								 "Accept-Charset: ISO-8859-1,utf-8;q=0.7,*;q=0.7\r\n");
		if (pos < lim)
			pos = pos + snprintf(buf+pos, lim-pos,
								 "Referer: http://bogus.com/default.html\r\n");
	}
	if (pos < lim)
		pos = pos + snprintf(buf+pos, lim-pos, "\r\n");
	if (pos > lim)
		return retErr("StartHttpStream overflow");
	// TBD: how to encode other stuff?
	if (md->debug) {
		buf[pos] = 0;
		fprintf(stdout, "-- %s", buf);
		flushLog();
	}
	
	nr->iov.iov_len = pos;
	ssize_t nSent = SH_RobustSendmsg(se, &nr->msg);
	if (nSent != pos) {
		if (nSent < 0) return retFail("StartHttpStream send problem");
		return retErr("StartHttpStream send problem");
	}
	
	return 0;
}

static int
StartNetRequest(NetRequest nr) { 
	MainData md = nr->md;
	// see if we have any open
	int openCount = SH_CountSockEntryOwned(md->sockBase,
										   nr->host,
										   nr->kind,
										   nr->port);
	if (openCount >= md->maxBusySameHost) {
		// the address is already busy
		if (md->debug) {
			fprintf(stdout, "-- StartNetRequest, %s, busy, oc %d\n",
					nr->id, openCount);
			flushLog();
		}
		return 0;
	}
	for (;;) {
		SockEntry se = SH_FindSockEntryForName(md->sockBase,
											   nr->host,
											   nr->kind,
											   nr->port,
											   0);
		if (se != NULL) {
			// we can reuse this connection!
			se->owned = 1;
			if (md->debug) {
				fprintf(stdout, "-- StartNetRequest, %s, reuse %d\n",
						nr->id, se->fd);
				flushLog();
			}
		} else {
			// sigh, need to make a new connection
			se = SH_NewSockEntryForName(md->sockBase,
										nr->host,
										nr->kind,
										nr->port);
			if (se == NULL) {
				// this happens if getaddrinfo failed
				// or if the name has no addresses
				return retFail("SH_NewSockEntryForName failed");
			}
			se->keepAlive = md->keepAliveDefault;
			if (md->debug) {
				fprintf(stdout, "-- StartNetRequest, %s, new %d\n",
						nr->id, se->fd);
				flushLog();
			}
		}
		se->owned = 1;
		md->changes++;
		nr->se = se;
		SH_SetNoDelay(se);
		InitBuffer(nr);
		int res = StartHttpStream(nr);
		if (res >= 0) break;
		// may have died on the first attempt, so kill the socket and try again
		if (md->debug) {
			fprintf(stdout, "-- StartNetRequest, %s, close %d and retry\n",
					nr->id, se->fd);
			flushLog();
		}
		nr->se = SH_Destroy(se);
	}
	return 0;
}

static NetRequest
NewNetRequest(InterestData iData, char *shortName) {
	MainData md = iData->md;
	int port = md->recentPort;
	char *host = (char *) md->recentHost;
	char *kind = "http";
	NetRequest nr = ProxyUtil_StructAlloc(1, NetRequestStruct);
	nr->startTime = GetCurrentTime();
	nr->md = md;
	nr->maxSegRequest = -1;
	nr->maxSegStored = -1;
	nr->ccnRoot = Concat(iData->ccnRoot, "");
	nr->fsRoot = Concat(iData->fsRoot, "");
	nr->host = Concat(host, "");
	nr->kind = Concat(kind, "");
	nr->port = port;
	nr->shortName = Concat(shortName, "");
	nr->unPercName = NewUnPercName(shortName);
	nr->id = MakeId(host, nr->unPercName);
	NetRequest each = md->requests;
	if (each == NULL) {
		// the very first one
		md->requests = nr;
	} else {
		// append to the end
		while (each->next != NULL) {
			if (strcmp(shortName, each->shortName) == 0
				&& HostMatch(host, each->host)) {
				// we should NOT be matching this!
				if (md->debug) {
					fprintf(stdout, "-- NewNetRequest BOGUS, %s\n", nr->id);
					flushLog();
				}
			}
			each = each->next;
		}
		each->next = nr;
	}
	if (md->debug) {
		double dt = DeltaTime(md->startTime, GetCurrentTime());
		fprintf(stdout, "@%4.3f, NewNetRequest, %s\n", dt, nr->id);
		flushLog();
	}
	StartNetRequest(nr);
	return nr;
}

static NetRequest
FindNetRequestByName(MainData md, char *host, char *shortName) {
	NetRequest nr = md->requests;
	if (host == NULL) host = "";
	while (nr != NULL) {
		if (strcmp(shortName, nr->shortName) == 0 && HostMatch(host, nr->host))
			return nr;
		nr = nr->next;
	}
	return NULL;
}

static void
UnlinkNetRequest(NetRequest nr) {
	MainData md = nr->md;
	NetRequest lag = md->requests;
	if (lag == NULL) {
		// should not happen
	} else if (nr == lag) {
		// remove head of list
		md->requests = nr->next;
	} else {
		for (;;) {
			NetRequest next = lag->next;
			if (next == NULL) break;
			if (next == nr) {
				lag->next = next->next;
				break;
			}
			lag = next;
		}
	}
}

static int
EndNetRequest(NetRequest nr) {
	// terminates the NetRequest, removing it from the requests list
	// the socket might stay alive for a while
	MainData md = nr->md;
	FileNode fn = nr->fn;
	
	if (md->debug) {
		double dt = DeltaTime(md->startTime, GetCurrentTime());
		fprintf(stdout, "@%4.3f, EndNetRequest, %s\n", dt, nr->id);
		flushLog();
	}
	if (fn != NULL && nr->error == 0) {
		// we have completion
		AssertFinalSize(nr->fn, fn->fileSize);
		// process any pending segments
		for (;;) {
			SegList pending = nr->segRequests;
			if (pending == NULL) break;
			if (pending->seg < fn->nSegs)
				PutSegment(fn, nr->ccnRoot, pending->seg);
			RemSegRequest(nr, pending->seg);
		}
		nr->fn = NULL;
	}
	
	UnlinkNetRequest(nr);
	
	SockEntry se = nr->se;
	if (se != NULL) {
		// get rid of the connection
		// TBD: transfer a keep-alive connection to the next NetRequest
		string msg = "recycle";
		int fd = se->fd;
		nr->se = NULL;
		se->owned = 0;
		HttpInfo h = nr->httpInfo;
		if (h == NULL
			|| h->error || h->forceClose
			|| SH_TimeAlive(se) > se->keepAlive) {
			// kill it, we can't keep going with this connection
			se = SH_Destroy(se);
			msg = "close";
		}
		if (md->debug) {
			fprintf(stdout, "-- EndNetRequest, %s %d\n", msg, fd);
			flushLog();
		}
	}
	
	nr->shortName = Freestr(nr->shortName);
	nr->unPercName = Freestr(nr->unPercName);
	nr->id = Freestr(nr->id);
	nr->ccnRoot = Freestr(nr->ccnRoot);
	nr->fsRoot = Freestr(nr->fsRoot);
	nr->host = Freestr(nr->host);
	nr->kind = Freestr(nr->kind);
	if (nr->buf != NULL) free(nr->buf);
	if (nr->httpInfo != NULL) free(nr->httpInfo);
	free(nr);
	md->changes++;
	return 0;
}

static int
ReadFromHttp(NetRequest nr) {
	// ReadFromHttp reads the next buffer from the HTTP stream associated
	// with the file, and outputs CCN segments as needed
	// returns 0 on success, -1 on failure
	SockEntry se = nr->se;
	if (se == NULL) return -1;
	nr->iov.iov_len = nr->bufSize;
	ssize_t n = SH_RobustRecvmsg(se, &nr->msg);
	FileNode fn = nr->fn;
	MainData md = nr->md;
	if (md->debug) {
		fprintf(stdout, "-- ReadFromHttp, %s, %zd bytes\n", nr->id, n);
		flushLog();
	}
	if (n <= 0) {
		// there is no more, assume EOF
		// TBD: test for read error
		EndNetRequest(nr);
		return 0;
	}
	HttpInfo h = nr->httpInfo;
	if (h == NULL) {
		h = ParseReplyHeader(nr);
		if (md->debug) {
			fprintf(stdout,
					"-- ReadFromHttp, %s, headerLen %zd", nr->id, h->headerLen);
			if (fn != NULL)
				fprintf(stdout,", fileSize %jd", (intmax_t) fn->fileSize);
			fprintf(stdout, "\n");
			flushLog();
		}
		if (h->error) {
			char msg[256+4];
			int lim = 256;
			int pos = 0;
			pos = pos + snprintf(msg+pos, lim-pos,
								 "ReadFromHttp HTTP error, code %d",
								 h->code);
			pos = pos + snprintf(msg+pos, lim-pos, ", %s", nr->id);
			msg[pos] = 0;
			retErr(msg);
			EndNetRequest(nr);
			return -1;
		}
	}
	if (fn == NULL) {
		// OK, now we need a new file
		fn = OpenFileNode(nr->md, nr->fsRoot, nr->host, nr->shortName,
						  1, DefaultFreshness);
		nr->fn = fn;
		if (fn == NULL) {
			// this is not good
			EndNetRequest(nr);
			return retErr("ReadFromHttp could not create file");
		}
	}
	if (fn->fd >= 0) {
		// first, fill in any temp segments
		FillTempSegments(fn, nr->buf, n);
		
		// now, append the buffer to the file
		ssize_t nWrite = write(fn->fd, nr->buf, n);
		if (md->debug) {
			fprintf(stdout, "-- ReadFromHttp, %s, wrote %zd bytes",
					nr->id, nWrite);
			if (n != nWrite)
				fprintf(stdout, "wanted to write %zd bytes", n);
			fprintf(stdout, "\n");
			flushLog();
		}
		
		fn->fileSize = fn->fileSize + nWrite;
		fn->nSegs = (fn->fileSize + CCN_CHUNK_SIZE - 1) / CCN_CHUNK_SIZE;
		
		
		if (nWrite < n) {
			// dreck, a write error!
			EndNetRequest(nr);
			return retErr("ReadFromHttp write error");
		}
	}
	if (nr->httpInfo->chunked) {
		ChunkInfo info = &nr->httpInfo->chunkInfo;
		AdvanceChunks((string) nr->buf,
					  0,
					  n,
					  info);
		switch (info->state) {
			case Chunk_Done: {
				if (md->debug) {
					printf("-- chunking done\n");
					flushLog();
				}
				nr->endSeen = 1;
				break;
			}
			case Chunk_Error: {
				if (md->debug) {
					printf("-- chunking error, assume last packet\n");
					flushLog();
				}
				nr->endSeen = 1;
				nr->httpInfo->forceClose = 1;
				break;
			}
			default: {
				if (md->debug) {
					printf("-- chunking in progress, chunkRem %u\n",
						   info->chunkRem);
					flushLog();
				}
			}
		}
	} else if (h != NULL && h->totalLen >= 0 && h->totalLen <= fn->fileSize)
		// we had an explicit contentLen, and it's done
		nr->endSeen = 1;
	
	// process any pending segments for the stable part of the file
	for (;;) {
		SegList pending = nr->segRequests;
		if (pending == NULL) break;
		seg_t seg = pending->seg;
		if (HaveSegment(fn, seg)) {
			PutSegment(fn, nr->ccnRoot, seg);
			RemSegRequest(nr, seg);
		} else break;
	}
	
	if (nr->endSeen) {
		// we are done now
		EndNetRequest(nr);
	}
	md->changes++;
	return 0;
}

// NoteInterest handles interests matching the global filter
// The main action to perform is to put a content object for a requested
// segment back to the ccn handle.  There are cases where there is no segment
// number, as when we are trying to resolve a version, so we respond with
// segment 0 (this may be somewhat inefficient if segment 0 is not really
// needed by the client, but we don't have a good way to avoid this yet).
//
// TBD: add code for enumeration?
//
enum ccn_upcall_res
NoteInterest(struct ccn_closure *selfp,
			 enum ccn_upcall_kind kind,
			 struct ccn_upcall_info *info) {
    InterestData iData = selfp->data;
	
    if (kind == CCN_UPCALL_FINAL) {
		if (iData != NULL) {
			if (iData->rootName != NULL)
				ccn_charbuf_destroy(&iData->rootName);
			iData->fsRoot = Freestr(iData->fsRoot);
			iData->ccnRoot = Freestr(iData->ccnRoot);
			free(iData);
		}
		free(selfp);
        return(CCN_UPCALL_RESULT_OK);
	}
    if (kind != CCN_UPCALL_INTEREST || iData == NULL) {
        return(CCN_UPCALL_RESULT_ERR);
	}
    if ((info->pi->answerfrom & CCN_AOK_DEFAULT) != 0) {
		// got a new interest
		// TBD: check this conditional with an expert
		MainData md = iData->md;
		seg_t seg = GetSegmentNumber(info);
		char *shortName = GetShortName(md, info, iData->ccnRoot);
		if (shortName != NULL) {
			// request looks valid
			char *host = md->recentHost;
			char us[MaxFileName+4];
			UnPercName(us, 0, MaxFileName, shortName);
			
			md->stats.interestsSeen++;
			
			if (md->debug) {
				double dt = DeltaTime(md->startTime, GetCurrentTime());
				fprintf(stdout, "@%4.3f, interest, ", dt);
				if (host != NULL)
					fprintf(stdout, "%s:", host);
				fprintf(stdout, "%s", us);
				if (seg >= 0)
					fprintf(stdout, ", seg %jd", seg);
				fprintf(stdout, "\n");
				flushLog();
			}
			if (seg < 0) seg = 0;
			
			NetRequest nr = FindNetRequestByName(md, host, shortName);
			FileNode fn = OpenFileNode(md, iData->fsRoot, host, shortName,
									   0, DefaultFreshness);
			if (fn == NULL) {
				// no file node yet
				if (nr == NULL) {
					// no file, no request, so start the HTTP request
					nr = NewNetRequest(iData, shortName);
				} else {
					// there is a busy request, we assume that the file will
					// show up in due course
					fprintf(stdout,
							"-- request busy, no file, %s:%s, seg %jd\n",
							host, us, seg);
				}
				AddSegRequest(nr, seg);
			} else {
				// there IS a file node
				seg_t nSegs = fn->nSegs;
				seg_t safeSeg = nSegs;
				if (fn->final == 0) safeSeg--;
				if (seg >= safeSeg && nr != NULL) {
					// file is not yet stable at tail end
					// ignore this interest and hope that we get another
					if (md->debug) {
						char *fmt = "-- file not yet stable, %s:%s, seg %jd\n";
						fprintf(stdout, fmt, host, us, seg);
					}
					AddSegRequest(nr, seg);
				} else if (seg < nSegs) {
					// we should have the segment in the file
					if (PutSegment(fn, iData->ccnRoot, seg) < 0) {
						// should not happen
						fprintf(stdout,
								"** PutSegment failed, %s:%s, seg %jd\n",
								host, us, seg);
					} else {
						// store worked, try to avoid excess stores
						if (nr != NULL) {
							RemSegRequest(nr, seg);
							if (nr->maxSegStored+1 == seg)
								nr->maxSegStored++;
						}
					}
				}
			}
			flushLog();
			shortName = Freestr(shortName);
		} else {
			// request looks bogus
			if (md->debug) {
				fprintf(stdout, "-- non-http interest ignored\n");
				flushLog();
			}
		}
	}
	return(CCN_UPCALL_RESULT_OK);
}

// RegisterInterest performs the setup to register the global interest
// with the ccn handle.  Any reasonable number of interests can be
// registered, but they all use the same handler.
static int
RegisterInterest(MainData md, char *ccnRoot, char *fsRoot) {
	struct ccn_charbuf *name = ccn_charbuf_create();
	char temp[256];
	snprintf(temp, sizeof(temp), "ccnx:/%s/", ccnRoot);
	int res = ccn_name_from_uri(name, temp);
	if (res < 0) {
        fprintf(stdout, "%s, bad ccn URI, %s\n", md->progname, temp);
		flushLog();
        return -1;
	}
	InterestData iData = ProxyUtil_StructAlloc(1, InterestDataStruct);
	iData->fsRoot = Concat(fsRoot, "");
	iData->ccnRoot = Concat(ccnRoot, "");
	iData->rootName = name;
	iData->md = md;
	struct ccn_closure *cc = ProxyUtil_StructAlloc(1, ccn_closure);
	cc->data = iData;
	cc->p = &NoteInterest;
	ccn_set_interest_filter(md->ccn, name, cc);
	return 0;
}

static void
ShowStats(MainData md) {
	double dt = DeltaTime(md->startTime, GetCurrentTime());
	fprintf(stdout, "@%4.3f, changes %jd",
			dt, (intmax_t) md->changes);
	fprintf(stdout, ", filesCreated %jd",
			(intmax_t) md->stats.filesCreated);
	fprintf(stdout, ", fileBytes %jd",
			(intmax_t) md->stats.fileBytes);
	fprintf(stdout, ", interestsSeen %jd",
			(intmax_t) md->stats.interestsSeen);
	fprintf(stdout, ", segmentsPut %jd",
			(intmax_t) md->stats.segmentsPut);
	fprintf(stdout, ", bytesPut %jd",
			(intmax_t) md->stats.bytesPut);
	fprintf(stdout, "\n");
	flushLog();
}

static int
MainLoop(MainData md) {
	double bt = DeltaTime(0, GetCurrentTime());
	fprintf(stdout, "NetFetch started, baseTime %7.6f\n", bt);
	flushLog();
	// start up server
	RegisterInterest(md, md->ccnRoot, md->fsRoot);
	
	// wait for done
	uint64_t lagChanges = 0;
	SockBase base = md->sockBase;
	for (;;) {
		uint64_t lastChanges = md->changes;
		SH_PrepSelect(base, MainPollMillis*1000);
		int ccnFD = -1;
		// adaptive way to determine the connection FD
		// if ccnd has disappeared while we were busy, try to reconnect
		for (;;) {
			ccnFD = ccn_get_connection_fd(md->ccn);
			if (ccnFD >= 0) break;
			int connRes = ccn_connect(md->ccn, NULL);
			if (connRes < 0) break;
		}
		if (ccnFD < 0) {
			retErr("broken CCN connection");
			break;
		}
		FD_SET(ccnFD, &base->readFDS);
		FD_SET(ccnFD, &base->errorFDS);
		base->fdLen = ccnFD+1;
		SH_DoSelect(base);
		int ccnReady = ( FD_ISSET(ccnFD, &base->readFDS)
						| FD_ISSET(ccnFD, &base->errorFDS));
		if (lastChanges != lagChanges && md->debug)
			ShowStats(md);
		// scan the requests, looking for reads that were done
		NetRequest nr = md->requests;
		while (nr != NULL) {
			SockEntry se = nr->se;
			NetRequest next = nr->next;
			FileNode fn = nr->fn;
			if (fn != NULL) fn->marked++;
			if (se != NULL) {
				int fd = se->fd;
				if (fd >= 0) {
					if (FD_ISSET(fd, &base->readFDS)) {
						// the read can be attempted
						FD_CLR(fd, &base->readFDS);
						ReadFromHttp(nr);
					}
					if (FD_ISSET(fd, &base->writeFDS)) {
						// the write is done
						FD_CLR(fd, &base->writeFDS);
						se->writeActive = 0;
					}
				}
			}
			nr = next;
		}
		
		if (ccnReady) {
			// if there are CCN interests, go get them
			FD_CLR(ccnFD, &base->readFDS);
			FD_CLR(ccnFD, &base->errorFDS);
			ccn_run(md->ccn, 0);
			// now all of the CCN callbacks should have finished
		}
		
		uint64_t now = GetCurrentTime();
		FileNode fn = md->files;
		while (fn != NULL) {
			FileNode next = fn->next;
			if (fn->marked) {
				fn->marked = 0;
			} else {
				double dt = DeltaTime(fn->lastUsed, now);
				if (dt > 60) {
					// it's been a while, so close the file
					CloseFileNode(fn);
				}
			}
			fn = next;
		}
		
		lagChanges = lastChanges;
		if (md->changes == lastChanges) {
			MilliSleep(MainPollMillis);
			SH_PruneAddrCache(base, 600, 300);
		}
	}
	return -1;
}

// main is the command line procedure
// TBD: handle arguments
int
main(int argc, char **argv) {
    char *progname = argv[0];
	struct ccn *h = ccn_create();
	int connRes = ccn_connect(h, NULL);
	if (connRes < 0) {
		return retErr("ccn_connect failed");
	}
	int status = 0;
	MainData md = NewMainData(h);
	SockBase base = SH_NewSockBase();
	base->startTime = md->startTime; // keep them sync'd
	base->debug = stdout;
	md->sockBase = base;
	md->debug = 1;
	md->maxBusySameHost = 4;
	md->keepAliveDefault = KeepAliveDefault;
	md->progname = progname;
	md->ccnRoot = "TestCCN";
	
	int i = 1;
	for (; i <= argc; i++) {
        string arg = argv[i];
		if (arg == NULL || arg[0] == 0) {
		} else if (arg[0] == '-') {
			if (strcasecmp(arg, "-fsRoot") == 0) {
				i++;
				arg = argv[i];
				md->fsRoot = arg;
			} else if (strcasecmp(arg, "-ccnRoot") == 0) {
				i++;
				arg = argv[i];
				md->ccnRoot = arg;
			} else if (strcasecmp(arg, "-noDebug") == 0) {
				md->debug = 0;
			} else if (strcasecmp(arg, "-absTime") == 0) {
				base->startTime = 0;
				md->startTime = 0;
			} else if (strcasecmp(arg, "-fanOut") == 0) {
				i++;
				int n = 0;
				if (i <= argc) n = atoi(argv[i]);
				if (n < 1 || n > 16) {
					fprintf(stdout, "** bad fanOut: %d\n", n);
					return -1;
				}
				md->maxBusySameHost = n;
			} else {
				fprintf(stdout, "** bad arg: %s\n", arg);
                fprintf(stdout, "Usage: %s -fsRoot <root> -ccnRoot <uri> -noDebug -absTime -fanOut <n>\n", argv[0]);
				return -1;
			}
		}
    }
	
	signal(SIGPIPE, SIG_IGN);
	
	status = init_internal_keystore(md);
	if (status >= 0)
		status = MainLoop(md);
    
	md = CloseMainData(md);
	ccn_disconnect(h);
	ccn_destroy(&h);
	exit(status);
}
