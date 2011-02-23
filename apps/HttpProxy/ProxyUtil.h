/* HttpProxy/ProxyUtil.h
 * Assorted low-level utilities.
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

#ifndef ProxyUtil_Include
#define ProxyUtil_Include

#include <stdint.h>

// Basic alocation macros
 
#define ProxyUtil_Alloc(NNN, TTT) (TTT *) calloc(NNN, sizeof(TTT))
 #define ProxyUtil_StructAlloc(NNN, TTT) (struct TTT *) calloc(NNN, sizeof(struct TTT))

// High-precision elapsed time support

typedef uint64_t TimeMarker;

extern TimeMarker
GetCurrentTime(void);

extern double
DeltaTime(TimeMarker mt1, TimeMarker mt2);

extern void
MilliSleep(int n);

// Shared string support

#define PartMax 63
#define NameMax 253

typedef char *string;

extern string
Concat (string s1, string s2);

extern string
Freestr(string str);

extern char
LowerCase(char c);

extern int
IsNumeric(char c);

extern int
IsAlpha(char c);

extern int
HexDigit(char c);

extern int
HasPrefix(string s, int sLen, string prefix);

extern int
HasPrefix2(string s, int sLen, string prefix1, string prefix2);
// returns 1 if there is a match for prefix1 followed by any number of blanks
// (including zero blanks), followed by prefix2
// otherwise returns 0

extern int
HasSuffix(string s, int sLen, string suffix);

extern int
TokenPresent(string buf, int len, string token);

extern int
SwitchPresent(string buf, int len, string token);

extern char
ShortNameChar(char c);

extern int
SkipOverBlank(string buf, int pos, int len);

extern int
SkipToBlank(string buf, int pos, int len);

extern int
NextLine(string buf, int pos, int len);

extern int
AcceptPart(string buf, int pos, string part, int partMax);

extern int
SameHost(string x, string y);

// returns length of host name if legal
// -1 if name is not legal by RFC 1034 and RFC 1035 rules
extern int
AcceptHostName(string buf, int pos, string host, int lim);

extern int
AcceptHostPort(string buf, int pos, int *port);
// returns length of port string if present if legal, 0 if not present
	
extern uint32_t
EvalUint(string buf, int pos);

#endif
