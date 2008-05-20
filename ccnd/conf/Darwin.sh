echo "# Darwin.sh $@ was here" >> conf.mk
# check for python that's advanced enough
PYTHON=`which python`
if test "$PYTHON" != ""; then 
  PYTHONVERSION=`$PYTHON -c "import sys; print ((sys.version_info >= (2, 5, 0)) and "1" or "0")"`
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
