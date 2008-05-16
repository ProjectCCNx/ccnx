CFLAGS = $(COPT) $(CWARNFLAGS) $(CPREFLAGS) $(PLATCFLAGS)
LDLIBS = -L$(CCNLIBDIR) $(MORE_LDLIBS) -lccn2
CPREFLAGS = -I../include
COPT = -g
CCNLIBDIR = ../lib

PROGRAMS = ccnd smoketestccnd
CSRC = ccnd.c ccnd_stats.c smoketestccnd.c
HSRC = ccnd_private.h

default all: $(PROGRAMS)

$(PROGRAMS): $(CCNLIBDIR)/libccn2.a

CCND_OBJ = ccnd.o ccnd_stats.o
ccnd: $(CCND_OBJ) 
	$(CC) $(CFLAGS) -o $@ $(CCND_OBJ) $(LDLIBS)

smoketestccnd: smoketestccnd.o
	$(CC) $(CFLAGS) -o $@ smoketestccnd.o $(LDLIBS)

clean:
	rm -f *.o *.a $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

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
