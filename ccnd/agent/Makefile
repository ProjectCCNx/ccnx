CFLAGS = $(COPT) $(CWARNFLAGS) $(CPREFLAGS) $(PLATCFLAGS)
LDLIBS = -L$(CCNLIBDIR) $(MORE_LDLIBS) -lccn
CPREFLAGS = -I../include
COPT = -g
CCNLIBDIR = ../lib

PROGRAMS = ccnd smoketestccnd ccnd_june08
BROKEN_PROGRAMS = 
CSRC = ccnd.c ccnd_stats.c smoketestccnd.c ccnd_june08.c
HSRC = ccnd_private.h

default: $(PROGRAMS)

all: default $(BROKEN_PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn.a

CCND_OBJ = ccnd.o ccnd_stats.o
ccnd: $(CCND_OBJ) 
	$(CC) $(CFLAGS) -o $@ $(CCND_OBJ) $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

ccnd_june08: ccnd_june08.o
	$(CC) $(CFLAGS) -o $@ ccnd_june08.o $(LDLIBS)

smoketestccnd: smoketestccnd.o
	$(CC) $(CFLAGS) -o $@ smoketestccnd.o $(LDLIBS)

clean:
	rm -f *.o *.a $(PROGRAMS) $(BROKEN_PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

test:   ccnd smoketestccnd
	./testmatchrules
        
###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
ccnd.o: ccnd.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h ../include/ccn/bloom.h ../include/ccn/hashtb.h \
  ../include/ccn/schedule.h ../include/ccn/uri.h ccnd_private.h
ccnd_stats.o: ccnd_stats.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h ../include/ccn/schedule.h ../include/ccn/hashtb.h \
  ../include/ccn/uri.h ccnd_private.h
smoketestccnd.o: smoketestccnd.c
ccnd_june08.o: ccnd_june08.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h ../include/ccn/hashtb.h ../include/ccn/matrix.h \
  ../include/ccn/schedule.h
