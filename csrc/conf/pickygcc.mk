# conf/pickygcc.mk
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
# very picky gcc flags
# To use, do something like ln -s pickygcc.mk local.mk
#
CWARNFLAGS = -Wall -Wswitch-enum -Wno-format-y2k -Wno-unused-parameter     \
             -Wstrict-prototypes -Wmissing-prototypes -Wpointer-arith      \
             -Wreturn-type -Wcast-qual -Wwrite-strings -Wswitch -Wshadow   \
             -Wcast-align -Wunused-parameter -Wchar-subscripts -Winline    \
             -Wnested-externs -Wredundant-decls -Wuninitialized -Wformat=2 \
             -Wno-format-extra-args -Wno-unknown-pragmas
COPT = -O
