# apps/HttpProxy/dir.mk
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009-2010 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

LDLIBS = -L$(CCNLIBDIR) $(MORE_LDLIBS) -lccn -lcrypto
EXPATLIBS = -lexpat
CCNLIBDIR = ../../lib
INCLDIR = ../../include

PROGRAMS = NetFetch HttpProxy

CSRC = HttpProxy.c NetFetch.c ProxyUtil.c SockHop.c

default all: $(PROGRAMS)

all: NetFetch HttpProxy

clean:
	rm -f *.o $(PROGRAMS)
	rm -rf *.dSYM *% *~

install: 

uninstall: 

HttpProxy: HttpProxy.o ProxyUtil.o SockHop.o
	$(CC) $(CFLAGS) -o $@ HttpProxy.o ProxyUtil.o SockHop.o $(LDLIBS)

NetFetch: NetFetch.o ProxyUtil.o SockHop.o
	$(CC) $(CFLAGS) -o $@ NetFetch.o ProxyUtil.o SockHop.o $(LDLIBS)

HttpProxy.o:
	$(CC) $(CFLAGS) -c HttpProxy.c

NetFetch.o:
	$(CC) $(CFLAGS) -c NetFetch.c

ProxyUtil.o:
	$(CC) $(CFLAGS) -c ProxyUtil.c

SockHop.o: 
	$(CC) $(CFLAGS) -c SockHop.c


###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
HttpProxy.o: HttpProxy.c ProxyUtil.h SockHop.h \
  ../../include/ccn/fetch.h \
  ../../include/ccn/ccn.h  ../../include/ccn/charbuf.h  ../../include/ccn/uri.h
NetFetch.o: NetFetch.c ProxyUtil.h SockHop.h  \
  ../../include/ccn/fetch.h \
  ../../include/ccn/ccn.h  ../../include/ccn/charbuf.h  ../../include/ccn/uri.h \
  ../../include/ccn/keystore.h  ../../include/ccn/signing.h
ProxyUtil.o: ProxyUtil.c ProxyUtil.h 
SockHop.o: SockHop.c SockHop.h
