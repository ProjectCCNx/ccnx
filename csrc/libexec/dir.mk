# libexec/dir.mk
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009 Palo Alto Research Center, Inc.
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
CSRC = ccndc.c udplink.c

default all: $(PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn.a

ccndc: ccndc.o
	$(CC) $(CFLAGS) -o $@ ccndc.o $(LDLIBS) $(OPENSSL_LIBS) $(RESOLV_LIBS) -lcrypto

udplink: udplink.o
	$(CC) $(CFLAGS) -o $@ udplink.o $(LDLIBS)  $(OPENSSL_LIBS) -lcrypto

clean:
	rm -f *.o *.a $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

test:
	@echo "Sorry, no libexec unit tests at this time"

###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
ccndc.o: ccndc.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccnd.h ../include/ccn/uri.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/signing.h
udplink.o: udplink.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h
