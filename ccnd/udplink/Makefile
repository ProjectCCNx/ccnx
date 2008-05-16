UNAME =$(shell uname)
UNAME:sh =uname
CFLAGS = $(COPT) $(CPREFLAGS) $(MORE_CFLAGS)
LDLIBS = -L$(CCNLIBDIR) $(MORE_LDLIBS) -lccn2
CPREFLAGS = -I../include
COPT = -g 
CCNLIBDIR = ../lib

include Makefile.$(UNAME).mk

PROGRAMS = udplink
CSRC = udplink.c

default all: $(PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn2.a

$(OBJDIR)/Makefile: Makefile
	test -d $(OBJDIR)
	test -f $(OBJDIR)/Makefile && mv $(OBJDIR)/Makefile $(OBJDIR)/Makefile~ ||:
	cp -p Makefile $(OBJDIR)/Makefile
	cp -p *.mk $(OBJDIR)/.

$(CSRC):
	test -f $(SRCDIR)/$@ && ln -s $(SRCDIR)/$@


udplink: udplink.o
	$(CC) $(CFLAGS) -o $@ udplink.o $(LDLIBS)

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
udplink.o: udplink.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h
