# sync/dir.mk
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

CCNLIBDIR = ../lib
LDLIBS = -L$(CCNLIBDIR) $(MORE_LDLIBS) -lccn -L. -lccnsync

INSTALLED_PROGRAMS = 

PROGRAMS = $(INSTALLED_PROGRAMS) \
    SyncTest

BROKEN_PROGRAMS =
DEBRIS =
SCRIPTSRC = 
CSRC = \
 IndexSorter.c \
 SyncActions.c \
 SyncBase.c \
 SyncHashCache.c \
 SyncNode.c \
 SyncRoot.c \
 SyncTest.c \
 SyncTreeWorker.c \
 SyncUtil.c \
 UpdateSketch.c \
 sync_api.c \
 sync_diff.c

HSRC = IndexSorter.h SyncActions.h SyncBase.h SyncHashCache.h SyncMacros.h \
       SyncNode.h SyncPrivate.h SyncRoot.h SyncTreeWorker.h SyncUtil.h \
       sync_plumbing.h sync_diff.h 

LIBS = libccnsync.a
LIB_OBJS = IndexSorter.o SyncBase.o SyncHashCache.o SyncNode.o SyncRoot.o \
       SyncTreeWorker.o SyncUtil.o SyncActions.o sync_diff.o sync_api.o

default all: $(PROGRAMS) $(LIBS)
all: $(BROKEN_PROGRAMS)

test: default

$(PROGRAMS): $(CCNLIBDIR)/libccn.a $(LIBS)

lib:	$(LIBS)

libccnsync.a:	$(LIB_OBJS)
	$(RM) $@
	$(AR) crus $@ $(LIB_OBJS)

SyncTest_OBJ = SyncTest.o
SyncTest: $(SyncTest_OBJ)
	$(CC) $(CFLAGS) -o $@ $(SyncTest_OBJ) $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

clean:
	$(RM) *.o *.a $(PROGRAMS) $(BROKEN_PROGRAMS) depend
	$(RM) -r *.dSYM *.gcov *.gcda *.gcno $(DEBRIS)

check test: SyncTest $(SCRIPTSRC)
	@echo No sync_exp tests hooked up yet.
	: -------------------------- :
	:  sync_exp unit tests pass  :
	: -------------------------- :
