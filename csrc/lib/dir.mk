# lib/dir.mk
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

PROGRAMS = hashtbtest skel_decode_test \
    encodedecodetest signbenchtest basicparsetest ccnbtreetest nametreetest

BROKEN_PROGRAMS =

DEBRIS = ccn_verifysig _bt_* test.aeskeystore test.keystore

CSRC = \
    basicparsetest.c \
    ccn_aes_keystore.c \
    ccn_aes_keystore_asn1.c \
    ccn_bloom.c \
    ccn_btree.c \
    ccn_btree_content.c \
    ccn_btree_store.c \
    ccn_buf_decoder.c \
    ccn_buf_encoder.c \
    ccn_bulkdata.c \
    ccn_charbuf.c \
    ccn_client.c \
    ccn_coding.c \
    ccn_digest.c \
    ccn_dtag_table.c \
    ccn_extend_dict.c \
    ccn_face_mgmt.c \
    ccn_fetch.c \
    ccn_flatname.c \
    ccn_header.c \
    ccn_indexbuf.c \
    ccn_interest.c \
    ccn_keystore.c \
    ccn_match.c \
    ccn_merkle_path_asn1.c \
    ccn_name_util.c \
    ccn_nametree.c \
    ccn_reg_mgmt.c \
    ccn_schedule.c \
    ccn_seqwriter.c \
    ccn_setup_sockaddr_un.c \
    ccn_signing.c \
    ccn_sockaddrutil.c \
    ccn_sockcreate.c \
    ccn_strategy_mgmt.c \
    ccn_traverse.c \
    ccn_uri.c \
    ccn_verifysig.c \
    ccn_versioning.c \
    ccnbtreetest.c \
    encodedecodetest.c \
    hashtb.c \
    hashtbtest.c \
    lned.c \
    nametreetest.c \
    signbenchtest.c \
    siphash24.c \
    skel_decode_test.c

LIBS = libccn.a

LIB_OBJS = \
    ccn_aes_keystore.o \
    ccn_aes_keystore_asn1.o \
    ccn_bloom.o \
    ccn_btree.o \
    ccn_btree_content.o \
    ccn_btree_store.o \
    ccn_buf_decoder.o \
    ccn_buf_encoder.o \
    ccn_bulkdata.o \
    ccn_charbuf.o \
    ccn_client.o \
    ccn_coding.o \
    ccn_digest.o \
    ccn_dtag_table.o \
    ccn_extend_dict.o \
    ccn_face_mgmt.o \
    ccn_fetch.o \
    ccn_flatname.o \
    ccn_header.o \
    ccn_indexbuf.o \
    ccn_interest.o \
    ccn_keystore.o \
    ccn_match.o \
    ccn_merkle_path_asn1.o \
    ccn_name_util.o \
    ccn_nametree.o \
    ccn_reg_mgmt.o \
    ccn_schedule.o \
    ccn_seqwriter.o \
    ccn_setup_sockaddr_un.o \
    ccn_signing.o \
    ccn_sockaddrutil.o \
    ccn_sockcreate.o \
    ccn_strategy_mgmt.o \
    ccn_traverse.o \
    ccn_uri.o \
    ccn_versioning.o \
    hashtb.o \
    lned.o \
    siphash24.o

SRCLINKS = q.dat

default all: dtag_check lib $(PROGRAMS)
# Don't try to build shared libs right now.
# all: shlib

all: ccn_verifysig

install: install_headers
install_headers:
	@test -d $(DINST_INC) || (echo $(DINST_INC) does not exist.  Please mkdir -p $(DINST_INC) if this is what you intended. && exit 2)
	mkdir -p $(DINST_INC)/ccn
	for i in `cd ../include/ccn && echo *.h`; do                \
	    cmp -s ../include/ccn/$$i $(DINST_INC)/ccn/$$i || \
	        cp ../include/ccn/$$i $(DINST_INC)/ccn/$$i || \
	        exit 1;                                             \
	done

uninstall: uninstall_headers
uninstall_headers:
	test -L $(DINST_INC)/ccn && $(RM) $(DINST_INC)/ccn ||:
	test -L $(DINST_INC) || $(RM) -r $(DINST_INC)/ccn

shlib: $(SHLIBNAME)

lib: libccn.a

test: default encodedecodetest ccnbtreetest nametreetest q.dat
	./encodedecodetest -o /dev/null
	./ccnbtreetest
	./ccnbtreetest - < q.dat
	./nametreetest - < q.dat
	$(RM) -R _bt_*

dtag_check: _always
	@./gen_dtag_table 2>/dev/null | diff - ccn_dtag_table.c | grep '^[<]' >/dev/null && echo '*** Warning: ccn_dtag_table.c may be out of sync with tagnames.cvsdict' || :

depend: android_obj.mk
android_obj.mk: _always
	echo 'CCNLIBOBJ := \' > templist
	for i in $(LIB_OBJS); do echo "    $$i" '\'; done | sort -u >> templist
	echo >> templist
	diff -b templist android_obj.mk || mv templist android_obj.mk
	$(RM) templist

libccn.a: $(LIB_OBJS)
	$(RM) $@
	$(AR) crus $@ $(LIB_OBJS)

shared: $(SHLIBNAME)

$(SHLIBNAME): libccn.a $(SHLIBDEPS)
	$(LD) $(SHARED_LD_FLAGS) $(OPENSSL_LIBS) -lcrypto -o $@ libccn.a

$(PROGRAMS): libccn.a

hashtbtest: hashtbtest.o
	$(CC) $(CFLAGS) -o $@ hashtbtest.o $(LDLIBS)

skel_decode_test: skel_decode_test.o
	$(CC) $(CFLAGS) -o $@ skel_decode_test.o $(LDLIBS)

basicparsetest: basicparsetest.o libccn.a
	$(CC) $(CFLAGS) -o $@ basicparsetest.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

encodedecodetest: encodedecodetest.o
	$(CC) $(CFLAGS) -o $@ encodedecodetest.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccn_digest.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_digest.c

ccn_extend_dict.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_extend_dict.c

ccn_keystore.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_keystore.c

ccn_aes_keystore.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_aes_keystore.c

ccn_aes_keystore_asn1.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_aes_keystore_asn1.c

ccn_signing.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_signing.c

ccn_sockcreate.o:
	$(CC) $(CFLAGS) -c ccn_sockcreate.c

ccn_strategy_mgmt.o:
	$(CC) $(CFLAGS) -c ccn_strategy_mgmt.c

ccn_traverse.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_traverse.c

ccn_merkle_path_asn1.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_merkle_path_asn1.c

ccn_header.o:
	$(CC) $(CFLAGS) -c ccn_header.c

ccn_fetch.o:
	$(CC) $(CFLAGS) -c ccn_fetch.c

ccn_verifysig.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_verifysig.c

ccn_verifysig: ccn_verifysig.o
	$(CC) $(CFLAGS) -o $@ ccn_verifysig.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

signbenchtest.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c signbenchtest.c

signbenchtest: signbenchtest.o
	$(CC) $(CFLAGS) -o $@ signbenchtest.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto 

ccnbtreetest.o:
	$(CC) $(CFLAGS) -Dccnbtreetest_main=main -c ccnbtreetest.c

ccnbtreetest: ccnbtreetest.o libccn.a
	$(CC) $(CFLAGS) -o $@ ccnbtreetest.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

nametreetest.o:
	$(CC) $(CFLAGS) -Dnametreetest_main=main -c nametreetest.c

nametreetest: nametreetest.o libccn.a
	$(CC) $(CFLAGS) -o $@ nametreetest.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

clean:
	rm -f *.o libccn.a libccn.1.$(SHEXT) $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS) *% *~
