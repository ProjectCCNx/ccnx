CFLAGS = $(COPT) $(CPREFLAGS)
LDLIBS = -L../lib $(MORE_LDLIBS) -lexpat -lccn2
CPREFLAGS = -I../include
COPT = -g -Wall

#Solaris# make MORE_LDLIBS='-lsocket -lnsl'

PROGRAMS = ccn_ccnbtoxml ccn_xmltoccnb \
    hashtbtest skel_decode_test smoketestclientlib
DEBRIS = ccn_coding ccn_recursive_parse
CSRC = ccn_buf_decoder.c ccn_charbuf.c ccn_coding.c ccn_client.c  \
    ccn_ccnbtoxml.c ccn_xmltoccnb.c ccn_schedule.c \
    skel_decode_test.c \
    hashtb.c hashtbtest.c \
    smoketestclientlib.c

LIB_OBJS = ccn_client.o ccn_charbuf.o ccn_coding.o ccn_schedule.o \
    ccn_buf_decoder.o hashtb.o

default all: lib $(PROGRAMS)

lib: libccn2.a

libccn2.a: $(LIB_OBJS)
	ar cru $@ $(LIB_OBJS)

$(PROGRAMS): libccn2.a

ccn_ccnbtoxml: ccn_ccnbtoxml.o
	$(CC) $(CFLAGS) -o $@ ccn_ccnbtoxml.o $(LDLIBS)

ccn_xmltoccnb: ccn_xmltoccnb.o
	$(CC) $(CFLAGS) -o $@ ccn_xmltoccnb.o $(LDLIBS)

hashtbtest: hashtbtest.o
	$(CC) $(CFLAGS) -o $@ hashtbtest.o $(LDLIBS)

skel_decode_test: skel_decode_test.o
	$(CC) $(CFLAGS) -o $@ skel_decode_test.o $(LDLIBS)

smoketestclientlib: smoketestclientlib.o
	$(CC) $(CFLAGS) -o $@ smoketestclientlib.o $(LDLIBS)

clean:
	rm -f *.o *.a $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

depend: Makefile $(CSRC)
	gcc -MM $(CSRC) $(CPREFLAGS) > depend
	tail -n `wc -l < depend` Makefile | diff - depend
###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
ccn_buf_decoder.o: ccn_buf_decoder.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h
ccn_charbuf.o: ccn_charbuf.c ../include/ccn/charbuf.h
ccn_coding.o: ccn_coding.c ../include/ccn/charbuf.h \
  ../include/ccn/coding.h
ccn_client.o: ccn_client.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/ccnd.h ../include/ccn/hashtb.h
ccn_ccnbtoxml.o: ccn_ccnbtoxml.c ../include/ccn/charbuf.h \
  ../include/ccn/coding.h
ccn_xmltoccnb.o: ccn_xmltoccnb.c ../include/ccn/coding.h \
  ../include/ccn/charbuf.h
ccn_schedule.o: ccn_schedule.c ../include/ccn/schedule.h
skel_decode_test.o: skel_decode_test.c ../include/ccn/charbuf.h \
  ../include/ccn/coding.h
hashtb.o: hashtb.c ../include/ccn/hashtb.h
hashtbtest.o: hashtbtest.c ../include/ccn/hashtb.h
smoketestclientlib.o: smoketestclientlib.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h
