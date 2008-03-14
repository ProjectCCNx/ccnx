CFLAGS = $(COPT) $(CPREFLAGS)
LDLIBS = -L$(CCNLIBDIR) $(MORE_LDLIBS) -lccn2
CPREFLAGS = -I../include
COPT = -g -Wall
CCNLIBDIR = ../lib

#Solaris# make MORE_LDLIBS='-lsocket -lnsl'

PROGRAMS = ccnd
CSRC = ccnd.c

default all: $(PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn2.a

$(CCNLIBDIR)/libccn2.a:
	cd $(CCNLIBDIR) && $(MAKE) libccn2.a;

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
ccnd.o: ccnd.c ../include/ccn/ccnd.h ../include/ccn/charbuf.h \
  ../include/ccn/coding.h ../include/ccn/hashtb.h
