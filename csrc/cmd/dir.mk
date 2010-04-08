# cmd/dir.mk
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

LDLIBS = -L../lib $(MORE_LDLIBS) -lccn
EXPATLIBS = -lexpat
CCNLIBDIR = ../lib

INSTALLED_PROGRAMS = \
    ccn_ccnbtoxml ccn_splitccnb ccndumpnames ccnrm \
    ccnls ccnslurp ccnbx ccncat ccnbasicconfig \
    ccnsendchunks ccncatchunks ccncatchunks2 \
    ccnput ccnget ccnhexdumpdata $(EXPAT_PROGRAMS) $(PCAP_PROGRAMS)

PROGRAMS = $(INSTALLED_PROGRAMS) \
    ccnbuzz  \
    dataresponsetest \
    ccnseqwriter \
    $(PCAP_PROGRAMS)

EXPAT_PROGRAMS = ccn_xmltoccnb
#PCAP_PROGRAMS = ccndumppcap
BROKEN_PROGRAMS =
DEBRIS =
SCRIPTSRC = ccn_initkeystore.sh
CSRC =  ccn_ccnbtoxml.c ccn_splitccnb.c ccn_xmltoccnb.c ccnbasicconfig.c \
       ccnbuzz.c ccnbx.c ccncat.c ccncatchunks.c ccncatchunks2.c \
       ccndumpnames.c ccndumppcap.c ccnget.c ccnhexdumpdata.c \
       ccnls.c ccnput.c ccnrm.c ccnsendchunks.c ccnseqwriter.c \
       ccnslurp.c dataresponsetest.c 

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

smoketestclientlib: smoketestclientlib.o
	$(CC) $(CFLAGS) -o $@ smoketestclientlib.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

dataresponsetest: dataresponsetest.o
	$(CC) $(CFLAGS) -o $@ dataresponsetest.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

encodedecodetest: encodedecodetest.o
	$(CC) $(CFLAGS) -o $@ encodedecodetest.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccndumpnames: ccndumpnames.o
	$(CC) $(CFLAGS) -o $@ ccndumpnames.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnls: ccnls.o
	$(CC) $(CFLAGS) -o $@ ccnls.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnrm: ccnrm.o
	$(CC) $(CFLAGS) -o $@ ccnrm.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnslurp: ccnslurp.o
	$(CC) $(CFLAGS) -o $@ ccnslurp.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnbx: ccnbx.o
	$(CC) $(CFLAGS) -o $@ ccnbx.o $(LDLIBS)   $(OPENSSL_LIBS) -lcrypto

ccncat: ccncat.o
	$(CC) $(CFLAGS) -o $@ ccncat.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnsendchunks: ccnsendchunks.o
	$(CC) $(CFLAGS) -o $@ ccnsendchunks.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnseqwriter: ccnseqwriter.o
	$(CC) $(CFLAGS) -o $@ ccnseqwriter.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccncatchunks: ccncatchunks.o
	$(CC) $(CFLAGS) -o $@ ccncatchunks.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccncatchunks2: ccncatchunks2.o
	$(CC) $(CFLAGS) -o $@ ccncatchunks2.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnbasicconfig: ccnbasicconfig.o
	$(CC) $(CFLAGS) -o $@ ccnbasicconfig.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnbuzz: ccnbuzz.o
	$(CC) $(CFLAGS) -o $@ ccnbuzz.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnput: ccnput.o
	$(CC) $(CFLAGS) -o $@ ccnput.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnget: ccnget.o
	$(CC) $(CFLAGS) -o $@ ccnget.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnhexdumpdata: ccnhexdumpdata.o
	$(CC) $(CFLAGS) -o $@ ccnhexdumpdata.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

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

clean:
	rm -f *.o libccn.a libccn.1.$(SHEXT) $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS) *% *~

###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
ccn_ccnbtoxml.o: ccn_ccnbtoxml.c ../include/ccn/charbuf.h \
  ../include/ccn/coding.h ../include/ccn/extend_dict.h
ccn_splitccnb.o: ccn_splitccnb.c ../include/ccn/coding.h
ccn_xmltoccnb.o: ccn_xmltoccnb.c ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/extend_dict.h
ccnbasicconfig.o: ccnbasicconfig.c ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccnd.h ../include/ccn/uri.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/signing.h \
  ../include/ccn/keystore.h
ccnbuzz.o: ccnbuzz.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccnbx.o: ccnbx.c ../include/ccn/charbuf.h ../include/ccn/coding.h \
  ../include/ccn/ccn.h ../include/ccn/indexbuf.h
ccncat.o: ccncat.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccncatchunks.o: ccncatchunks.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccncatchunks2.o: ccncatchunks2.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/schedule.h \
  ../include/ccn/uri.h
ccndumpnames.o: ccndumpnames.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccndumppcap.o: ccndumppcap.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h
ccnget.o: ccnget.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccnhexdumpdata.o: ccnhexdumpdata.c ../include/ccn/coding.h \
  ../include/ccn/ccn.h ../include/ccn/charbuf.h ../include/ccn/indexbuf.h
ccnls.o: ccnls.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccnput.o: ccnput.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h ../include/ccn/uri.h \
  ../include/ccn/keystore.h ../include/ccn/signing.h
ccnrm.o: ccnrm.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccnsendchunks.o: ccnsendchunks.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h \
  ../include/ccn/keystore.h ../include/ccn/signing.h
ccnseqwriter.o: ccnseqwriter.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h \
  ../include/ccn/seqwriter.h
ccnslurp.o: ccnslurp.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
dataresponsetest.o: dataresponsetest.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
