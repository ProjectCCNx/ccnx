# javasrc/Makefile
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009, 2010, 2011 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

ANT = `command -v ant || echo echo SKIPPING ant`
JAR = ccn.jar
LIBS = $(JAR) lib/bcprov-jdk16-143.jar lib/kxml2-2.3.0.jar $(EXTRA_LIBS)
EXTRA_LIBS = `find lib/bcprov-jdk15-143.jar -print 2>/dev/null`
WHINE = sh -c "type $(ANT) 2>/dev/null ||                  \
               echo Skipping java build in $$(pwd -L) -    \
                    $(ANT) is not installed; "
WARN_NO_INSTALL = echo Nothing installed from javasrc/
# Default, the top-level Makefile default target will
# call the install target here but override INSTALL_BASE
# with dir in the source tree
INSTALL_LIB = $(INSTALL_BASE)/lib
INSTALL_BIN = $(INSTALL_BASE)/bin
INSTALL = cp 
CP = cp
LS = /bin/ls

default all: jar
check: test

install:
	@test -f $(JAR) && $(MAKE) real_install \
            INSTALL_BASE=$(INSTALL_BASE)        \
            INSTALL_LIB=$(INSTALL_LIB)          \
            INSTALL_BIN=$(INSTALL_BIN) || $(WARN_NO_INSTALL)

real_install: _always
	test -d $(INSTALL_LIB) 
	for i in $(LIBS) ""; do test -z "$$i" || $(INSTALL) $$i $(INSTALL_LIB); done
	test -d $(INSTALL_BIN)
	# Using -R on . dir to preserve sym links
	$(CP) -R tools/. $(INSTALL_BIN)

uninstall:
	for i in $(LIBS) ""; do test -z "$$i" || rm -f $(INSTALL_LIB)/`basename $$i`; done
	for i in `$(LS) tools` "" ; do test -z "$$i" || rm -f $(INSTALL_BIN)/`basename $$i`; done

# Use ant to actually do the work for these targets
jar test: _always
	$(ANT) -k -DTEST_PORT=$${CCN_LOCAL_PORT_BASE:-63000} $@

clean: _always
	$(ANT) -k clean
	rm -f _always $(JAR)

test: test-alone
test-alone: _always
	netstat -a -n | grep -v TIME_WAIT > test-alone
	grep '^..p.*[.]'$${CCN_LOCAL_PORT_BASE:-63000} test-alone || rm test-alone
	test \! -f test-alone        # Fail here if conflicting tests are running

documentation: _always
	doxygen

dist-docs: _always
	doxygen Doxyfile.dist

testinstall:
	@echo No $@ target for `pwd`
 
default all clean depend test check shared install uninstall: _always

_always:

.PHONY: _always
