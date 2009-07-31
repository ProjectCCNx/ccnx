# makefile for src/lib directory

LDLIBS = -L$(CCNLIBDIR) $(MORE_LDLIBS) -lccn
EXPATLIBS = -lexpat
CCNLIBDIR = ../lib

PROGRAMS = hashtbtest matrixtest skel_decode_test \
    smoketestclientlib  \
    encodedecodetest signbenchtest

BROKEN_PROGRAMS =
DEBRIS = 
SCRIPTSRC = ccn_initkeystore.sh
CSRC = ccn_bloom.c ccn_buf_decoder.c ccn_buf_encoder.c ccn_bulkdata.c \
       ccn_charbuf.c ccn_client.c ccn_coding.c ccn_digest.c \
       ccn_dtag_table.c ccn_indexbuf.c ccn_keystore.c ccn_match.c \
       ccn_matrix.c ccn_merkle_path_asn1.c ccn_name_util.c ccn_schedule.c \
       ccn_signing.c ccn_traverse.c ccn_uri.c ccn_verifysig.c ccn_versioning.c \
       encodedecodetest.c hashtb.c hashtbtest.c \
       matrixtest.c signbenchtest.c skel_decode_test.c \
       smoketestclientlib.c
LIBS = libccn.a
LIB_OBJS = ccn_client.o ccn_charbuf.o ccn_indexbuf.o ccn_coding.o ccn_dtag_table.o ccn_schedule.o ccn_matrix.o \
    ccn_buf_decoder.o ccn_uri.o ccn_buf_encoder.o ccn_bloom.o ccn_name_util.o ccn_digest.o ccn_keystore.o ccn_signing.o ccn_traverse.o ccn_match.o hashtb.o ccn_merkle_path_asn1.o ccn_bulkdata.o ccn_versioning.o

default all: lib $(PROGRAMS)
# Don't try to build shared libs right now.
# all: shlib

install: install_headers
install_headers:
	test -d $(INSTALL_INCLUDE) && mkdir -p $(INSTALL_INCLUDE)/ccn && cp ../include/ccn/*.h $(INSTALL_INCLUDE)/ccn	

uninstall: uninstall_headers
uninstall_headers:
	$(RM) -r $(INSTALL_INCLUDE)/ccn

shlib: $(SHLIBNAME)

lib: libccn.a

test: default keystore_check encodedecodetest
	./encodedecodetest -o /dev/null

keystore_check: ccn_initkeystore.sh
	test -f $$HOME/.ccn/.ccn_keystore || $(MAKE) -f dir.mk new_keystore

new_keystore:
	@echo === CCN Keystore not found in your home directory
	@echo === I will create one for you now '(^C to abort)'
	sleep 1 && sh ccn_initkeystore.sh && sleep 3 && mv .ccn $(HOME)

libccn.a: $(LIB_OBJS)
	ar cru $@ $(LIB_OBJS)

shared: $(SHLIBNAME)

$(SHLIBNAME): libccn.a $(SHLIBDEPS)
	$(LD) $(SHARED_LD_FLAGS) $(OPENSSL_LIBS) -lcrypto -o $@ libccn.a

$(PROGRAMS): libccn.a

hashtbtest: hashtbtest.o
	$(CC) $(CFLAGS) -o $@ hashtbtest.o $(LDLIBS)

matrixtest: matrixtest.o
	$(CC) $(CFLAGS) -o $@ matrixtest.o $(LDLIBS)

skel_decode_test: skel_decode_test.o
	$(CC) $(CFLAGS) -o $@ skel_decode_test.o $(LDLIBS)

smoketestclientlib: smoketestclientlib.o
	$(CC) $(CFLAGS) -o $@ smoketestclientlib.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

encodedecodetest: encodedecodetest.o
	$(CC) $(CFLAGS) -o $@ encodedecodetest.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

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
ccn_bloom.o: ccn_bloom.c ../include/ccn/bloom.h
ccn_buf_decoder.o: ccn_buf_decoder.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccn_buf_encoder.o: ccn_buf_encoder.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/signing.h \
  ../include/ccn/ccn_private.h
ccn_bulkdata.o: ccn_bulkdata.c ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccn_charbuf.o: ccn_charbuf.c ../include/ccn/charbuf.h
ccn_client.o: ccn_client.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccn_private.h ../include/ccn/ccnd.h \
  ../include/ccn/digest.h ../include/ccn/hashtb.h \
  ../include/ccn/signing.h
ccn_coding.o: ccn_coding.c ../include/ccn/coding.h
ccn_digest.o: ccn_digest.c ../include/ccn/digest.h
ccn_dtag_table.o: ccn_dtag_table.c ../include/ccn/coding.h
ccn_indexbuf.o: ccn_indexbuf.c ../include/ccn/indexbuf.h
ccn_keystore.o: ccn_keystore.c ../include/ccn/keystore.h
ccn_match.o: ccn_match.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/digest.h
ccn_matrix.o: ccn_matrix.c ../include/ccn/matrix.h \
  ../include/ccn/hashtb.h
ccn_merkle_path_asn1.o: ccn_merkle_path_asn1.c \
  ../include/ccn/merklepathasn1.h
ccn_name_util.o: ccn_name_util.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccn_schedule.o: ccn_schedule.c ../include/ccn/schedule.h
ccn_signing.o: ccn_signing.c ../include/ccn/merklepathasn1.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/signing.h
ccn_traverse.o: ccn_traverse.c ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccn_uri.o: ccn_uri.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccn_verifysig.o: ccn_verifysig.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/keystore.h \
  ../include/ccn/signing.h
ccn_versioning.o: ccn_versioning.c ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h \
  ../include/ccn/ccn_private.h
encodedecodetest.o: encodedecodetest.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/bloom.h ../include/ccn/uri.h \
  ../include/ccn/digest.h ../include/ccn/keystore.h \
  ../include/ccn/signing.h
hashtb.o: hashtb.c ../include/ccn/hashtb.h
hashtbtest.o: hashtbtest.c ../include/ccn/hashtb.h
matrixtest.o: matrixtest.c ../include/ccn/matrix.h
signbenchtest.o: signbenchtest.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/keystore.h
skel_decode_test.o: skel_decode_test.c ../include/ccn/charbuf.h \
  ../include/ccn/coding.h
smoketestclientlib.o: smoketestclientlib.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
