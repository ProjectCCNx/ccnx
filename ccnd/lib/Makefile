CFLAGS = $(COPT) $(CPREFLAGS)
LDLIBS = -L../lib $(MORE_LDLIBS) -lexpat -lccn2
CPREFLAGS = -I../include
COPT = -g -Wall

#Solaris# make MORE_LDLIBS='-lsocket -lnsl'

PROGRAMS = ccn_coding ccn_xmltoccnb
CSRC = ccn_charbuf.c ccn_coding.c ccn_xmltoccnb.c

LIB_OBJS = ccn_charbuf.o 

default all: $(PROGRAMS) lib

lib: libccn2.a

libccn2.a: $(LIB_OBJS)
	ar cru $@ $(LIB_OBJS)

ccn_coding: ccn_coding.o libccn2.a
	$(CC) $(CFLAGS) -o $@ ccn_coding.o $(LDLIBS)

ccn_xmltoccnb: ccn_xmltoccnb.o libccn2.a
	$(CC) $(CFLAGS) -o $@ ccn_xmltoccnb.o $(LDLIBS)

clean:
	rm -f *.o *.a $(PROGRAMS) depend
	rm -rf *.dSYM

depend: Makefile $(CSRC)
	gcc -MM $(CSRC) $(CPREFLAGS) > depend
	tail -n `wc -l < depend` Makefile | diff - depend
###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
ccn_charbuf.o: ccn_charbuf.c ../include/ccn/charbuf.h
ccn_coding.o: ccn_coding.c ../include/ccn/charbuf.h \
  ../include/ccn/coding.h
ccn_xmltoccnb.o: ccn_xmltoccnb.c ../include/ccn/coding.h \
  ../include/ccn/charbuf.h
