# conf/OpenSSL.mk
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2010 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

################################################################
#
# This provides a way to build CCNx using a version of OpenSSL that you
# have built and installed, instead of using the system's version.
# To use this, copy this file into conf/local.mk and customize as needed,
# then do the top-level ./configure && make.
#
# When you are configuring openssl, be sure to specify the "shared" option.
#

# This is the prefix that openssl installs into by default.
OPENSSL_PREFIX = ~/PARC/openssl/openssl-0.9.8x

OPENSSL_CFLAGS = -I$(OPENSSL_PREFIX)/include

# On some platforms, lib may need adjustment, e.g. lib64 on x86_64 GNU/Linux
OPENSSL_LIBS = -R$(OPENSSL_PREFIX)/lib -L$(OPENSSL_PREFIX)/lib
