# Makefile for ccn/Java_CCN

ANT = ant
LIBS = ccn.jar lib/bcprov-jdk16-143.jar
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
CP = cp
LS = /bin/ls

default all: jar
check: test

install: jar
	test -d $(INSTALL_LIB) 
	for i in $(LIBS) ""; do test -z "$$i" || $(INSTALL) $$i $(INSTALL_LIB); done
	test -d $(INSTALL_BIN)
	# Using -R on . dir to preserve sym links
	$(CP) -R tools/. $(INSTALL_BIN)

uninstall:
	for i in $(LIBS) ""; do test -z "$$i" || $(RM) $(INSTALL_LIB)/`basename $$i`; done
	for i in `$(LS) tools` "" ; do test -z "$$i" || $(RM) $(INSTALL_BIN)/`basename $$i`; done

# Use ant to actually do the work for these targets
jar clean test: _always
	@type $(ANT) > /dev/null && $(ANT) -k $@ || $(WHINE)
	@rm -f _always

documentation: _always
	doxygen

testinstall:
	@echo No $@ target for `pwd`
 
default all clean depend test check shared install uninstall: _always

_always:

.PHONY: _always
