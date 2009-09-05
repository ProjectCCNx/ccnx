# makefile for csrc/libexec directory

LDLIBS = -L$(CCNLIBDIR) $(MORE_LDLIBS) -lccn
CCNLIBDIR = ../lib

INSTALLED_PROGRAMS = ccndc
PROGRAMS = $(INSTALLED_PROGRAMS) ccndc-inject udplink
CSRC = ccndc.c ccndc-inject.c udplink.c

default all: $(PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn.a

ccndc: ccndc.o
	$(CC) $(CFLAGS) -o $@ ccndc.o $(LDLIBS) $(OPENSSL_LIBS) -lresolv -lcrypto

ccndc-inject: ccndc-inject.o
	$(CC) $(CFLAGS) -o $@ ccndc-inject.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

udplink: udplink.o
	$(CC) $(CFLAGS) -o $@ udplink.o $(LDLIBS)  $(OPENSSL_LIBS) -lcrypto

clean:
	rm -f *.o *.a $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

test:
	@echo "Sorry, no libexec unit tests at this time"

###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
ccndc.o: ccndc.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccnd.h ../include/ccn/uri.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/signing.h \
  ../include/ccn/keystore.h
ccndc-inject.o: ccndc-inject.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccnd.h ../include/ccn/uri.h
udplink.o: udplink.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h
