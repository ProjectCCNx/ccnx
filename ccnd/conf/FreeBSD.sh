#!/bin/sh
# Make some symlinks so that we can use the native expat.
set -x
find include lib -type l -exec rm '{}' ';'
ln -s /usr/include/bsdxml.h include/expat.h
ln -s /usr/lib/libbsdxml.a lib/libexpat.a
