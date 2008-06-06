CFLAGS = $(COPT) $(CWARNFLAGS) $(CPREFLAGS) $(MORE_CFLAGS)
LDLIBS = -L$(CCNLIBDIR) $(MORE_LDLIBS) -lccn
CPREFLAGS = -I../include
COPT = -g 
CCNLIBDIR = ../lib

PROGRAMS = udplink
CSRC = udplink.c

default all: $(PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn.a

udplink: udplink.o
	$(CC) $(CFLAGS) -o $@ udplink.o $(LDLIBS)

clean:
	rm -f *.o *.a $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
udplink.o: udplink.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h
