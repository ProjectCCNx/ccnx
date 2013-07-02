# ccnd/dir.mk
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

INSTALLED_PROGRAMS = ccnd ccndsmoketest 
PROGRAMS = $(INSTALLED_PROGRAMS)
DEBRIS = anything.ccnb contentobjecthash.ccnb contentmishash.ccnb \
         contenthash.ccnb

BROKEN_PROGRAMS = 
CSRC = ccnd_main.c ccnd.c ccnd_msg.c ccnd_stats.c ccnd_internal_client.c ccndsmoketest.c
HSRC = ccnd_private.h
SCRIPTSRC = testbasics fortunes.ccnb contentobjecthash.ref anything.ref \
            minsuffix.ref
 
default: $(PROGRAMS)

all: default $(BROKEN_PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn.a

CCND_OBJ = ccnd_main.o ccnd.o ccnd_msg.o ccnd_stats.o ccnd_internal_client.o
ccnd: $(CCND_OBJ) ccnd_built.sh
	$(CC) $(CFLAGS) -o $@ $(CCND_OBJ) $(LDLIBS) $(OPENSSL_LIBS) -lcrypto
	sh ./ccnd_built.sh

ccnd_built.sh:
	touch ccnd_built.sh

ccndsmoketest: ccndsmoketest.o
	$(CC) $(CFLAGS) -o $@ ccndsmoketest.o $(LDLIBS)

clean:
	rm -f *.o *.a $(PROGRAMS) $(BROKEN_PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

check test: ccnd ccndsmoketest $(SCRIPTSRC)
	./testbasics
	: ---------------------- :
	:  ccnd unit tests pass  :
	: ---------------------- :
