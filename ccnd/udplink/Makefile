CFLAGS = $(COPT) $(CPREFLAGS)
LDLIBS = -L$(CCNLIBDIR) $(MORE_LDLIBS) -lccn2
CPREFLAGS = -I../include
COPT = -g -Wall
CCNLIBDIR = ../lib

#Solaris# make MORE_LDLIBS='-lsocket -lnsl'

PROGRAMS = udplink
CSRC = udplink.c

default all: $(PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn2.a

$(CCNLIBDIR)/libccn2.a:
	cd $(CCNLIBDIR) && $(MAKE) libccn2.a;

udplink: udplink.o
	$(CC) $(CFLAGS) -o $@ udplink.o $(LDLIBS)

clean:
	rm -f *.o *.a $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
udplink.o: udplink.c ../include/ccn/ccnd.h \
  ../include/ccn/coding.h
