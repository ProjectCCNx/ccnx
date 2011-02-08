# apps/HttpProxy/Makefile
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

default all: _always
	make -f dir.mk

clean depend test check shared testinstall install uninstall: _always
	make -f dir.mk $@

_always:
.PHONY: _always
