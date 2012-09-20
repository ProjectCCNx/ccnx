# doc/Makefile
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2012 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

# SUBDIRS indicates the subdirectories to be built by this makefile.

SUBDIRS = technical manpages
MAKEF =  -f ../conf.mk -f Makefile

default:
	: To clean the documentation: make clean-documentation

all html clean clean-documentation depend install uninstall documentation dist-docs: conf.mk _always
	for i in $(SUBDIRS); do                                 \
	    (cd "$$i" && pwd && $(MAKE) $(MAKEF) $@) || exit 1; \
	done
	@rm -f _always

test check shared:
	@: nothing to do

conf.mk:
	test -f ../csrc/$@ && ln -s ../csrc/$@

_always:

.PHONY: _always
