# cmd/dir.mk
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
EXPATLIBS = -lexpat
CCNLIBDIR = ../lib
SYNCLIBS = -L../sync -lccnsync

INSTALLED_PROGRAMS = \
    ccn_ccnbtoxml ccn_splitccnb ccnc ccndumpnames ccnnamelist ccnrm \
    ccnls ccnslurp ccnbx ccncat ccnbasicconfig \
    ccnsendchunks ccncatchunks ccncatchunks2 \
    ccnpoke ccnpeek ccnhexdumpdata \
    ccnseqwriter ccnsimplecat \
    ccnfilewatch \
    ccnguestprefix \
    ccninitkeystore \
    ccninitaeskeystore \
    ccnlibtest \
    ccnsyncwatch ccnsyncslice \
    $(EXPAT_PROGRAMS) $(PCAP_PROGRAMS)

PROGRAMS = $(INSTALLED_PROGRAMS) \
    ccnbuzz  \
    dataresponsetest \
    ccn_fetch_test \
    ccnsnew \
    ccninitaeskeystore \
   $(PCAP_PROGRAMS)

EXPAT_PROGRAMS = ccn_xmltoccnb
#PCAP_PROGRAMS = ccndumppcap
BROKEN_PROGRAMS =
DEBRIS =
SCRIPTSRC = ccn_initkeystore.sh
CSRC =  ccn_ccnbtoxml.c ccn_splitccnb.c ccn_xmltoccnb.c ccnbasicconfig.c \
       ccnbuzz.c ccnbx.c \
       ccnc.c \
       ccncat.c ccnsimplecat.c ccncatchunks.c ccncatchunks2.c \
       ccndumpnames.c ccndumppcap.c ccnfilewatch.c \
       ccnguestprefix.c ccnpeek.c ccnhexdumpdata.c \
       ccninitkeystore.c ccnls.c ccnnamelist.c ccnpoke.c ccnrm.c ccnsendchunks.c \
       ccnseqwriter.c \
       ccnsnew.c \
       ccnsyncwatch.c ccnsyncslice.c ccn_fetch_test.c ccnlibtest.c ccnslurp.c dataresponsetest.c \
       ccninitaeskeystore.c

default all: $(PROGRAMS)
# Don't try to build broken programs right now.
# all: $(BROKEN_PROGRAMS)

test: default

$(PROGRAMS): $(CCNLIBDIR)/libccn.a

ccn_ccnbtoxml: ccn_ccnbtoxml.o
	$(CC) $(CFLAGS) -o $@ ccn_ccnbtoxml.o $(LDLIBS)

ccn_xmltoccnb: ccn_xmltoccnb.o
	$(CC) $(CFLAGS) -o $@ ccn_xmltoccnb.o $(LDLIBS) $(EXPATLIBS)

ccn_splitccnb: ccn_splitccnb.o
	$(CC) $(CFLAGS) -o $@ ccn_splitccnb.o $(LDLIBS)

hashtbtest: hashtbtest.o
	$(CC) $(CFLAGS) -o $@ hashtbtest.o $(LDLIBS)

matrixtest: matrixtest.o
	$(CC) $(CFLAGS) -o $@ matrixtest.o $(LDLIBS)

skel_decode_test: skel_decode_test.o
	$(CC) $(CFLAGS) -o $@ skel_decode_test.o $(LDLIBS)

ccnlibtest: ccnlibtest.o
	$(CC) $(CFLAGS) -o $@ ccnlibtest.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

dataresponsetest: dataresponsetest.o
	$(CC) $(CFLAGS) -o $@ dataresponsetest.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

encodedecodetest: encodedecodetest.o
	$(CC) $(CFLAGS) -o $@ encodedecodetest.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnc: ccnc.o
	$(CC) $(CFLAGS) -o $@ ccnc.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnguestprefix: ccnguestprefix.o
	$(CC) $(CFLAGS) -o $@ ccnguestprefix.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccndumpnames: ccndumpnames.o
	$(CC) $(CFLAGS) -o $@ ccndumpnames.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnls: ccnls.o
	$(CC) $(CFLAGS) -o $@ ccnls.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnnamelist: ccnnamelist.o
	$(CC) $(CFLAGS) -o $@ ccnnamelist.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnrm: ccnrm.o
	$(CC) $(CFLAGS) -o $@ ccnrm.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnslurp: ccnslurp.o
	$(CC) $(CFLAGS) -o $@ ccnslurp.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnbx: ccnbx.o
	$(CC) $(CFLAGS) -o $@ ccnbx.o $(LDLIBS)   $(OPENSSL_LIBS) -lcrypto

ccncat: ccncat.o
	$(CC) $(CFLAGS) -o $@ ccncat.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnsimplecat: ccnsimplecat.o
	$(CC) $(CFLAGS) -o $@ ccnsimplecat.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnsendchunks: ccnsendchunks.o
	$(CC) $(CFLAGS) -o $@ ccnsendchunks.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnseqwriter: ccnseqwriter.o
	$(CC) $(CFLAGS) -o $@ ccnseqwriter.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccn_fetch_test: ccn_fetch_test.o
	$(CC) $(CFLAGS) -o $@ ccn_fetch_test.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccncatchunks: ccncatchunks.o
	$(CC) $(CFLAGS) -o $@ ccncatchunks.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccncatchunks2: ccncatchunks2.o
	$(CC) $(CFLAGS) -o $@ ccncatchunks2.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnbasicconfig: ccnbasicconfig.o
	$(CC) $(CFLAGS) -o $@ ccnbasicconfig.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnbuzz: ccnbuzz.o
	$(CC) $(CFLAGS) -o $@ ccnbuzz.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnpoke: ccnpoke.o
	$(CC) $(CFLAGS) -o $@ ccnpoke.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnpeek: ccnpeek.o
	$(CC) $(CFLAGS) -o $@ ccnpeek.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnhexdumpdata: ccnhexdumpdata.o
	$(CC) $(CFLAGS) -o $@ ccnhexdumpdata.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccninitkeystore: ccninitkeystore.o
	$(CC) $(CFLAGS) -o $@ ccninitkeystore.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccninitaeskeystore: ccninitaeskeystore.o
	$(CC) $(CFLAGS) -o $@ ccninitaeskeystore.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccn_digest.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_digest.c

ccn_keystore.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_keystore.c

ccn_signing.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_signing.c

ccn_merkle_path_asn1.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_merkle_path_asn1.c

ccn_verifysig.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_verifysig.c

ccn_verifysig: ccn_verifysig.o
	$(CC) $(CFLAGS) -o $@ ccn_verifysig.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

signbenchtest.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c signbenchtest.c

signbenchtest: signbenchtest.o
	$(CC) $(CFLAGS) -o $@ signbenchtest.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto 

ccndumppcap: ccndumppcap.o
	$(CC) $(CFLAGS) -o $@ ccndumppcap.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto -lpcap

ccnfilewatch: ccnfilewatch.o
	$(CC) $(CFLAGS) -o $@ ccnfilewatch.o

ccnsnew: ccnsnew.o
	$(CC) $(CFLAGS) -o $@ ccnsnew.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnsyncwatch ccnsyncslice: ../sync/libccnsync.a

ccnsyncwatch: ccnsyncwatch.o 
	$(CC) $(CFLAGS) -o $@ ccnsyncwatch.o $(SYNCLIBS) $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnsyncslice: ccnsyncslice.o
	$(CC) $(CFLAGS) -o $@ ccnsyncslice.o $(SYNCLIBS) $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

clean:
	rm -f *.o libccn.a libccn.1.$(SHEXT) $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS) *% *~
