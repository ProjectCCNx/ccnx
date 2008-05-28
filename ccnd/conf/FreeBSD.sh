#!/bin/sh
# Make some symlinks so that we can use the native expat.
set -x
mkdir -p include
find include lib -type l -name \*expat\* -exec rm '{}' ';'
ln -s /usr/include/bsdxml.h include/expat.h
ln -s /usr/lib/libbsdxml.a lib/libexpat.a
