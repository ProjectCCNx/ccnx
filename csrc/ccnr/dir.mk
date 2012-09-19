# ccnr/dir.mk
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2012 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

LDLIBS = -L$(CCNLIBDIR) -L$(SYNCLIBDIR) $(MORE_LDLIBS) -lccnsync -lccn
CCNLIBDIR = ../lib
SYNCLIBDIR = ../sync
# Override conf.mk or else we don't pick up all the includes
CPREFLAGS = -I../include -I..

INSTALLED_PROGRAMS = ccnr
PROGRAMS = $(INSTALLED_PROGRAMS)
DEBRIS = 

BROKEN_PROGRAMS = 
CSRC = ccnr_dispatch.c ccnr_forwarding.c ccnr_init.c ccnr_internal_client.c ccnr_io.c ccnr_link.c ccnr_main.c ccnr_match.c ccnr_msg.c ccnr_net.c ccnr_proto.c ccnr_sendq.c ccnr_stats.c ccnr_store.c ccnr_sync.c ccnr_util.c
HSRC = ccnr_dispatch.h ccnr_forwarding.h ccnr_init.h ccnr_internal_client.h        \
       ccnr_io.h ccnr_link.h ccnr_match.h ccnr_msg.h ccnr_net.h ccnr_private.h     \
       ccnr_proto.h ccnr_sendq.h ccnr_stats.h ccnr_store.h ccnr_sync.h ccnr_util.h

SCRIPTSRC = 

default: $(PROGRAMS)

all: default $(BROKEN_PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn.a $(SYNCLIBDIR)/libccnsync.a

CCNR_OBJ = ccnr_dispatch.o ccnr_forwarding.o ccnr_init.o ccnr_internal_client.o ccnr_io.o ccnr_link.o ccnr_main.o ccnr_match.o ccnr_msg.o ccnr_net.o ccnr_proto.o ccnr_sendq.o ccnr_stats.o ccnr_store.o ccnr_sync.o ccnr_util.o

ccnr: $(CCNR_OBJ)
	$(CC) $(CFLAGS) -o $@ $(CCNR_OBJ) $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

clean:
	rm -f *.o *.a $(PROGRAMS) $(BROKEN_PROGRAMS) depend
	rm -rf *.dSYM *.gcov *.gcda *.gcno $(DEBRIS)

check test: ccnr $(SCRIPTSRC)
	: ---------------------- :
	:  ccnr unit tests pass  :
	: ---------------------- :

###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
ccnr_dispatch.o: ccnr_dispatch.c ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ../sync/SyncBase.h \
  ../include/ccn/loglevels.h ../sync/sync_plumbing.h ccnr_private.h \
  ../include/ccn/seqwriter.h ccnr_dispatch.h ccnr_forwarding.h ccnr_io.h \
  ccnr_link.h ccnr_match.h ccnr_msg.h ccnr_proto.h ccnr_sendq.h \
  ccnr_stats.h ccnr_store.h ccnr_sync.h ccnr_util.h
ccnr_forwarding.o: ccnr_forwarding.c ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h ccnr_forwarding.h ccnr_io.h ccnr_link.h \
  ccnr_match.h ccnr_msg.h ../include/ccn/loglevels.h ccnr_stats.h \
  ccnr_util.h
ccnr_init.o: ccnr_init.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ../sync/sync_plumbing.h \
  ../sync/SyncActions.h ../sync/SyncBase.h ../include/ccn/loglevels.h \
  ../sync/sync_plumbing.h ../sync/SyncRoot.h ../sync/SyncUtil.h \
  ../sync/IndexSorter.h ccnr_private.h ../include/ccn/seqwriter.h \
  ccnr_init.h ccnr_dispatch.h ccnr_forwarding.h ccnr_internal_client.h \
  ccnr_io.h ccnr_msg.h ccnr_net.h ccnr_proto.h ccnr_sendq.h ccnr_store.h \
  ccnr_sync.h ccnr_util.h
ccnr_internal_client.o: ccnr_internal_client.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/schedule.h ../include/ccn/sockaddrutil.h \
  ../include/ccn/uri.h ../include/ccn/keystore.h ccnr_private.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/seqwriter.h \
  ccnr_internal_client.h ccnr_forwarding.h ../include/ccn/hashtb.h \
  ccnr_io.h ccnr_msg.h ../include/ccn/loglevels.h ccnr_proto.h \
  ccnr_util.h
ccnr_io.o: ccnr_io.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h ccnr_io.h ccnr_forwarding.h \
  ccnr_internal_client.h ccnr_link.h ccnr_msg.h \
  ../include/ccn/loglevels.h ccnr_sendq.h ccnr_stats.h
ccnr_link.o: ccnr_link.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h ccnr_link.h ccnr_forwarding.h \
  ccnr_internal_client.h ccnr_io.h ccnr_match.h ccnr_msg.h \
  ../include/ccn/loglevels.h ccnr_sendq.h ccnr_stats.h ccnr_store.h \
  ccnr_util.h
ccnr_main.o: ccnr_main.c ccnr_private.h ../include/ccn/ccn_private.h \
  ../include/ccn/coding.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/charbuf.h ../include/ccn/schedule.h \
  ../include/ccn/seqwriter.h ccnr_init.h ccnr_dispatch.h ccnr_msg.h \
  ../include/ccn/loglevels.h ccnr_stats.h
ccnr_match.o: ccnr_match.c ../include/ccn/bloom.h \
  ../include/ccn/btree_content.h ../include/ccn/btree.h \
  ../include/ccn/charbuf.h ../include/ccn/hashtb.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccn_private.h ../include/ccn/face_mgmt.h \
  ../include/ccn/sockcreate.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h ccnr_match.h ccnr_forwarding.h ccnr_io.h \
  ccnr_msg.h ../include/ccn/loglevels.h ccnr_sendq.h ccnr_store.h
ccnr_msg.o: ccnr_msg.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h ../include/ccn/uri.h \
  ccnr_private.h ../include/ccn/ccn_private.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/schedule.h ../include/ccn/seqwriter.h ccnr_msg.h \
  ../include/ccn/loglevels.h
ccnr_net.o: ccnr_net.c ccnr_private.h ../include/ccn/ccn_private.h \
  ../include/ccn/coding.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/charbuf.h ../include/ccn/schedule.h \
  ../include/ccn/seqwriter.h ccnr_net.h ccnr_io.h ccnr_msg.h \
  ../include/ccn/loglevels.h
ccnr_proto.o: ccnr_proto.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccn_private.h ../include/ccn/hashtb.h \
  ../include/ccn/schedule.h ../include/ccn/sockaddrutil.h \
  ../include/ccn/uri.h ../sync/SyncBase.h ../include/ccn/loglevels.h \
  ../sync/sync_plumbing.h ccnr_private.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/seqwriter.h ccnr_proto.h ccnr_dispatch.h \
  ccnr_forwarding.h ccnr_init.h ccnr_io.h ccnr_msg.h ccnr_sendq.h \
  ccnr_store.h ccnr_sync.h ccnr_util.h
ccnr_sendq.o: ccnr_sendq.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h ccnr_sendq.h ccnr_io.h ccnr_link.h \
  ccnr_msg.h ../include/ccn/loglevels.h ccnr_store.h
ccnr_stats.o: ccnr_stats.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/schedule.h ../include/ccn/sockaddrutil.h \
  ../include/ccn/hashtb.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/ccn_private.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/seqwriter.h ccnr_stats.h ccnr_io.h ccnr_msg.h \
  ../include/ccn/loglevels.h
ccnr_store.o: ccnr_store.c ../include/ccn/bloom.h \
  ../include/ccn/btree_content.h ../include/ccn/btree.h \
  ../include/ccn/charbuf.h ../include/ccn/hashtb.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccn_private.h ../include/ccn/face_mgmt.h \
  ../include/ccn/sockcreate.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h ccnr_stats.h ccnr_store.h ccnr_init.h \
  ccnr_link.h ccnr_util.h ccnr_proto.h ccnr_msg.h \
  ../include/ccn/loglevels.h ccnr_sync.h ccnr_match.h ccnr_sendq.h \
  ccnr_io.h
ccnr_sync.o: ccnr_sync.c ../include/ccn/btree.h ../include/ccn/charbuf.h \
  ../include/ccn/hashtb.h ../include/ccn/btree_content.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/indexbuf.h \
  ../include/ccn/schedule.h ../sync/SyncBase.h ../include/ccn/loglevels.h \
  ../sync/sync_plumbing.h ccnr_private.h ../include/ccn/ccn_private.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/seqwriter.h ccnr_dispatch.h \
  ccnr_io.h ccnr_link.h ccnr_msg.h ccnr_proto.h ccnr_store.h ccnr_sync.h \
  ccnr_util.h ../sync/sync_plumbing.h
ccnr_util.o: ccnr_util.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h ccnr_util.h
