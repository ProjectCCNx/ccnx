/*
 * HttpProxy/ProxyUtil.c
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
 
/*
 *  Created by atkinson on 5/24/10.
 */

#include "./ProxyUtil.h"
#include <sys/time.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <time.h>

extern TimeMarker
GetCurrentTime(void) {
	const TimeMarker M = 1000*1000;
	struct timeval now = {0};
    gettimeofday(&now, 0);
	return now.tv_sec*M+now.tv_usec;
}

extern double
DeltaTime(TimeMarker mt1, TimeMarker mt2) {
	int64_t dmt = mt2-mt1;
	return dmt*1.0e-6;
}

extern void
MilliSleep(int n) {
	if (n >= 1) {
		const TimeMarker M = 1000*1000;
		const TimeMarker G = 1000*M;
		n = n * M;
		struct timespec ts = {n / G, n % G};
		nanosleep(&ts, NULL);
	}
}

// Shared string support

extern string
Concat (string s1, string s2) {
	if (s1 == NULL && s2 == NULL) return NULL;
	int len1 = ((s1 != NULL) ? strlen(s1) : 0);
	int len2 = ((s2 != NULL) ? strlen(s2) : 0);
	char *s = (char *) calloc(len1+len2+1, sizeof(char));
	int i = 0;
	for (i = 0; i < len1; i++) s[i] = s1[i];
	for (i = 0; i < len2; i++) s[len1+i] = s2[i];
	return s;
}

extern string
Freestr(string str) {
	if (str != NULL) free((void *) str);
	return NULL;
}

extern char
LowerCase(char c) {
	if (c >= 'A' && c <= 'Z') return c + ('a' - 'A');
	return c;
}

extern int
IsNumeric(char c) {
	if (c >= '0' && c <= '9') return c;
	return 0;
}

extern int
IsAlpha(char c) {
	if (c >= 'A' && c <= 'Z') return c;
	if (c >= 'a' && c <= 'Z') return c - ('a'-'A');
	return 0;
}

extern int
HexDigit(char c) {
	if (c >= '0' && c <= '9') return (c - '0');
	if (c >= 'a' && c <= 'f') return (10 + c - 'a');
	if (c >= 'A' && c <= 'F') return (10 + c - 'A');
	return -1;
}

extern int
HasPrefix(string s, int sLen, string prefix) {
	int pos = 0;
	for (;;) {
		char cc = prefix[pos];
		if (cc == 0) return 1;
		if (pos >= sLen) return 0;
		char c = s[pos];
		if (c != cc) return 0;
		pos++;
	}
}

extern int
HasPrefix2(string s, int sLen, string prefix1, string prefix2) {
	int pos = 0;
	// try to match the first string
	for (;;) {
		char cc = prefix1[pos];
		if (cc == 0) break;
		if (pos >= sLen) return 0;
		char c = s[pos];
		if (c != cc) return 0;
		pos++;
	}
	// skip the blanks
	for (;;) {
		char c = s[pos];
		if (c != ' ') break;
		pos++;
	}
	int pos1 = pos;
	// try to match the second string
	for (;;) {
		char cc = prefix2[pos-pos1];
		if (cc == 0) break;
		if (pos >= sLen) return 0;
		char c = s[pos];
		if (c != cc) return 0;
		pos++;
	}
	return 1;
}

extern int
HasSuffix(string s, int sLen, string suffix) {
	int sPos = sLen;
	int sufPos = strlen(suffix);
	if (sPos < sufPos)
		// suffix is too long, no match
		return 0;
	for (;;) {
		sPos--;
		sufPos--;
		if (sufPos < 0) return 1;
		if (s[sPos] != suffix[sufPos]) return 0;
	}
}

extern int
TokenPresent(string buf, int len, string token) {
	int off = 0;
	while (off < len) {
		char bc = buf[off];
		char tc = token[off];
		off++;
		if (tc == 0 || off == len) return 1;
		if (bc != tc) break;
	}
	return 0;	
}

extern int
SwitchPresent(string buf, int len, string token) {
	int off = 0;
	while (off < len) {
		char bc = LowerCase(buf[off]);
		char tc = LowerCase(token[off]);
		off++;
		if (tc == 0 || off == len) return 1;
		if (bc != tc) break;
	}
	return 0;	
}

extern char
ShortNameChar(char c) {
	if (c >= 'A' && c <= 'Z') return c;
	if (c >= 'a' && c <= 'z') return c;
	if (c >= '0' && c <= '9') return c;
	if (c == '/' || c == '.' || c == '%' || c == '-' || c == '_'
		|| c == '?' || c == '&' || c == '=') return c;
	return 0;
}

extern int
SkipOverBlank(string buf, int pos, int len) {
	while (pos < len) {
		if (buf[pos] != ' ') break;
		pos++;
	}
	return pos;
}

extern int
SkipToBlank(string buf, int pos, int len) {
	while (pos < len) {
		if (buf[pos] <= ' ') break;
		pos++;
	}
	return pos;
}

extern int
NextLine(string buf, int pos, int len) {
	while (pos < len) {
		char c = buf[pos];
		if (c < ' ' && c != '\r' && c != '\n' && c != '\t') break;
		pos++;
		if (c == '\n') break;
	}
	return pos;
}

extern int
AcceptPart(string buf, int pos, string part, int partMax) {
	int len = 0;
	while (len < partMax) {
		char c = buf[pos+len];
		if ((c >= 'a' && c <= 'z')
			|| (c >= 'A' && c <= 'Z') 
			|| (c >= '0' && c <= '9')
			|| (c == '-')) {
			// valid character
			if (part != NULL) part[len] = c;
			len++;
		} else break;
	}
	if (part != NULL) part[len] = 0;
	return pos+len;
}

extern int
SameHost(string x, string y) {
	if (x != NULL && y != NULL && strcasecmp(x, y) == 0)
		return 1;
	return 0;
}

extern int
AcceptHostName(string buf, int pos, string host, int lim) {
	// returns length of host name if legal
	// accumulates host name into host (if not NULL)
	// -1 if name is not legal by RFC 1034 and RFC 1035 rules
	// lim is limit of the host length
	int len = 0;
	int partLen = 0;
	char lag = 0;
	while (len < lim) {
		char c = buf[pos];
		if (c == '.') {
			// separator
			if (partLen > PartMax || len > NameMax) return -1;
			if (lag == 0 || lag == '.') return -1;
			partLen = 0;
		} else if (c >= 'A' && c <= 'Z') {
			// upper ->lower
			c = c + ('a' - 'A');
			partLen++;
		} else if ((c >= 'a' && c <= 'z')
				   || (c >= '0' && c <= '9')
				   || (c == '-')) {
			// valid character
			partLen++;
		} else {
			// not part of valid host name, so terminate now
			if (partLen > PartMax || len > NameMax) return -1;
			if (lag == 0 || lag == '.') return -1;
			break;
		}
		if (host != NULL) host[len] = c;
		lag = c;
		len++;
		pos++;
	}
	if (len > lim) return -1;
	if (host != NULL) host[len] = 0;
	return len;
}

extern int
AcceptHostPort(string buf, int pos, int *port) {
	// returns length of port string if present if legal, 0 if not present
	// accumulates port into port[0] (if port != NULL)
	// port string length includes the ':', which is required
	int ret = 1;
	if (buf[pos] != ':') return 0;
	pos++;
	int acc = 0;
	for (;;) {
		char c = buf[pos];
		if (c < '0' || c > '9') break;
		pos++;
		ret++;
		acc = acc*10 + (c - '0');
	}
	if (port != NULL) port[0] = acc;
	if (ret == 1) ret = 0;
	return ret;
}

extern uint32_t
EvalUint(string buf, int pos) {
	// quick & dirty unsigned value extraction
	uint32_t n = 0;
	int seen = 0;
	for (;;) {
		char c = buf[pos];
		if (c == ' ' || c == '\t') {
			if (seen > 0) return n;
			pos++;
		} else if (c >= '0' && c <= '9') {
			n = n * 10 + (c - '0');
			pos++;
			seen++;
		} else return n;
	}
}

