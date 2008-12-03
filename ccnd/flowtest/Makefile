CFLAGS=	$(COPT) $(CWARNFLAGS) $(CPREFLAGS) $(PLATCFLAGS)
PROGRAMS=	flowtest
CSRC=		flowtest.c
COPT=		-g

default all: $(PROGRAMS)

flowtest: flowtest.o
	$(CC) $(CFLAGS) -o $@ flowtest.o $(MORE_LDLIBS)

clean:
	rm -f *.o $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

test: default
	./flowtest -c 30 -e 7777 localhost & ./flowtest -vv -c 31 -l 5  localhost 7777

###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
flowtest.o: flowtest.c
