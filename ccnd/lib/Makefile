CFLAGS = $(COPT) $(CWARNFLAGS) $(CPREFLAGS) $(PLATCFLAGS)
LDLIBS = -L../lib $(MORE_LDLIBS) -lccn
EXPATLIBS = -lexpat
CPREFLAGS = -I../include
COPT = -g

PROGRAMS = ccn_ccnbtoxml ccn_splitccnb ccn_verifysig\
    hashtbtest matrixtest skel_decode_test \
    smoketestclientlib dataresponsetest ccntimestamps \
    encodedecodetest ccndumpnames $(EXPAT_PROGRAMS)
EXPAT_PROGRAMS = ccn_xmltoccnb
BROKEN_PROGRAMS = ccndump
DEBRIS = libccn2.a
CSRC = ccn_buf_decoder.c ccn_dtag_table.c ccn_buf_encoder.c ccn_charbuf.c \
    ccn_indexbuf.c ccn_coding.c ccn_client.c  \
    ccn_ccnbtoxml.c ccn_xmltoccnb.c ccn_splitccnb.c \
    ccn_schedule.c ccn_matrix.c ccn_bloom.c ccn_name_util.c \
    ccn_match.c ccn_digest.c ccn_pkcs12.c \
    skel_decode_test.c \
    hashtb.c hashtbtest.c matrixtest.c \
    smoketestclientlib.c ccntimestamps.c ccndump.c ccndumpnames.c \
    ccn_verifysig.c dataresponsetest.c ccn_uri.c  encodedecodetest.c

LIB_OBJS = ccn_client.o ccn_charbuf.o ccn_indexbuf.o ccn_coding.o ccn_dtag_table.o ccn_schedule.o ccn_matrix.o \
    ccn_buf_decoder.o ccn_uri.o ccn_buf_encoder.o ccn_bloom.o ccn_name_util.o ccn_digest.o ccn_pkcs12.o ccn_match.o hashtb.o

default: lib $(PROGRAMS)

shlib: $(SHLIBNAME)

all: default shlib $(BROKEN_PROGRAMS)

lib: libccn.a

test: default encodedecodetest
	./encodedecodetest -o /dev/null

libccn.a: $(LIB_OBJS)
	ar cru $@ $(LIB_OBJS)

shared: $(SHLIBNAME)

$(SHLIBNAME): libccn.a $(SHLIBDEPS)
	$(LD) $(SHARED_LD_FLAGS) -o $@ libccn.a

$(PROGRAMS): libccn.a

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
	$(CC) $(CFLAGS) -o $@ smoketestclientlib.o $(LDLIBS)

dataresponsetest: dataresponsetest.o
	$(CC) $(CFLAGS) -o $@ dataresponsetest.o $(LDLIBS)

encodedecodetest: encodedecodetest.o
	$(CC) $(CFLAGS) -o $@ encodedecodetest.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccntimestamps: ccntimestamps.o
	$(CC) $(CFLAGS) -o $@ ccntimestamps.o $(LDLIBS)

ccndump: ccndump.o
	$(CC) $(CFLAGS) -o $@ ccndump.o $(LDLIBS)

ccndumpnames: ccndumpnames.o
	$(CC) $(CFLAGS) -o $@ ccndumpnames.o $(LDLIBS)

ccn_digest.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_digest.c

ccn_pkcs12.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_pkcs12.c

ccn_verifysig.o:
	$(CC) $(CFLAGS) $(OPENSSL_CFLAGS) -c ccn_verifysig.c

ccn_verifysig: ccn_verifysig.o
	$(CC) $(CFLAGS) -o $@ ccn_verifysig.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

clean:
	rm -f *.o libccn.a libccn.1.$(SHEXT) $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
ccn_buf_decoder.o: ccn_buf_decoder.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccn_dtag_table.o: ccn_dtag_table.c ../include/ccn/coding.h
ccn_buf_encoder.o: ccn_buf_encoder.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccn_charbuf.o: ccn_charbuf.c ../include/ccn/charbuf.h
ccn_indexbuf.o: ccn_indexbuf.c ../include/ccn/indexbuf.h
ccn_coding.o: ccn_coding.c ../include/ccn/coding.h
ccn_client.o: ccn_client.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h ../include/ccn/hashtb.h
ccn_ccnbtoxml.o: ccn_ccnbtoxml.c ../include/ccn/charbuf.h \
  ../include/ccn/coding.h
ccn_xmltoccnb.o: ccn_xmltoccnb.c ../include/ccn/coding.h \
  ../include/ccn/charbuf.h
ccn_splitccnb.o: ccn_splitccnb.c ../include/ccn/charbuf.h \
  ../include/ccn/coding.h
ccn_schedule.o: ccn_schedule.c ../include/ccn/schedule.h
ccn_matrix.o: ccn_matrix.c ../include/ccn/matrix.h \
  ../include/ccn/hashtb.h
ccn_bloom.o: ccn_bloom.c ../include/ccn/bloom.h
ccn_name_util.o: ccn_name_util.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccn_match.o: ccn_match.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/digest.h
ccn_digest.o: ccn_digest.c ../include/ccn/digest.h
skel_decode_test.o: skel_decode_test.c ../include/ccn/charbuf.h \
  ../include/ccn/coding.h
hashtb.o: hashtb.c ../include/ccn/hashtb.h
hashtbtest.o: hashtbtest.c ../include/ccn/hashtb.h
matrixtest.o: matrixtest.c ../include/ccn/matrix.h
smoketestclientlib.o: smoketestclientlib.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccntimestamps.o: ccntimestamps.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccndump.o: ccndump.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h
ccndumpnames.o: ccndumpnames.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccn_verifysig.o: ccn_verifysig.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
dataresponsetest.o: dataresponsetest.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccn_uri.o: ccn_uri.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h ../include/ccn/uri.h
encodedecodetest.o: encodedecodetest.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h ../include/ccn/digest.h
