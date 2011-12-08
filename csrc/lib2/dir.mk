# lib2/dir.mk
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
SYNCLIBDIR = ../sync/
# Override conf.mk or else we don't pick up all the includes
CPREFLAGS = -I../include -I..

INSTALLED_PROGRAMS = 
PROGRAMS = ccnbtreetest ccnfilewatch
DEBRIS = 

BROKEN_PROGRAMS = 
CSRC = ccn_btree.c ccn_btree_content.c ccn_btree_store.c ccnbtreetest.c
HSRC = 
SCRIPTSRC = 
 
default: $(PROGRAMS)

all: default $(BROKEN_PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn.a

LIB2_OBJ = ccn_btree.o ccn_btree_store.o ccn_btree_content.o
ccnbtreetest: $(LIB2_OBJ) ccnbtreetest.c
	$(CC) $(CFLAGS) -o $@ -Dccnbtreetest_main=main ccnbtreetest.c $(LIB2_OBJ) $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

clean:
	rm -f *.o *.a $(PROGRAMS) $(BROKEN_PROGRAMS) depend
	rm -rf *.dSYM *.gcov *.gcda *.gcno $(DEBRIS)
	rm -rf _bt_*

check test: ccnbtreetest $(SCRIPTSRC)
	./ccnbtreetest
	: ---------------------- :
	:  lib2 unit tests pass  :
	: ---------------------- :

###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
ccn_btree.o: ccn_btree.c ../include/ccn/charbuf.h ../include/ccn/hashtb.h \
  ../ccn/btree.h
ccn_btree_content.o: ccn_btree_content.c ../ccn/btree.h \
  ../include/ccn/charbuf.h ../include/ccn/hashtb.h ../ccn/btree_content.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/indexbuf.h \
  ../include/ccn/bloom.h ../include/ccn/uri.h
ccn_btree_store.o: ccn_btree_store.c ../ccn/btree.h \
  ../include/ccn/charbuf.h ../include/ccn/hashtb.h
ccnbtreetest.o: ccnbtreetest.c ../ccn/btree.h ../include/ccn/charbuf.h \
  ../include/ccn/hashtb.h ../ccn/btree_content.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/indexbuf.h ../include/ccn/uri.h
