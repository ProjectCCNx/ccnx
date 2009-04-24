CFLAGS = $(COPT) $(CWARNFLAGS) $(CPREFLAGS) $(PLATCFLAGS)
LDLIBS = -L$(CCNLIBDIR) $(MORE_LDLIBS) -lccn
CPREFLAGS = -I../include
COPT = -g 
CCNLIBDIR = ../lib

INSTALLED_PROGRAMS = ccndc
PROGRAMS = $(INSTALLED_PROGRAMS)
CSRC = ccndc.c

default all: $(PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn.a

ccndc: ccndc.o
	$(CC) $(CFLAGS) -o $@ ccndc.o $(LDLIBS) -lcrypto -lsocket

clean:
	rm -f *.o *.a $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

test:
	@echo "Sorry, no ccndc unit tests at this time"

###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
ccndc.o: ccndc.c
ccndc.o: ../include/ccn/ccn.h
ccndc.o: ../include/ccn/coding.h
ccndc.o: ../include/ccn/charbuf.h
ccndc.o: ../include/ccn/indexbuf.h
ccndc.o: ../include/ccn/uri.h
ccndc.o: ../include/ccn/charbuf.h
