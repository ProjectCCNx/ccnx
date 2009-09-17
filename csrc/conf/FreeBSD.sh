# conf/FreeBSD.sh
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
echo "# FreeBSD.sh $@ was here" >> conf.mk
set -x
mkdir -p include
find include lib -type l -name \*expat\* -exec rm '{}' ';'
# XXX fix this - should check for version installed by ports and use that
# Make some symlinks so that we can use the native expat.
ln -s /usr/include/bsdxml.h include/expat.h
ln -s /usr/lib/libbsdxml.a lib/libexpat.a
