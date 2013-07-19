# apps/HttpProxy/dir.mk
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2013 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

LDLIBS = -L$(CCNLIBDIR) $(MORE_LDLIBS) $(OPENSSL_LIBS) -lccn -lcrypto
CCNLIBDIR = ../../csrc/lib
# Do not install these yet - we should choose names more appropriate for
# a flat namespace in /usr/local/bin
# INSTALLED_PROGRAMS = NetFetch HttpProxy
PROGRAMS = NetFetch HttpProxy

CSRC = HttpProxy.c NetFetch.c ProxyUtil.c SockHop.c

default all: $(PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn.a

HttpProxy: HttpProxy.o ProxyUtil.o SockHop.o
	$(CC) $(CFLAGS) -o $@ HttpProxy.o ProxyUtil.o SockHop.o $(LDLIBS)

NetFetch: NetFetch.o ProxyUtil.o SockHop.o
	$(CC) $(CFLAGS) -o $@ NetFetch.o ProxyUtil.o SockHop.o $(LDLIBS)

clean:
	rm -f *.o $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS) *% *~

test: default

