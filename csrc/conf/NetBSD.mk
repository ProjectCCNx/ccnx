# conf/NetBSD.mk
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009-2013 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#
SHEXT=so
SHLIBNAME=libccn.$(SHEXT).1
SHLIBDEPS=
SHARED_LD_FLAGS= -shared -whole-archive -soname=$(SHLIBNAME) -lc
PLATCFLAGS=-fPIC
RESOLV_LIBS=
CPREFLAGS = -I/usr/pkg/include
MORE_LDLIBS = -L/usr/pkg/lib -R/usr/pkg/lib
