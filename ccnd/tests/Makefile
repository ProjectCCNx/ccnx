# Makefile for tests directory
SCRIPTSRC = 

default all:
	@: nothing to do in tests

clean:
	rm -rf log logs depend STATUS SKIPPED FAILING

check test:
	mkdir -p log
	./testdriver
	: -------------- :
	:  TESTS PASSED  :
	: -------------- :

.PHONY: default all clean check test
