# conf/FreeBSD.sh
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009-2010 Palo Alto Research Center, Inc.
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
test -f /usr/local/include/expat.h && exit 0
echo "# Using bsdxml.h for expat - recommend installing expat package and repeat ./configure" >> conf.mk
# Make some symlinks so that we can use the native expat.
ln -s /usr/include/bsdxml.h include/expat.h
ln -s /usr/lib/libbsdxml.a lib/libexpat.a
