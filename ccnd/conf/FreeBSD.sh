echo "# FreeBSD.sh $@ was here" >> conf.mk
set -x
mkdir -p include
find include lib -type l -name \*expat\* -exec rm '{}' ';'
# XXX fix this - should check for version installed by ports and use that
# Make some symlinks so that we can use the native expat.
ln -s /usr/include/bsdxml.h include/expat.h
ln -s /usr/lib/libbsdxml.a lib/libexpat.a
