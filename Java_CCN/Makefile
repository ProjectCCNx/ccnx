# Makefile for ccn/Java_CCN

ANT = ant
WHINE = sh -c "type $(ANT) 2>/dev/null ||                  \
               echo Skipping java build in $$(pwd -L) -    \
                    $(ANT) is not installed; "

default all: jar
check: test

# Use ant to actually do the work for these targets
jar clean test: _always
	@type $(ANT) > /dev/null && $(ANT) -k $@ || $(WHINE)
	@rm -f _always

documentation: _always
	doxygen

testinstall install uninstall:
	@echo No $@ target for `pwd`
 
default all clean depend test check shared install uninstall: _always

_always:

.PHONY: _always
