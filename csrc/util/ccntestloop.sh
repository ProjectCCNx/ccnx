# Source file: util/ccntestloop.sh
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

: ${CCN_LOG_LEVEL_ALL:=WARNING}
: ${CCN_TEST_BRANCH:=HEAD}
: ${CCN_TEST_GITCOMMAND:=`command -v git || :`}

Fail () {
    echo '***' Failed - $* >&2
    exit 1;
}

case ${CCN_LOG_LEVEL_ALL} in
    OFF) ;;
    SEVERE) ;;
    WARNING) ;;
    INFO) ;;
    CONFIG) ;;
    FINE) ;;
    FINER) ;;
    FINEST) ;;
    ALL) ;;
    *) Fail CCN_LOG_LEVEL_ALL=$CCN_LOG_LEVEL_ALL is invalid.
esac

export CCN_LOG_LEVEL_ALL
export CCN_TEST_BRANCH
export CCN_TEST_GITCOMMAND

test -d javasrc || Fail $0 is intended to be run at the top level of ccnx
test -d .git || CCN_TEST_GITCOMMAND=:

set | grep ^CCN

SaveLogs () {
    test -d testout && mv testout testout.$1
}

RunCTest () {
   # return 0
   echo Running csrc tests...
   ( cd ../csrc && make test 2>&1 ) > testout/logs/csrc-tests.log
   date
}

RunTest () { 
    echo;
    echo;
    echo;
    test -d testout && Fail testout directory is present.
    test -d testout.$1 && Fail testout.$1 directory is present.
    mkdir -p testout/logs
    echo Updating for run $1 >&2
    $CCN_TEST_GITCOMMAND checkout $CCN_TEST_BRANCH
    $CCN_TEST_GITCOMMAND pull --no-commit origin $CCN_TEST_BRANCH
    echo Building for run $1 >&2
    (cd .. && ${MAKE:-make}; ) || Fail did not build
    echo Starting run $1
    date;
    RunCTest && ant -DCHATTY=${CCN_LOG_LEVEL_ALL} test && SaveLogs $1 && return 0
    SaveLogs $1.FAILED
    return 1
}

LastRun () {
   ls -td javasrc/testout.* 2>/dev/null | head -n 1 | cut -d . -f 2
}

RunTests () {
    L=$((`LastRun` +0))
    I=$((L+1))
    ( cd javasrc; while RunTest $I; do I=$((I+1)); sleep 2; done; )
}

RunTests
