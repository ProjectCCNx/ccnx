echo "# Linux.sh $@ was here" >> conf.mk
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
  if test "$PYTHONVERSION" == 1; then
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
