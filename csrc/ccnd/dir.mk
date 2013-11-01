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
         contenthash.ccnb ccnd_stregistry.h

BROKEN_PROGRAMS = 
CSRC = ccnd_main.c \
       ccnd.c ccnd_msg.c ccnd_stats.c ccnd_internal_client.c ccnd_stregistry.c \
       $(STRATEGYSRC) \
       ccndsmoketest.c
HSRC = ccnd_private.h ccnd_strategy.h
SCRIPTSRC = testbasics fortunes.ccnb contentobjecthash.ref anything.ref \
            minsuffix.ref gen_stregistry.sh

# To add a strategy, list its source here, and then make depend.
STRATEGYSRC = default_strategy.c null_strategy.c trace_strategy.c \
              parallel_strategy.c loadsharing_strategy.c \
              faceattr_strategy.c

default: $(PROGRAMS)

all: default $(BROKEN_PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn.a

# Leave main out of this list to make it easier to support the android build
CCND_OBJ = ccnd.o ccnd_msg.o ccnd_stats.o ccnd_internal_client.o ccnd_stregistry.o \
	$(STRATEGYSRC:.c=.o)

ccnd: ccnd_main.o $(CCND_OBJ) ccnd_built.sh
	$(CC) $(CFLAGS) -o $@ ccnd_main.o $(CCND_OBJ) $(LDLIBS) $(OPENSSL_LIBS) -lcrypto
	$(SH) ./ccnd_built.sh

ccnd_built.sh:
	touch ccnd_built.sh

ccndsmoketest: ccndsmoketest.o
	$(CC) $(CFLAGS) -o $@ ccndsmoketest.o $(LDLIBS)

ccnd_stregistry.h: gen_stregistry.sh $(CSRC)
	$(SH) gen_stregistry.sh $(CSRC)

depend: ccnd_stregistry.h android_obj.mk
android_obj.mk: _always
	echo 'CCNDOBJ := \' > templist
	for i in android_main.o $(CCND_OBJ); \
		do echo "    $$i" '\'; done | sort -u >> templist
	echo >> templist
	diff -b templist android_obj.mk || mv templist android_obj.mk
	$(RM) templist

clean:
	rm -f *.o *.a $(PROGRAMS) $(BROKEN_PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

check test: ccnd ccndsmoketest $(SCRIPTSRC)
	./testbasics
	: ---------------------- :
	:  ccnd unit tests pass  :
	: ---------------------- :
