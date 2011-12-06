# conf/Darwin.mk
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
SHEXT=dylib
SHLIBNAME=libccn.1.$(SHEXT)
SHLIBDEPS=/usr/lib/dylib1.o
SHARED_LD_FLAGS = -dylib -arch `/usr/bin/arch` -install_name $(SHLIBNAME) $(OPENSSL_LIBS) -all_load /usr/lib/dylib1.o -lcrypto -lSystem
PLATCFLAGS=-fno-common
CWARNFLAGS = -Wall -Wpointer-arith -Wreturn-type -Wstrict-prototypes
