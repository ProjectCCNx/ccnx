# apps/Makefile
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2011-2012 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

# SUBDIRS indicates the subdirectories to be built by this makefile.
# If a dir.mk file is present in the subdirectory, it will be used
# in conjunction with the machinery of the csrc subtree.
# Otherwise it just does a recursive make.

SUBDIRS = HttpProxy ccnChat ccnFileProxy `cat local.subdirs 2>/dev/null || :`
MAKEF =  -f ../conf.mk -f dir.mk -f ../subr.mk $(PASSTHRU)
PASSTHRU = DINST_INC=$(DINST_INC) DINST_LIB=$(DINST_LIB) DINST_BIN=$(DINST_BIN)
CSRCDIR = ../csrc
CFLAGS = $(COPT)

include $(CSRCDIR)/conf.mk

default all clean depend test check shared install uninstall: conf.mk subr.mk generic.mk _always
	test -d include/ccn || (test -d $(CSRCDIR)/include/ccn && ln -s $(CSRCDIR)/include)
	for i in $(SUBDIRS); do           \
	    if [ -f "$$i/dir.mk" ]; then  \
	        (cd "$$i" && pwd && $(MAKE) $(MAKEF) "COPT=$(CFLAGS)" CFLAGS='$$(REAL_CFLAGS)' SRCDIR=../$(SRCDIR)/$$i $@) || exit 1;	\
	    else			  \
	        (cd "$$i" && pwd && $(MAKE) -f ../conf.mk -f Makefile $(PASSTHRU) $@) || exit 1;	\
	    fi                            \
	done
	@rm -f _always

subr.mk generic.mk conf.mk:
	test -f $(CSRCDIR)/$@ && ln -s $(CSRCDIR)/$@

test check: default

documentation dist-docs html: _always

_always:

.PHONY: _always
