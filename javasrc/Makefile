# Makefile for ccn/Java_CCN

ANT = ant
LIBS = ccn.jar
TOOLS = ccn_repo ccn_run dumplog get_file put_file
WHINE = sh -c "type $(ANT) 2>/dev/null ||                  \
               echo Skipping java build in $$(pwd -L) -    \
                    $(ANT) is not installed; "
# Default, the top-level Makefile default target will
# call the install target here but override INSTALL_BASE
# with dir in the source tree
INSTALL_BASE=/usr/local
INSTALL_LIB = $(INSTALL_BASE)/lib
INSTALL_BIN = $(INSTALL_BASE)/bin
INSTALL = cp 

default all: jar
check: test

install: jar
	test -d $(INSTALL_LIB) 
	for i in $(LIBS) ""; do test -z "$$i" || $(INSTALL) $$i $(INSTALL_LIB); done
	test -d $(INSTALL_BIN)
	for i in $(TOOLS) ; do test -z "tools/$$i" || echo $$i; $(INSTALL) tools/$$i $(INSTALL_BIN); done

# Use ant to actually do the work for these targets
jar clean test: _always
	@type $(ANT) > /dev/null && $(ANT) -k $@ || $(WHINE)
	@rm -f _always

documentation: _always
	doxygen

testinstall uninstall:
	@echo No $@ target for `pwd`
 
default all clean depend test check shared install uninstall: _always

_always:

.PHONY: _always
