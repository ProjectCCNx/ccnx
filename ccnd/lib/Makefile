SHEXT=
SHARED_LD_FLAGS=
PLATCFLAGS=

# Darwin #
#SHEXT=dylib
#SHARED_LD_FLAGS = -dylib -arch `/usr/bin/arch` -install_name libccn.1.$(SHEXT) -all_load /usr/lib/dylib1.o -lSystem
#PLATCFLAGS=-fno-common

CFLAGS = $(COPT) $(CPREFLAGS) $(PLATCFLAGS)
LDLIBS = -L../lib $(MORE_LDLIBS) -lexpat -lccn2
CPREFLAGS = -I../include
COPT = -g -Wall

#Solaris# make MORE_LDLIBS='-lsocket -lnsl'

PROGRAMS = ccn_ccnbtoxml ccn_xmltoccnb ccn_splitccnb \
    hashtbtest matrixtest skel_decode_test \
    smoketestclientlib dataresponsetest ccntimestamps ccndump \
    ccn_verifysig
DEBRIS = ccn_coding ccn_recursive_parse
CSRC = ccn_buf_decoder.c ccn_charbuf.c ccn_indexbuf.c ccn_coding.c ccn_client.c  \
    ccn_ccnbtoxml.c ccn_xmltoccnb.c ccn_splitccnb.c ccn_schedule.c ccn_matrix.c \
    skel_decode_test.c \
    hashtb.c hashtbtest.c matrixtest.c \
    smoketestclientlib.c ccntimestamps.c ccndump.c \
    ccn_verifysig.c

LIB_OBJS = ccn_client.o ccn_charbuf.o ccn_indexbuf.o ccn_coding.o ccn_schedule.o ccn_matrix.o \
    ccn_buf_decoder.o hashtb.o

default all: lib $(PROGRAMS)

lib: libccn2.a

libccn2.a: $(LIB_OBJS)
	ar cru $@ $(LIB_OBJS)

shared: libccn.1.$(SHEXT)

libccn.1.$(SHEXT): libccn2.a
	$(LD) $(SHARED_LD_FLAGS) -o $@ libccn2.a

$(PROGRAMS): libccn2.a

ccn_ccnbtoxml: ccn_ccnbtoxml.o
	$(CC) $(CFLAGS) -o $@ ccn_ccnbtoxml.o $(LDLIBS)

ccn_xmltoccnb: ccn_xmltoccnb.o
	$(CC) $(CFLAGS) -o $@ ccn_xmltoccnb.o $(LDLIBS)

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

ccntimestamps: ccntimestamps.o
	$(CC) $(CFLAGS) -o $@ ccntimestamps.o $(LDLIBS)

ccndump: ccndump.o
	$(CC) $(CFLAGS) -o $@ ccndump.o $(LDLIBS)

ccn_verifysig: ccn_verifysig.o
	$(CC) $(CFLAGS) -o $@ ccn_verifysig.o $(LDLIBS) -lcrypto

clean:
	rm -f *.o *.a libccn.1.$(SHEXT) $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

depend: Makefile $(CSRC)
	gcc -MM $(CSRC) $(CPREFLAGS) > depend
	tail -n `wc -l < depend` Makefile | diff - depend
###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
ccn_buf_decoder.o: ccn_buf_decoder.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccn_charbuf.o: ccn_charbuf.c ../include/ccn/charbuf.h
ccn_indexbuf.o: ccn_indexbuf.c ../include/ccn/indexbuf.h
ccn_coding.o: ccn_coding.c ../include/ccn/charbuf.h \
  ../include/ccn/coding.h
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
ccn_verifysig.o: ccn_verifysig.c
