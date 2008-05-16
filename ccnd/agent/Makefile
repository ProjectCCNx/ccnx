CFLAGS = $(COPT) $(CPREFLAGS)
LDLIBS = -L$(CCNLIBDIR) $(MORE_LDLIBS) -lccn2
CPREFLAGS = -I../include
COPT = -g -Wall
CCNLIBDIR = ../lib

#Solaris# make MORE_LDLIBS='-lsocket -lnsl'

PROGRAMS = ccnd smoketestccnd
CSRC = ccnd.c ccnd_stats.c smoketestccnd.c

default all: $(PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn2.a

$(OBJDIR)/Makefile: Makefile
	test -d $(OBJDIR)
	test -f $(OBJDIR)/Makefile && mv $(OBJDIR)/Makefile $(OBJDIR)/Makefile~ ||:
	cp -p Makefile $(OBJDIR)/Makefile

$(CSRC) ccnd_private.h:
	test -f $(SRCDIR)/$@ && ln -s $(SRCDIR)/$@

CCND_OBJ = ccnd.o ccnd_stats.o
ccnd: $(CCND_OBJ) 
	$(CC) $(CFLAGS) -o $@ $(CCND_OBJ) $(LDLIBS)

smoketestccnd: smoketestccnd.o
	$(CC) $(CFLAGS) -o $@ smoketestccnd.o $(LDLIBS)

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
ccnd.o: ccnd.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h ../include/ccn/hashtb.h ../include/ccn/matrix.h \
  ../include/ccn/schedule.h ccnd_private.h
ccnd_stats.o: ccnd_stats.c ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/schedule.h \
  ../include/ccn/hashtb.h ccnd_private.h ../include/ccn/coding.h
smoketestccnd.o: smoketestccnd.c
