# csrc/generic.mk
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#
# This is a generic Makefile that is put into each subdirectory
# so that the developer make easily build just that subdirectory,
# possibly with different CFLAGS, etc.
# This is not used at all for a make from the top level.

default all clean depend test check shared install uninstall: _always
	SELF=`basename \`pwd\``; (cd .. && $(MAKE) SUBDIRS=$$SELF $@)

_always:

.PHONY: _always
