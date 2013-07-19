# conf/SunOS.mk
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
MORE_LDLIBS = -lmtmalloc -lnsl -lsocket -L/usr/sfw/lib -R/usr/sfw/lib
CPREFLAGS = -I/usr/sfw/include
SHEXT = so
SHLIBNAME=libccn.$(SHEXT).1
SHLIBDEPS=
SHARED_LD_FLAGS = -G -z allextract
INSTALL = ginstall
MAKE = gmake
