# Makefile for apps/containerApp
# Use ant to actually do the work here

ANT = ant
WHINE = sh -c "type $(ANT) 2>/dev/null ||                  \
               echo Skipping java build in $$(pwd -L) -    \
                    $(ANT) is not installed; "

default all: jar
check: test

# Note - change this if documentation target is added to build.xml
documentation: _always
jar clean: _always
	@type $(ANT) > /dev/null && $(ANT) $@ || $(WHINE)
	@rm -f _always

depend shared: _always

testinstall install uninstall test:
	@echo No $@ target for `pwd`
 
default all clean depend test check shared documentation install uninstall: _always

_always:

.PHONY: _always
