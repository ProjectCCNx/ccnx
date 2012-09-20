# schema/Makefile
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

SUBDIRS = validation

default all clean depend coverage check test shared testinstall install uninstall: _always
	for i in $(SUBDIRS); do         \
	  (cd "$$i" && pwd && $(MAKE) $@) || break;			\
	done

documentation dist-docs html: _always
	@:

_always: 

.PHONY: _always 
