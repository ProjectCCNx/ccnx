/**
 * @file ccn_fetch_test.c
 * @brief Provides a test platform for ccn_fetch.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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

#include <ccn/fetch.h>

#include <ccn/ccn.h>
#include <ccn/uri.h>

#include <sys/select.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <strings.h>
#include <time.h>
#include <unistd.h>

#include <sys/time.h>

#define LOCAL_BUF_MAX 20000

typedef struct ccn_charbuf *MyCharbuf;
typedef char *string;

struct MyParms {
	struct ccn_fetch *f;
	int ccnFD;
	string src;
	string dst;
	FILE *debug;
	int resolveVersion;
	int appendOut;
	int assumeFixed;
	int maxSegs;
};

static uint64_t
GetCurrentTime(void) {
	const uint64_t M = 1000*1000;
	struct timeval now;
    gettimeofday(&now, 0);
	return now.tv_sec*M+now.tv_usec;
}

static double
DeltaTime(uint64_t mt1, uint64_t mt2) {
	int64_t dmt = mt2-mt1;
	return dmt*1.0e-6;
}

#define MinMilliSleep 2
static void
MilliSleep(uint64_t n) {
	if (n >= MinMilliSleep) {
		const uint64_t M = 1000*1000;
		const uint64_t G = 1000*M;
		n = n * M;
		struct timespec ts;
		ts.tv_sec = n / G;
		ts.tv_nsec = n % G;
		nanosleep(&ts, NULL);
	}
}
#define MyAlloc(NNN, TTT) (TTT *) calloc(NNN, sizeof(TTT))

static int
retErr(string msg) {
	fprintf(stderr, "** error: %s\n", msg);
	fflush(stderr);
	return -1;
}

typedef struct TestElemStruct *TestElem;
struct TestElemStruct {
	FILE *out;
	string fileName;
	struct ccn_fetch_stream *fs;
	string buf;
	int bufMax;
	int bufLen;
	intmax_t accum;
	uint64_t startTime;
};

static TestElem
NewElem(struct MyParms *p) {
	string name = p->src;
	int bufMax = LOCAL_BUF_MAX;
	TestElem e = MyAlloc(1, struct TestElemStruct);
	e->startTime = GetCurrentTime();
	MyCharbuf cbName = ccn_charbuf_create();
	int res = ccn_name_from_uri(cbName, name);
	if (res < 0) {
		fprintf(stderr, "** open of %s failed!\n", name);
	} else {
		e->fs = ccn_fetch_open(p->f, cbName,
							   name,
							   NULL,
							   p->maxSegs,
							   p->resolveVersion,
							   p->assumeFixed);
		if (e->fs == NULL) {
			fprintf(stderr, "** open of %s failed!\n", name);
		} else {
			fprintf(stderr, "-- opened %s\n", name);
			if (p->dst != NULL) {
				e->fileName = p->dst;
				FILE *out = fopen(e->fileName,
								  ((p->appendOut > 0) ? "a" : "w"));
				e->out = out;
			} else {
				e->fileName = "stdout";
				e->out = stdout;
			}
			e->buf = MyAlloc(bufMax+4, char);
			e->bufMax = bufMax;
		}
	}
	ccn_charbuf_destroy(&cbName);
	return e;
}

static TestElem
ElemDone(TestElem e) {
	if (e->fs != NULL)
		e->fs = ccn_fetch_close(e->fs);
	if (e->out != NULL && e->out != stdout)
		fclose(e->out);
	double dt = DeltaTime(e->startTime, GetCurrentTime());
	if (e->accum > 0)
		fprintf(stderr,
				"-- Moved %jd bytes to %s in %4.3f secs (%4.3f MB/sec)\n",
				e->accum, e->fileName, dt, e->accum * 1.0e-6 / dt);
	if (e->buf != NULL) free(e->buf);
	free(e);
	return NULL;
}

static int
runTest(struct MyParms *p) {
	int res = 0;
	string msg = NULL;
	struct SelectDataStruct {
		int fdLen;
		fd_set readFDS;
		fd_set writeFDS;
		fd_set errorFDS;
		struct timeval selectTimeout;
	} sds;
	int timeoutUsecs = 100;
	sds.selectTimeout.tv_sec = (timeoutUsecs / 1000000);
	sds.selectTimeout.tv_usec = (timeoutUsecs % 1000000);
	
	// initialize the test files
	TestElem e = NewElem(p);
	if (e->fs == NULL) {
		// could not open the file
		res = -1;
	} else {
		for (;;) {
			FD_ZERO(&sds.readFDS);
			FD_ZERO(&sds.writeFDS);
			FD_ZERO(&sds.errorFDS);
			sds.fdLen = p->ccnFD+1;
			FD_SET(p->ccnFD, &sds.readFDS);
			FD_SET(p->ccnFD, &sds.writeFDS);
			FD_SET(p->ccnFD, &sds.errorFDS);
			int res = select(sds.fdLen,
							 &sds.readFDS,
							 &sds.writeFDS,
							 &sds.errorFDS,
							 &sds.selectTimeout
							 );
			if (res != 0) ccn_fetch_poll(p->f);
			intmax_t nb = ccn_fetch_read(e->fs, e->buf, e->bufMax);
			if (nb == CCN_FETCH_READ_END) {
				// end of this test
				break;
			} else if (nb > 0) {
				// there is data to be written
				fwrite(e->buf, sizeof(char), nb, e->out);
				e->accum = e->accum + nb;
			} else if (nb == CCN_FETCH_READ_NONE) {
				// we just don't know enough right now
				MilliSleep(5);
			} else if (nb == CCN_FETCH_READ_TIMEOUT) {
				// timeouts are treated as transient (maybe not true)
				ccn_reset_timeout(e->fs);
				MilliSleep(5);
			} else {
				// random failure
				msg = "read failed";
			}
		}
		
	}
	e = ElemDone(e);
	MilliSleep(5);

	// cleanup
	if (msg != NULL) {
		return retErr(msg);
	}
	return res;

}

static char *help = "usage: ccn_fetch_test {switch | ccnName}*\n\
    -help     help\n\
    -out XXX  sets output file to XXX (default: stdout)\n\
    -mb NNN   ses NNN as max number of buffers to use (default: 4)\n\
    -d        enables debug output (default: none)\n\
    -f        use fixed-size segments (default: variable)\n\
    -nv       no resolve version (default: CCN_V_HIGH)\n";

int
main(int argc, string *argv) {
	
	struct ccn *h = ccn_create();
	int connRes = ccn_connect(h, NULL);
	if (connRes < 0) {
		return retErr("ccn_connect failed");
	}
	struct ccn_fetch *f = ccn_fetch_new(h);
	int ccnFD = ccn_get_connection_fd(h);
	int needHelp = ((argc < 2) ? 1 : 0);
	
	struct MyParms p = {0};
	
	p.resolveVersion = CCN_V_HIGH;
	p.f = f;
	p.ccnFD = ccnFD;
	p.maxSegs = 4;
	
	int i = 1;
	while (i < argc) {
		string arg = argv[i++];
		if (arg[0] == '-') {
			if (strcasecmp(arg, "-out") == 0) {
				p.appendOut = 0;
				p.dst = NULL;
				if (i < argc) p.dst = argv[i++];
			} else if (strcasecmp(arg, "-d") == 0) {
				p.debug = stderr;
			} else if (strcasecmp(arg, "-f") == 0) {
				p.assumeFixed = 1;
			} else if (strcasecmp(arg, "-help") == 0) {
				needHelp++;
				break;
			} else if (strcasecmp(arg, "-nv") == 0) {
				p.resolveVersion = 0;
			} else if (strcasecmp(arg, "-mb") == 0) {
				if (i < argc) p.maxSegs = atoi(argv[i++]);
			} else {
				fprintf(stderr, "-- Unsupported switch: %s\n", arg);
				needHelp++;
				break;
			}
		} else {
			if (p.debug != NULL)
				ccn_fetch_set_debug(f, p.debug, ccn_fetch_flags_NoteAll);
			p.src = arg;
			runTest(&p);
			p.appendOut = 1;
		}
	}

	f = ccn_fetch_destroy(f);
	ccn_disconnect(h);
	ccn_destroy(&h);
	if (needHelp) {
		fprintf(stderr, help, NULL);
	}
	return 0;
}

