/*
 * lib/ccn_fetch.c
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2010-2011 Palo Alto Research Center, Inc.
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

/**
 * Streaming access for fetching segmented CCNx data.
 *
 * Supports multiple streams from a single connection and
 * seeking to an arbitrary position within the associated file.
 *
 * TBD: need to fix up the case where a segment cannot be fetched but we are
 * not really at the end of the stream data.  This case can occur if we express
 * an interest for a segment and the interest times out.  Current behavior is
 * to treat this as an end-of-stream (prematurely and silently)
 *
 * TBD: need to provide a more principled (or maybe just controlled) way to
 * handle interest timeouts.
 */

#include <ccn/fetch.h>

#include <sys/types.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <sys/time.h>

// TBD: the following constants should be more principled
#define CCN_CHUNK_SIZE 4096
#define CCN_VERSION_TIMEOUT 8000
#define CCN_INTEREST_TIMEOUT_USECS 15000000
#define MaxSuffixDefault 4

typedef intmax_t seg_t;

typedef uint64_t TimeMarker;

static TimeMarker
GetCurrentTimeUSecs(void) {
	const TimeMarker M = 1000*1000;
	struct timeval now = {0};
    gettimeofday(&now, 0);
	return now.tv_sec*M+now.tv_usec;
}

static int64_t
DeltaTime(TimeMarker mt1, TimeMarker mt2) {
	return(mt2-mt1);
}

///////////////////////////////////////////////////////

struct ccn_fetch {
	struct ccn *h;
	FILE *debug;
	ccn_fetch_flags debugFlags;
	int localConnect;
	int nStreams;
	int maxStreams;
	struct ccn_fetch_stream **streams;
};

struct ccn_fetch_buffer {
	seg_t seg;			// the seg for this buffer (< 0 if unassigned)
	int len;			// the number of valid bytes
	int max;			// the buffer size
	unsigned char *buf;	// where the bytes are
};

struct localClosure {
	struct ccn_fetch_stream *fs;
	struct localClosure *next;
	seg_t reqSeg;
	TimeMarker startClock;
};

struct ccn_fetch_stream {
	struct ccn_fetch *parent;
	struct localClosure *requests;	// segment requests in process
	int reqBusy;			// the number of requests busy
	int nBufs;				// the number of buffers allocated
	struct ccn_fetch_buffer **bufs;	// the buffers
	char *id;
	struct ccn_charbuf *name;			// interest name (without seq#)
	struct ccn_charbuf *interest;		// interest template
	intmax_t fileSize;		// the file size (< 0 if unassigned)
	intmax_t readPosition;	// the read position (always assigned)
	seg_t maxGoodSeg;		// the highest good segment seen
	seg_t minBadSeg;		// the lowest timeout segment seen
	seg_t finalSeg;			// final segment number (< 0 if not known yet)
	intmax_t timeoutUSecs;		// microseconds for interest timeout
	intmax_t timeoutsSeen;
	seg_t segsRead;
	seg_t segsRequested;
};

// forward reference
static enum ccn_upcall_res
CallMe(struct ccn_closure *selfp,
	   enum ccn_upcall_kind kind,
	   struct ccn_upcall_info *info);

///////////////////////////////////////////////////////
// Internal routines
///////////////////////////////////////////////////////

static char *globalNullString = "";
static char *
newStringCopy(char *src) {
	int n = ((src == NULL) ? 0 : strlen(src));
	if (n <= 0 || src == globalNullString) return globalNullString;
	char *s = calloc(n+1, sizeof(*s));
	strncpy(s, src, n);
	return s;
}

static char *
freeString(char * s) {
	if (s != NULL && s != globalNullString)
		free(s);
	return NULL;
}

static struct ccn_charbuf *
sequenced_name(struct ccn_charbuf *basename, seg_t seq) {
    // creates a new struct ccn_charbuf *, appending the sequence number to the basename
	struct ccn_charbuf *name = ccn_charbuf_create();
    ccn_charbuf_append_charbuf(name, basename);
	if (seq >= 0)
		ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, seq);
    return(name);
}

static struct ccn_charbuf *
make_data_template(int maxSuffix) {
	// creates a template for interests that only have a name
	// and a segment number
	struct ccn_charbuf *cb = ccn_charbuf_create();
    ccn_charbuf_append_tt(cb, CCN_DTAG_Interest, CCN_DTAG);
    ccn_charbuf_append_tt(cb, CCN_DTAG_Name, CCN_DTAG);
    ccn_charbuf_append_closer(cb); /* </Name> */
    ccn_charbuf_append_tt(cb, CCN_DTAG_MaxSuffixComponents, CCN_DTAG);
    ccnb_append_number(cb, maxSuffix);
    ccn_charbuf_append_closer(cb); /* </MaxSuffixComponents> */
    ccn_charbuf_append_closer(cb); /* </Interest> */
    return(cb);
}

static seg_t
GetNumberFromInfo(const unsigned char *ccnb,
				  enum ccn_dtag tt, size_t start, size_t stop) {
	// gets the binary number for the info
	// based on the tag and the start and stop indexes
	// returns -1 if the number does not appear to exist
	// must be called from inside of CallMe
	if (start < stop) {
		size_t len = 0;
		seg_t n = 0;
		const unsigned char *data = NULL;
		ccn_ref_tagged_BLOB(tt, ccnb, start, stop, &data, &len);
		if (len > 0 && data != NULL) {
			// parse big-endian encoded number
			size_t i;
            for (i = 0; i < len; i++) {
				n = n * 256 + data[i];
			}
			return n;
		}
	}
	return -1;
}

static seg_t
GetFinalSegment(struct ccn_upcall_info *info) {
	// gets the final segment number for the content
	// returns -1 if it is not yet known
	// must be called from inside of CallMe
	if (info == NULL) return -1;
	const unsigned char *ccnb = info->content_ccnb;
	if (ccnb == NULL || info->pco == NULL) return -1;
	int start = info->pco->offset[CCN_PCO_B_FinalBlockID];
	int stop = info->pco->offset[CCN_PCO_E_FinalBlockID];
	return GetNumberFromInfo(ccnb, CCN_DTAG_FinalBlockID, start, stop);
}

static struct localClosure *
AddSegRequest(struct ccn_fetch_stream *fs, seg_t seg) {
	// adds a segment request, returns NULL if already present
	// or if the seg given is outside the valid range
	// returns the new request if it was created
	FILE *debug = fs->parent->debug;
	ccn_fetch_flags flags = fs->parent->debugFlags;
	if (seg < 0) return NULL;
	if (fs->finalSeg >= 0 && seg > fs->finalSeg) return NULL;
	struct localClosure *req = fs->requests;
	while (req != NULL) {
		if (req->reqSeg == seg) return NULL;
		req = req->next;
	}
	req = calloc(1, sizeof(*req));
	req->fs = fs;
	req->reqSeg = seg;
	req->startClock = GetCurrentTimeUSecs();
	req->next = fs->requests;
	fs->requests = req;
	if (debug != NULL && (flags & ccn_fetch_flags_NoteAddRem)) {
		fprintf(debug, "-- ccn_fetch AddSegRequest %s, seg %jd\n",
				fs->id, seg);
		fflush(debug);
	}
	return req;
}

static struct localClosure *
RemSegRequest(struct ccn_fetch_stream *fs, struct localClosure *req) {
	// removes a segment request
	// returns NULL if the request was removed
	// if not found then just returns the request
	FILE *debug = fs->parent->debug;
	ccn_fetch_flags flags = fs->parent->debugFlags;
	struct localClosure *this = fs->requests;
	struct localClosure *lag = NULL;
	seg_t seg = req->reqSeg;
	while (this != NULL) {
		struct localClosure *next = this->next;
		if (this == req) {
			if (lag == NULL) {
				fs->requests = next;
			} else {
				lag->next = next;
			}
			req->fs = NULL;
			if (debug != NULL && (flags & ccn_fetch_flags_NoteAddRem)) {
				fprintf(debug, "-- ccn_fetch RemSegRequest %s, seg %jd\n",
						fs->id, seg);
				fflush(debug);
			}
			return NULL;
		}
		lag = this;
		this = next;
	}
	if (debug != NULL && (flags & ccn_fetch_flags_NoteAddRem)) {
		fprintf(debug, "-- ccn_fetch RemSegRequest %s, seg %jd, NOT FOUND!\n",
				fs->id, seg);
		fflush(debug);
	}
	return req;
}

static struct ccn_fetch_buffer *
FindBufferForSeg(struct ccn_fetch_stream *fs, seg_t seg) {
	// finds the buffer object given the segmentnumber
	int i;
    if (seg >= 0)
		for (i = 0; i < fs->nBufs; i++) {
			struct ccn_fetch_buffer *fb = fs->bufs[i];
			if (fb->seg == seg) return fb;
		} 
	return NULL;
}

static int
NeedSegment(struct ccn_fetch_stream *fs, seg_t seg) {
	// requests that a specific segment interest be registered
	// but ONLY if it the request not already in flight
	// AND the segment is not already in a buffer
	struct ccn_fetch_buffer *fb = FindBufferForSeg(fs, seg);
	if (fb != NULL) return 0;
	struct localClosure *req = AddSegRequest(fs, seg);
	if (req != NULL) {
		FILE *debug = fs->parent->debug;
		ccn_fetch_flags flags = fs->parent->debugFlags;
		struct ccn_charbuf *temp = sequenced_name(fs->name, seg);
		struct ccn *h = fs->parent->h;
		struct ccn_closure *action = calloc(1, sizeof(*action));
		action->data = req;
		action->p = &CallMe;
		int res = ccn_express_interest(h, temp, action, fs->interest);
		ccn_charbuf_destroy(&temp);
		if (res >= 0) {
			// the ccn connection accepted our request
			fs->reqBusy++;
			fs->segsRequested++;
			if (debug != NULL && (flags & ccn_fetch_flags_NoteNeed)) {
				fprintf(debug,
						"-- ccn_fetch NeedSegment %s, seg %jd",
						fs->id, seg);
				if (fs->finalSeg >= 0)
					fprintf(debug, ", final %jd", fs->finalSeg);
				fprintf(debug, "\n");
				fflush(debug);
			}
			return 1;
		}
		// the request was not placed, so get rid of the evidence
		// CallMe won't get a chance to free it
		if (debug != NULL && (flags & ccn_fetch_flags_NoteNeed)) {
			fprintf(debug,
					"** ccn_fetch NeedSegment failed, %s, seg %jd\n",
					fs->id, seg);
			fflush(debug);
		}
		RemSegRequest(fs, req);
		free(req);
		free(action);
        
	}
	return 0;
}

static void
NeedSegments(struct ccn_fetch_stream *fs, seg_t limSeg) {
	// determines which segments should be requested
	// based on the current readPosition
	seg_t loSeg = fs->readPosition / CCN_CHUNK_SIZE;
	seg_t finalSeg = fs->finalSeg;
	if (finalSeg >= 0 && limSeg > finalSeg) limSeg = finalSeg;
	if (loSeg > limSeg) limSeg = loSeg;
	while (loSeg <= limSeg) {
		// try to request needed segments
		NeedSegment(fs, loSeg);
		loSeg++;
	}
}

static enum ccn_upcall_res
CallMe(struct ccn_closure *selfp,
	   enum ccn_upcall_kind kind,
	   struct ccn_upcall_info *info) {
	// CallMe is the callback routine invoked by ccn_run when a registered
	// interest has something interesting happen.
    int i;
    struct localClosure *req = (struct localClosure *)selfp->data;
	seg_t thisSeg = req->reqSeg;
    struct ccn_fetch_stream *fs = (struct ccn_fetch_stream *) req->fs;
	if (fs == NULL) {
		if (kind == CCN_UPCALL_FINAL) {
			// orphaned, so just get rid of it
			free(req);
			free(selfp);
		}
		return(CCN_UPCALL_RESULT_OK);
	}
	FILE *debug = fs->parent->debug;
	seg_t finalSeg = fs->finalSeg;
	ccn_fetch_flags flags = fs->parent->debugFlags;
	if (finalSeg < 0) {
		// worth a try to find the last segment
		finalSeg = GetFinalSegment(info);
		fs->finalSeg = finalSeg;
		if (finalSeg >= 0)
			if (debug != NULL && (flags & ccn_fetch_flags_NoteFinal)) {
				fprintf(debug, 
						"-- ccn_fetch, %s, thisSeg %jd, finalSeg %jd\n",
						fs->id, thisSeg, finalSeg);
				fflush(debug);
			}
	}
    
	seg_t needSeg = fs->readPosition / CCN_CHUNK_SIZE;
	seg_t limSeg = needSeg+fs->nBufs-1;
    
	switch (kind) {
		case CCN_UPCALL_FINAL:
			// this is the cleanup for an expressed interest
			req = RemSegRequest(fs, req);
			if (fs->reqBusy > 0) fs->reqBusy--;
			free(selfp);
			return(CCN_UPCALL_RESULT_OK);
		case CCN_UPCALL_INTEREST_TIMED_OUT: {
			if (finalSeg >= 0 && thisSeg > finalSeg)
				// ignore this timeout quickly
				return(CCN_UPCALL_RESULT_OK);
			int64_t dt = DeltaTime(req->startClock, GetCurrentTimeUSecs());
			if (dt >= fs->timeoutUSecs) {
				// timed out, too many retries
				// assume that this interest will never produce
				seg_t minBadSeg = fs->minBadSeg;
				fs->timeoutsSeen++;
				if (minBadSeg < 0 || thisSeg < minBadSeg) {
					// we can infer a new minBadSeg
					fs->minBadSeg = thisSeg;
					if (thisSeg <= fs->maxGoodSeg)
						fs->maxGoodSeg = thisSeg - 1;
				}
				if (debug != NULL && (flags & ccn_fetch_flags_NoteTimeout)) {
					fprintf(debug, 
							"** ccn_fetch timeout, %s, seg %jd",
							fs->id, thisSeg);
					fprintf(debug, 
							", dt %lld us, timeoutUSecs %jd\n",
							dt, fs->timeoutUSecs);
					fflush(debug);
				}
				return(CCN_UPCALL_RESULT_OK);
			}
			// TBD: may need to reseed bloom filter?  who to ask?
			return(CCN_UPCALL_RESULT_REEXPRESS);
		}
		case CCN_UPCALL_CONTENT_UNVERIFIED:
			return (CCN_UPCALL_RESULT_VERIFY);
		case CCN_UPCALL_CONTENT:
			if (fs->minBadSeg >= 0 && fs->minBadSeg <= thisSeg)
				// we will ignore this, since we are blocked
				return(CCN_UPCALL_RESULT_OK);
			if (thisSeg > fs->maxGoodSeg)
				fs->maxGoodSeg = thisSeg;
			if (thisSeg < needSeg || thisSeg > limSeg)
				// no point in getting the contents
				// since we don't really want them
				return(CCN_UPCALL_RESULT_OK);
			break;
		default:
			// SHOULD NOT HAPPEN
			return(CCN_UPCALL_RESULT_ERR);
    }
	
	struct ccn_fetch_buffer *fb = FindBufferForSeg(fs, thisSeg);
	if (fb == NULL) {
		// we don't already have the data
		int found = -1;
		for (i = 0; i < fs->nBufs; i++) {
			struct ccn_fetch_buffer *fb = fs->bufs[i];
			seg_t bSeg = fb->seg;
			if (bSeg < needSeg || bSeg > limSeg) {
				// a very useful victim
				found = i;
				break;
			}
		}
		if (found < 0) {
			// no victim?  this is a bug!
			// TBD: how to recover?
			if (debug != NULL && (flags & ccn_fetch_flags_NoteGlitch)) {
				fprintf(debug, 
						"** ccn_fetch no victim, %s, seg %jd\n",
						fs->id, thisSeg);
				fflush(debug);
			}
		} else {
			// we can finally transfer the data
			const unsigned char *data = NULL;
			size_t dataLen = 0;
			size_t ccnb_size = info->pco->offset[CCN_PCO_E];
			const unsigned char *ccnb = info->content_ccnb;
			int res = ccn_content_get_value(ccnb, ccnb_size, info->pco,
											&data, &dataLen);
			
			if (res < 0) {
			} else if (dataLen == 0) {
				if (debug != NULL && (flags & ccn_fetch_flags_NoteAddRem)) {
					fprintf(debug, 
							"-- ccn_fetch dataLen == 0, %s, seg %jd, final %jd\n",
							fs->id, thisSeg, finalSeg);
					fflush(debug);
				}
			} else {
				// transfer the data
				struct ccn_fetch_buffer *fb = fs->bufs[found];
				if (dataLen > 0) memcpy(fb->buf, data, dataLen);
				fb->seg = thisSeg;
				fb->len = dataLen;
				limSeg = thisSeg+1;
				if (debug != NULL && (flags & ccn_fetch_flags_NoteFill)) {
					fprintf(debug, 
							"-- ccn_fetch FillSeg, %s, seg %jd, len %d\n",
							fs->id, thisSeg, (int) dataLen);
					fflush(debug);
				}
				if (thisSeg == finalSeg) {
					// NOW we really know the file size
					fs->fileSize = thisSeg * CCN_CHUNK_SIZE + dataLen;
					limSeg = thisSeg;
					if (debug != NULL && (flags & ccn_fetch_flags_NoteFinal)) {
						fprintf(debug, 
								"-- ccn_fetch file size, %s, fileSize %jd\n",
								fs->id, fs->fileSize);
						fflush(debug);
					}
				}
				fs->segsRead++;
			}
		}
	}
	
	ccn_set_run_timeout(fs->parent->h, 0);
	return(CCN_UPCALL_RESULT_OK);
}

///////////////////////////////////////////////////////
// External routines
///////////////////////////////////////////////////////

/**
 * Creates a new ccn_fetch object using the given ccn connection.
 * If h == NULL, attempts to create a new connection automatically.
 * @returns NULL if the creation was not successful
 * (only can happen for the h == NULL case).
 */
extern struct ccn_fetch *
ccn_fetch_new(struct ccn *h) {
	struct ccn_fetch *f = calloc(1, sizeof(*f));
	if (h == NULL) {
		h = ccn_create();
		int connRes = ccn_connect(h, NULL);
		if (connRes < 0) {
			ccn_destroy(&h);
			free(f);
			return NULL;
		}
		f->localConnect = 1;
	}
	f->h = h;
	return f;
}

void
ccn_fetch_set_debug(struct ccn_fetch *f, FILE *debug, ccn_fetch_flags flags) {
	f->debug = debug;
	f->debugFlags = flags;
}

/**
 * Destroys a ccn_fetch object.
 * Only destroys the underlying ccn connection if it was automatically created.
 * Forces all underlying streams to close immediately.
 * @returns NULL in all cases.
 */
extern struct ccn_fetch *
ccn_fetch_destroy(struct ccn_fetch *f) {
	// destroys a ccn_fetch object
	// always returns NULL
	// only destroys the underlying ccn connection if it was
	// automatically created, otherwise does not alter it
	if (f != NULL) {
		struct ccn *h = f->h;
		if (h != NULL && f->localConnect) {
			ccn_disconnect(h);
			ccn_destroy(&f->h);
		}
		// take down all of the streams
		while (f->nStreams > 0) {
			struct ccn_fetch_stream *fs = f->streams[0];
			if (fs == NULL) break;
			ccn_fetch_close(fs);
		}
		free(f);
	}
	return NULL;
}

/**
 * Polls the underlying streams and attempts to make progress.
 * Scans the streams for those that have data already present, or are at the end
 * of the stream.  If the count is 0, perfoms a ccn_poll on the underlying
 * ccn connection with a 0 timeout.
 *
 * NOTE: periodic calls to ccn_fetch_poll should be performed to update
 * the contents of the streams UNLESS the client is calling ccn_run for
 * the underlying ccn connection.
 * @returns the count of streams that have pending data or have ended.
 */
extern int
ccn_fetch_poll(struct ccn_fetch *f) {
	int i;
    int count = 0;
	int ns = f->nStreams;
	for (i = 0; i < ns; i++) {
		struct ccn_fetch_stream *fs = f->streams[i];
		if (fs != NULL) {
			intmax_t avail = ccn_fetch_avail(fs);
			if (avail >= 0) count++;
		}
	}
	// we should try for more progress
	ccn_run(f->h, 0);
	return count;
}

/**
 * Provides an iterator through the underlying streams.
 * Use fs == NULL to start the iteration, and an existing stream to continue
 * the iteration.
 * @returns the next stream in the iteration, or NULL at the end.
 * Note that providing a stale (closed) stream handle will return NULL.
 */
extern struct ccn_fetch_stream *
ccn_fetch_next(struct ccn_fetch *f, struct ccn_fetch_stream *fs) {
	int i;
    int ns = f->nStreams;
    struct ccn_fetch_stream *lag = NULL;
	for (i = 0; i < ns; i++) {
		struct ccn_fetch_stream *tfs = f->streams[i];
		if (tfs != NULL) {
			if (lag == fs) return tfs;
			lag = tfs;
		}
	}
	return NULL;
}

/**
 * @returns the underlying ccn connection.
 */
extern struct ccn *
ccn_fetch_get_ccn(struct ccn_fetch *f) {
	return f->h;
}

/**
 * Creates a stream for a named interest.
 * The name should be a ccnb encoded interest.
 * If resolveVersion, then we assume that the version is unresolved, 
 * and an attempt is made to determine the version number using the highest
 * version.
 * The number of buffers (nBufs) may be silently limited.
 * @returns NULL if the stream creation failed,
 * otherwise returns the new stream.
 */
extern struct ccn_fetch_stream *
ccn_fetch_open(struct ccn_fetch *f,
			   struct ccn_charbuf *name,
			   const char *id,
			   struct ccn_charbuf *interestTemplate,
			   int nBufs,
			   int resolveVersion) {
	// returns a new ccn_fetch_stream object based on the arguments
	// returns NULL if not successful
	int i;
    if (nBufs <= 0) return NULL;
	if (nBufs > 64) nBufs = 64;
	int res = 0;
	FILE *debug = f->debug;
	ccn_fetch_flags flags = f->debugFlags;
    
	// first, resolve the version
	struct ccn_fetch_stream *fs = calloc(1, sizeof(*fs));
	fs->name = ccn_charbuf_create();
	fs->id = newStringCopy((char *) id);
	ccn_charbuf_append_charbuf(fs->name, name);
	if (resolveVersion) {
		int tm = 40; // TBD: need better strategy for version timeout
		while (tm < CCN_VERSION_TIMEOUT) {
			res = ccn_resolve_version(f->h, fs->name, resolveVersion, tm);
			if (res >= 0) break;
			tm = tm + tm;
		}
		if (res < 0) {
			// could not resolve version for this name
			// get rid of allocations so far and bail out
			if (debug != NULL && (flags & ccn_fetch_flags_NoteOpenClose)) {
				fprintf(debug, 
						"-- ccn_fetch open, %s, failed to resolve version\n",
						fs->id);
				fflush(debug);
			}
			ccn_charbuf_destroy(&fs->name);
			freeString(fs->id);
			free(fs);
			return NULL;
		}
	}
	fs->fileSize = -1;
	fs->finalSeg = -1;
	fs->maxGoodSeg = -1;
	fs->minBadSeg = -1;
	fs->parent = f;
	fs->timeoutUSecs = CCN_INTEREST_TIMEOUT_USECS;  // TBD: how to get better timeout?
	
	// use the supplied template or the default
	if (interestTemplate != NULL) {
		struct ccn_charbuf *cb = ccn_charbuf_create();
		ccn_charbuf_append_charbuf(cb, interestTemplate);
		fs->interest = cb;
	} else
		fs->interest = make_data_template(MaxSuffixDefault);
	
	// allocate the buffers
	fs->nBufs = nBufs;
	fs->bufs = calloc(nBufs, sizeof(*(fs->bufs)));
	for (i = 0; i < nBufs; i++) {
		struct ccn_fetch_buffer *fb = calloc(1, sizeof(*fb));
		fb->seg = -1;
		fb->max = CCN_CHUNK_SIZE;
		fb->buf = calloc(fb->max, sizeof(*(fb->buf)));
		fs->bufs[i] = fb;
	}
	
	// remember the stream in the parent
	int ns = f->nStreams;
	int max = f->maxStreams;
	if (ns >= max) {
		// extend the vector
		int nMax = max+max/2+4;
        f->streams = realloc(f->streams, sizeof(*(f->streams)) * nMax);
		f->maxStreams = nMax;
	}
	// guaranteed room to add at the end
	f->streams[ns] = fs;
	f->nStreams = ns+1;
	
	if (debug != NULL && (flags & ccn_fetch_flags_NoteOpenClose)) {
		fprintf(debug, 
				"-- ccn_fetch open, %s\n",
				fs->id);
		fflush(debug);
	}
	// prep for the first segment
	NeedSegment(fs, 0);
	return fs;
}

/**
 * Closes the stream and reclaims any resources used by the stream.
 * The stream object will be freed, so the client must not access it again.
 * @returns NULL in all cases.
 */
extern struct ccn_fetch_stream *
ccn_fetch_close(struct ccn_fetch_stream *fs) {
	// destroys a ccn_fetch_stream object
	// implicit abort of any outstanding fetches
	// always returns NULL
	int i;
    FILE *debug = fs->parent->debug;
	ccn_fetch_flags flags = fs->parent->debugFlags;
    
	// make orphans of all outstanding requests
	// CallMe should handle the cleanup
	struct localClosure * this = fs->requests;
	fs->requests = NULL;
	while (this != NULL) {
		this->fs = NULL;
		this = this->next;
	}
	// free up the buffers
	int nBufs = fs->nBufs;
	for (i = 0; i < nBufs; i++) {
		struct ccn_fetch_buffer *fb = fs->bufs[i];
		fs->bufs[i] = NULL;
		if (fb != NULL) {
			if (fb->buf != NULL) free(fb->buf);
			free(fb);
		}
	}
	
	if (fs->name != NULL)
		ccn_charbuf_destroy(&fs->name);
	if (fs->interest != NULL)
		ccn_charbuf_destroy(&fs->interest);
	struct ccn_fetch *f = fs->parent;
	if (f != NULL) {
		int ns = f->nStreams;
		fs->parent = NULL;
		for (i = 0; i < ns; i++) {
			struct ccn_fetch_stream *tfs = f->streams[i];
			if (tfs == fs) {
				// found it, so get rid of it
				ns--;
				f->nStreams = ns;
				f->streams[i] = NULL;
				f->streams[i] = f->streams[ns];
				f->streams[ns] = NULL;	
				break;	
			}
		}
	}
	if (debug != NULL && (flags & ccn_fetch_flags_NoteOpenClose)) {
		fprintf(debug, 
				"-- ccn_fetch close, %s, segReq %jd, segsRead %jd, timeouts %jd\n",
				fs->id,
				fs->segsRequested,
				fs->segsRead,
				fs->timeoutsSeen);
		fflush(debug);
	}
	// finally, get rid of the stream object
	freeString(fs->id);
	free(fs);
	return NULL;
}

/**
 * Tests for available bytes in the stream.
 * Determines how many bytes can be read on the given stream
 * without waiting (via ccn_fetch_poll).
 * @returns -1 if no bytes are immediately available,
 *    0 if the stream is at the end,
 *    and N > 0 if N bytes can be read without performing a poll.
 */
extern intmax_t
ccn_fetch_avail(struct ccn_fetch_stream *fs) {
	intmax_t pos = fs->readPosition;
	if (fs->fileSize >= 0 && pos >= fs->fileSize) {
		// file size known, and we are at the limit
		return 0;
	}
	intmax_t avail = 0;
	seg_t loSeg = pos / CCN_CHUNK_SIZE;
	if (fs->minBadSeg >= 0 && loSeg >= fs->minBadSeg)
		// if we failed to get a segment and we needed it, assume EOF
		// TBD: not a good assumption?
		return -1;
	seg_t hiSeg = loSeg + fs->nBufs - 1;
	seg_t finalSeg = fs->finalSeg;
	if (finalSeg >= 0 && hiSeg > finalSeg) hiSeg = finalSeg;
	if (loSeg > hiSeg) return -1; // seek beyond EOF may cause this
	int mod = pos % CCN_CHUNK_SIZE;
	
	seg_t seg = loSeg;
	while (seg <= hiSeg) {
		struct ccn_fetch_buffer *fb = FindBufferForSeg(fs, seg);
		if (fb == NULL) break;
		int len = fb->len;
		avail = avail + len;
		if (seg == loSeg && mod != 0) {
			// correct for an offset into the block
			if (mod > len) mod = len;  // really?
			avail = avail - mod;
		}
		seg++;
	}
	if (avail == 0) avail = -1;
	return avail;
}

/**
 * Reads bytes from a stream.
 * Reads at most len bytes into buf from the given stream.
 * Will not wait for bytes to arrive.
 * Advances the read position on a successful read.
 * @returns -1 if no bytes are immediately available
 *    (includes len <= 0 or buf == NULL cases),
 *    0 if the stream is at the end,
 *    and N > 0 if N bytes can be read without performing a poll.
 */
extern intmax_t
ccn_fetch_read(struct ccn_fetch_stream *fs,
			   void *buf,
			   intmax_t len) {
	if (len < 0 || buf == NULL) {
		return CCN_FETCH_READ_NONE;
	}
	intmax_t off = 0;
	intmax_t pos = fs->readPosition;
	if (fs->fileSize >= 0 && pos >= fs->fileSize) {
		// file size known, and we are at the limit
		return CCN_FETCH_READ_END;
	}
	intmax_t nr = 0;
	unsigned char *dst = (unsigned char *) buf;
	seg_t finalSeg = fs->finalSeg;
	seg_t seg = pos / CCN_CHUNK_SIZE;
	
	if (fs->minBadSeg >= 0 && seg >= fs->minBadSeg)
		// if a needed read timed out, then we say so
		return CCN_FETCH_READ_TIMEOUT;
	seg_t limSeg = seg+fs->nBufs-1;
	if (seg*2 < limSeg)
		// don't start off too quickly, make this nice for short files
		limSeg = seg*2;
	if (finalSeg >= seg && finalSeg < limSeg)
		// use finalSeg to limit the excess interests
		limSeg = finalSeg;
	while (seg <= limSeg && len > 0) {
		struct ccn_fetch_buffer *fb = FindBufferForSeg(fs, seg);
		if (fb == NULL) break;
		unsigned char *src = fb->buf;
		seg_t lo = seg * CCN_CHUNK_SIZE;
		seg_t hi = lo + fb->len;
		if (pos < lo || pos >= hi || seg != fb->seg) {
			// this SHOULD NOT HAPPEN!
			FILE *debug = fs->parent->debug;
			if (debug != NULL) {
				fprintf(debug, 
						"** ccn_fetch read, %s, seg %jd, pos %jd, lo %jd, hi %jd\n",
						fs->id, seg, pos, (intmax_t) lo, (intmax_t) hi);
				fflush(debug);
			}
			break;
		}
		int d = hi - pos;
		if (d > len) d = len;
		memcpy(dst+off, src+(pos-lo), d);
		nr = nr + d;
		pos = pos + d;
		off = off + d;
		len = len - d;
		fs->readPosition = pos;
		seg++;
	}
	NeedSegments(fs, limSeg);
	if (nr == 0) {
		return CCN_FETCH_READ_NONE;
	}
	return nr;
}

/**
 * Resets the timeout marker.
 */
extern void
ccn_reset_timeout(struct ccn_fetch_stream *fs) {
	fs->minBadSeg = -1;
}

/**
 * Seeks to a position in a stream.
 * Sets the read position.
 * It is strongly recommended that the seek is only done to a position that
 * is either 0 or has resulted from a successful read.  Otherwise
 * end of stream indicators may be returned for a seek beyond the end.
 * @returns -1 if the seek is to a bad position, otherwise returns 0.
 */
extern int
ccn_fetch_seek(struct ccn_fetch_stream *fs, intmax_t pos) {
	// seeks to the given position in the input stream
	if (pos < 0) return -1;
	intmax_t fileSize = fs->fileSize;
	if (fileSize >= 0) {
		// file is known exactly, so fast case
		if (pos > fileSize) return -1;
		fs->readPosition = pos;
		return 0;
	}
	// at this point we don't know the exact size
	seg_t seg = pos / CCN_CHUNK_SIZE;
	int mod = pos % CCN_CHUNK_SIZE;
	if (fs->minBadSeg >= 0 && seg >= fs->minBadSeg) {
		// be careful to not return bad seek indicator if we are seeking to
		// the exact EOF and we don't know exactly where it is
		if (mod > 0 || seg > fs->minBadSeg)
			// failed to get segment, but we really need it
			return -1;
	}
	struct ccn_fetch_buffer *fb = FindBufferForSeg(fs, seg);
	if (fb != NULL) {
		// fast case, already in a buffer
		if (mod > fb->len) return -1; // beyond the end
		fs->readPosition = pos;
	} else {
		// force the segment requests
		fs->readPosition = pos;
		NeedSegment(fs, seg);
		if (mod == 0 && seg > 0) NeedSegment(fs, seg-1);
	}
	return 0;
}

/**
 * @returns the current read position.
 */
extern intmax_t
ccn_fetch_position(struct ccn_fetch_stream *fs) {
	// returns the current read position
	return fs->readPosition;
}


