# ccnr/dir.mk
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2012-2013 Palo Alto Research Center, Inc.
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
CDIRFLAGS = -I..

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
