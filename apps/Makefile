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
OBJTREE = build
MAKEF =  -f ../conf.mk -f dir.mk -f ../subr.mk
CFLAGS = -g
CSRCDIR = ../csrc

default all clean depend test check shared install uninstall: conf.mk subr.mk generic.mk _always
	test -d include/ccn || (test -d $(CSRCDIR)/include/ccn && ln -s $(CSRCDIR)/include)
	for i in $(SUBDIRS); do         \
	  (cd "$$i" && pwd && $(MAKE) $(MAKEF) "COPT=$(CFLAGS)" CFLAGS='$$(REAL_CFLAGS)' SRCDIR=../$(SRCDIR)/$$i $@) || exit 1;	\
	done
	@test -d $(OBJTREE) && echo "+++ NOTE - $(OBJTREE) exists" ||:
	@rm -f _always

subr.mk generic.mk conf.mk:
	test -f $(CSRCDIR)/$@ && ln -s $(CSRCDIR)/$@

test check: default

objtree: conf.mk _always
	mkdir -p $(OBJTREE)
	echo SRCDIR = ..  > $(OBJTREE)/Makefile
	cat Makefile     >> $(OBJTREE)/Makefile
	for i in $(SUBDIRS); do						\
	  mkdir -p $(OBJTREE) &&					\
	    (cd $$i && pwd &&						\
		$(MAKE) $(MAKEF) OBJDIR=../$(OBJTREE)/$$i SRCDIR=. 	\
			../$(OBJTREE)/$$i/dir.mk	 		\
	     || exit 1);						\
	done
	cd $(OBJTREE) && ../configure

documentation: _always
	@: not yet

dist-docs: _always
	@: not yet

_always:

.PHONY: _always
