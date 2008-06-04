echo "# FreeBSD.sh $@ was here" >> conf.mk
#!/bin/sh
# Make some symlinks so that we can use the native expat.
set -x
mkdir -p include
find include lib -type l -name \*expat\* -exec rm '{}' ';'
ln -s /usr/include/bsdxml.h include/expat.h
ln -s /usr/lib/libbsdxml.a lib/libexpat.a
# check for python that's advanced enough
PYTHON=`which python`
if test "$PYTHON" != ""; then 
  PYTHONVERSION=`$PYTHON <<EOF
try:
    import ctypes
except:
    print 0
else:
    print 1 
EOF`
  if test "$PYTHONVERSION" -eq 1; then
     echo "PYTHON=$PYTHON" >> conf.mk
     echo "PYTHON_TARGETS=python" >> conf.mk
  else
     echo "PYTHON=" >> conf.mk
     echo "PYTHON_TARGETS=" >> conf.mk
  fi
else
   echo "PYTHON=" >> conf.mk
   echo "PYTHON_TARGETS=" >> conf.mk
fi
