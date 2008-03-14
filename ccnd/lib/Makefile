CFLAGS = $(COPT) $(CPREFLAGS)
LDLIBS = -L../lib $(MORE_LDLIBS) -lexpat -lccn2
CPREFLAGS = -I../include
COPT = -g -Wall

#Solaris# make MORE_LDLIBS='-lsocket -lnsl'

PROGRAMS = ccn_ccnbtoxml ccn_xmltoccnb ccn_recursive_parse hashtbtest
DEBRIS = ccn_coding
CSRC = ccn_charbuf.c ccn_coding.c ccn_ccnbtoxml.c ccn_xmltoccnb.c \
    ccn_recursive_parse.c \
    hashtb.c hashtbtest.c

LIB_OBJS = ccn_charbuf.o ccn_coding.o hashtb.o

default all: $(PROGRAMS) lib

lib: libccn2.a

libccn2.a: $(LIB_OBJS) ccn_coding.o
	ar cru $@ $(LIB_OBJS)

$(PROGRAMS): libccn2.a

ccn_ccnbtoxml: ccn_ccnbtoxml.o
	$(CC) $(CFLAGS) -o $@ ccn_ccnbtoxml.o $(LDLIBS)

ccn_xmltoccnb: ccn_xmltoccnb.o
	$(CC) $(CFLAGS) -o $@ ccn_xmltoccnb.o $(LDLIBS)

hashtbtest: hashtbtest.o
	$(CC) $(CFLAGS) -o $@ hashtbtest.o $(LDLIBS)

ccn_recursive_parse: ccn_recursive_parse.o
	$(CC) $(CFLAGS) -o $@ ccn_recursive_parse.o $(LDLIBS)

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
ccn_charbuf.o: ccn_charbuf.c ../include/ccn/charbuf.h
ccn_coding.o: ccn_coding.c ../include/ccn/charbuf.h \
  ../include/ccn/coding.h
ccn_ccnbtoxml.o: ccn_ccnbtoxml.c ../include/ccn/charbuf.h \
  ../include/ccn/coding.h
ccn_xmltoccnb.o: ccn_xmltoccnb.c ../include/ccn/coding.h \
  ../include/ccn/charbuf.h
ccn_recursive_parse.o: ccn_recursive_parse.c ../include/ccn/coding.h \
  ../include/ccn/charbuf.h
hashtb.o: hashtb.c ../include/ccn/hashtb.h
hashtbtest.o: hashtbtest.c ../include/ccn/hashtb.h
