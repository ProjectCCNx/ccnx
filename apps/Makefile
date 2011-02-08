# apps/Makefile
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2011 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

# SUBDIRS indicates the c subdirectories to be built by this makefile using
# the machinery of the csrc subtree.
SUBDIRS = HttpProxy
MAKEF =  -f ../conf.mk -f dir.mk -f ../subr.mk
CFLAGS = -g
CSRCDIR = ../csrc

default all clean depend test check shared install uninstall: conf.mk subr.mk generic.mk _always
	test -d include/ccn || (test -d $(CSRCDIR)/include/ccn && ln -s $(CSRCDIR)/include)
	for i in $(SUBDIRS); do         \
	  (cd "$$i" && pwd && $(MAKE) $(MAKEF) "COPT=$(CFLAGS)" CFLAGS='$$(REAL_CFLAGS)' SRCDIR=../$(SRCDIR)/$$i $@) || exit 1;	\
	done
	@rm -f _always

subr.mk generic.mk conf.mk:
	test -f $(CSRCDIR)/$@ && ln -s $(CSRCDIR)/$@

test check: default

documentation: _always
	@: not yet

dist-docs: _always
	@: not yet

_always:

.PHONY: _always
