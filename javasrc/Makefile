# javasrc/Makefile
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009-2012 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

default:

../csrc/conf.mk: Makefile
	false - top-level ./configure is needed

include ../csrc/conf.mk   # if missing, top-level ./configure is needed

JAR = ccn.jar
LIBS = $(JAR) lib/bcprov-jdk16-143.jar lib/kxml2-2.3.0.jar $(EXTRA_LIBS)
EXTRA_LIBS = `find lib/bcprov-jdk15-143.jar -print 2>/dev/null`
WARN_NO_INSTALL = echo Nothing installed from javasrc/
# Default, the top-level Makefile default target will
# call the install target here but override DINST_*
# with dir in the source tree

default all: jar
check: test

install:
	@test -f $(JAR) && $(MAKE) real_install \
            DINST_LIB=$(DINST_LIB)          \
            DINST_BIN=$(DINST_BIN) || $(WARN_NO_INSTALL)

real_install: _always
	test -d $(DINST_LIB) 
	for i in $(LIBS) ""; do test -z "$$i" || $(INSTALL) $$i $(DINST_LIB); done
	test -d $(DINST_BIN)
	# Using -R on . dir to preserve sym links
	$(CP) -R tools/. $(DINST_BIN)

uninstall:
	for i in $(LIBS) ""; do test -z "$$i" || rm -f $(DINST_LIB)/`basename $$i`; done
	for i in `$(LS) tools` "" ; do test -z "$$i" || rm -f $(DINST_BIN)/`basename $$i`; done

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
 
default all clean depend test check shared install uninstall html: _always

_always:

.PHONY: _always
