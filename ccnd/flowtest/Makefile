CFLAGS=	$(COPT) $(CWARNFLAGS) $(CPREFLAGS) $(PLATCFLAGS)
PROGRAMS=	flowtest
CSRC=		flowtest.c

default all: $(PROGRAMS)

flowtest: flowtest.o
	$(CC) $(CFLAGS) -o $@ flowtest.o $(MORE_LDLIBS)

clean:
	rm -f *.o $(PROGRAMS) depend
	rm -rf *.dSYM $(DEBRIS)

test: default
	./flowtest -vv localhost

###############################
# Dependencies below here are checked by depend target
# but must be updated manually.
###############################
flowtest.o: flowtest.c
