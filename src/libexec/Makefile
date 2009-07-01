CFLAGS = $(COPT) $(CWARNFLAGS) $(CPREFLAGS) $(PLATCFLAGS)
LDLIBS = -L$(CCNLIBDIR) $(MORE_LDLIBS) -lccn
CPREFLAGS = -I../include
COPT = -g 
CCNLIBDIR = ../lib

INSTALLED_PROGRAMS = ccndc udplink
PROGRAMS = $(INSTALLED_PROGRAMS)
CSRC = ccndc.c udplink.c

default all: $(PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn.a

ccndc: ccndc.o
	$(CC) $(CFLAGS) -o $@ ccndc.o $(LDLIBS) $(OPENSSL_LIBS) -lcrypto

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
