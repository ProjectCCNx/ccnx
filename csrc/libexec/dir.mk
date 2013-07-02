# libexec/dir.mk
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009-2013 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

LDLIBS = -L$(CCNLIBDIR) $(MORE_LDLIBS) -lccn
CCNLIBDIR = ../lib

INSTALLED_PROGRAMS = ccndc
DEBRIS = ccndc-inject
PROGRAMS = $(INSTALLED_PROGRAMS) udplink
CSRC = ccndc-log.c ccndc-main.c ccndc-srv.c ccndc.c udplink.c
HSRC = ccndc-log.h ccndc-srv.h ccndc.h

default all: $(PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn.a

ccndc: ccndc-log.o ccndc-srv.o ccndc.o ccndc-main.o
	$(CC) $(CFLAGS) -o $@ ccndc-log.o ccndc-srv.o ccndc.o ccndc-main.o $(LDLIBS) $(OPENSSL_LIBS) $(RESOLV_LIBS) -lcrypto

udplink: udplink.o
	$(CC) $(CFLAGS) -o $@ udplink.o $(LDLIBS)  $(OPENSSL_LIBS) -lcrypto

clean:
	rm -f *.o *.a $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

test:
	@echo "Sorry, no libexec unit tests at this time"

