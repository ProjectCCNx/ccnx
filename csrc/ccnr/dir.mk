# ccnr/dir.mk
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2011 Palo Alto Research Center, Inc.
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

INSTALLED_PROGRAMS = ccnr
PROGRAMS = $(INSTALLED_PROGRAMS)
DEBRIS = 

BROKEN_PROGRAMS = 
CSRC = ccnr_dispatch.c ccnr_forwarding.c ccnr_init.c ccnr_internal_client.c ccnr_io.c ccnr_link.c ccnr_main.c ccnr_match.c ccnr_msg.c ccnr_net.c ccnr_sendq.c ccnr_stats.c ccnr_store.c ccnr_util.c
HSRC = ccnr_private.h
SCRIPTSRC = 
 
default: $(PROGRAMS)

all: default $(BROKEN_PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn.a

CCNR_OBJ = ccnr_dispatch.o ccnr_forwarding.o ccnr_init.o ccnr_internal_client.o ccnr_io.o ccnr_link.o ccnr_main.o ccnr_match.o ccnr_msg.o ccnr_net.o ccnr_sendq.o ccnr_stats.o ccnr_store.o ccnr_util.o
ccnr: $(CCNR_OBJ) ccnr_built.sh
	$(CC) $(CFLAGS) -o $@ $(CCNR_OBJ) $(LDLIBS) $(OPENSSL_LIBS) -lcrypto
	sh ./ccnr_built.sh

ccnr_built.sh:
	touch ccnr_built.sh

clean:
	rm -f *.o *.a $(PROGRAMS) $(BROKEN_PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

check test: ccnr $(SCRIPTSRC)
	false
	: ---------------------- :
	:  ccnr unit tests pass  :
	: ---------------------- :

###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
ccnr_dispatch.o: ccnr_dispatch.c common.h ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h
ccnr_forwarding.o: ccnr_forwarding.c common.h ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h
ccnr_init.o: ccnr_init.c common.h ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h
ccnr_internal_client.o: ccnr_internal_client.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/schedule.h ../include/ccn/sockaddrutil.h \
  ../include/ccn/uri.h ccnr_private.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/seqwriter.h
ccnr_io.o: ccnr_io.c common.h ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h
ccnr_link.o: ccnr_link.c common.h ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h
ccnr_main.o: ccnr_main.c ccnr_private.h ../include/ccn/ccn_private.h \
  ../include/ccn/coding.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/charbuf.h ../include/ccn/schedule.h \
  ../include/ccn/seqwriter.h
ccnr_match.o: ccnr_match.c common.h ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h
ccnr_msg.o: ccnr_msg.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h ../include/ccn/uri.h \
  ccnr_private.h ../include/ccn/ccn_private.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/schedule.h ../include/ccn/seqwriter.h
ccnr_net.o: ccnr_net.c common.h ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h
ccnr_sendq.o: ccnr_sendq.c common.h ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h
ccnr_stats.o: ccnr_stats.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/schedule.h ../include/ccn/sockaddrutil.h \
  ../include/ccn/hashtb.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/ccn_private.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/seqwriter.h
ccnr_store.o: ccnr_store.c common.h ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h
ccnr_util.o: ccnr_util.c common.h ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h
