# ccnd/dir.mk
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

INSTALLED_PROGRAMS = ccnd ccndsmoketest ccnd-init-keystore-helper
PROGRAMS = $(INSTALLED_PROGRAMS)
DEBRIS = anything.ccnb contentobjecthash.ccnb contentmishash.ccnb \
         contenthash.ccnb

BROKEN_PROGRAMS = 
CSRC = ccnd_main.c ccnd.c ccnd_msg.c ccnd_stats.c ccnd_internal_client.c ccndsmoketest.c
HSRC = ccnd_private.h
SCRIPTSRC = testbasics fortunes.ccnb contentobjecthash.ref anything.ref \
            ccnd-init-keystore-helper.sh minsuffix.ref
 
default: $(PROGRAMS)

all: default $(BROKEN_PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn.a

CCND_OBJ = ccnd_main.o ccnd.o ccnd_msg.o ccnd_stats.o ccnd_internal_client.o
ccnd: $(CCND_OBJ) ccnd_built.sh
	$(CC) $(CFLAGS) -o $@ $(CCND_OBJ) $(LDLIBS) $(OPENSSL_LIBS) -lcrypto
	sh ./ccnd_built.sh

ccnd_built.sh:
	touch ccnd_built.sh

ccnd-init-keystore-helper: ccnd-init-keystore-helper.sh
	sed -e 's@/bin/sh@'`which sh`'@g' ccnd-init-keystore-helper.sh > $@
	chmod +x $@

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

###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
ccnd_main.o: ccnd_main.c ccnd_private.h ../include/ccn/ccn_private.h \
  ../include/ccn/coding.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/charbuf.h ../include/ccn/schedule.h \
  ../include/ccn/seqwriter.h
ccnd.o: ccnd.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/ccnd.h ../include/ccn/face_mgmt.h \
  ../include/ccn/sockcreate.h ../include/ccn/hashtb.h \
  ../include/ccn/schedule.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/uri.h ccnd_private.h ../include/ccn/seqwriter.h
ccnd_msg.o: ccnd_msg.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h ../include/ccn/uri.h ccnd_private.h \
  ../include/ccn/ccn_private.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/schedule.h ../include/ccn/seqwriter.h
ccnd_stats.o: ccnd_stats.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h ../include/ccn/schedule.h \
  ../include/ccn/sockaddrutil.h ../include/ccn/hashtb.h \
  ../include/ccn/uri.h ccnd_private.h ../include/ccn/ccn_private.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/seqwriter.h
ccnd_internal_client.o: ccnd_internal_client.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/schedule.h ../include/ccn/sockaddrutil.h \
  ../include/ccn/uri.h ccnd_private.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/seqwriter.h
ccndsmoketest.o: ccndsmoketest.c ../include/ccn/ccnd.h \
  ../include/ccn/ccn_private.h
