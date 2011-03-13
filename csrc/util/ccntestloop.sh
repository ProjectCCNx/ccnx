# Source file: util/ccntestloop.sh
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2010, 2011 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

#
# ccntestloop runs the ccnx unit tests repeatedly.
#
# This is intended to be run from the top level of the ccnx distribution.
# Results of the test runs aro kept in the testdir subdirectory, which is
# created if necessary.  However, testdir may be a symlink to another
# directory (preferable on the same file system).  It is advisable to
# link to some location outside of the workspace to avoid loss of test
# results due to a "git clean" command.
#
# The testdir/config will be sourced as a sh script upon startup.  This allows
# the environment variables to be set up for the next round.  Creative use
# of this allows for such things as testing various combinations of parameters
# on each run.

THIS=$0

Usage () {
cat << EOM >&2
usage: `basename $THIS` 
(Must be run from the top level of a ccnx source tree.)
EOM
exit 1
}

GetConfiguration () {
	set -a
	rm -f testdir/config~
	test -f testdir/config && . testdir/config && \
		cp testdir/config testdir/config~
	set +a
}

ProvideDefaults () {
	# Provide defaults for environment
	: ${CCN_LOG_LEVEL_ALL:=WARNING}
	: ${CCN_TEST_BRANCH:=HEAD}
	: ${CCN_TEST_GITCOMMAND:=`command -v git || echo :`}
	: ${MAKE:=make}
	test -d .git || CCN_TEST_GITCOMMAND=:
	export CCN_CTESTS
	export CCN_JAVATESTS
	export CCN_LOG_LEVEL_ALL
	export CCN_TEST_BRANCH
	export CCN_TEST_GITCOMMAND
	export MAKE
}

# Say things to both stdout and stderr so that output may be captured with tee.
Echo () {
	echo "$*" >&2
	echo "$*"
}

Fail () {
	Echo '***' Failed - "$*"
	exit 1;
}

CheckLogLevel () {
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
}

CheckDirectory () {
	test -d javasrc || Fail $THIS is intended to be run at the top level of ccnx
	test -d javasrc/testout               && \
	  rm -rf javasrc/testout~             && \
	  mv javasrc/testout javasrc/testout~ && \
	  Echo WARNING: existing javasrc/testout renamed to javasrc/testout~
	test -d testdir/. || mkdir testdir
}

PrintDetails () {
	uname -a
	env | grep ^CCN
	$CCN_TEST_GITCOMMAND rev-list --max-count=1 HEAD
	$CCN_TEST_GITCOMMAND status
	$CCN_TEST_GITCOMMAND diff | cat
	head -999 csrc/conf.mk testdir/config~ 2>/dev/null
}

SaveLogs () {
	test -d javasrc/testout || return 1
	PrintDetails > javasrc/testout/TEST-details.txt
	grep -e BUILD -e 'Total time.*minutes' javasrc/testout/TEST-javasrc-testlog.txt
	mv javasrc/testout testdir/testout.$1
}

PruneOldLogs () {
	Echo Pruning logs from older successful runs
	# Leave 20 most recent successes
	(cd testdir; rm -rf `ls -dt testout.*[0123456789] | tail -n +20`; )
	true
}

UpdateSources () {
	Echo Updating for run $1
	$CCN_TEST_GITCOMMAND status | grep modified:         && \
		Echo Modifications present - skipping update && \
		sleep 3 && return
	$CCN_TEST_GITCOMMAND checkout $CCN_TEST_BRANCH && \
	$CCN_TEST_GITCOMMAND pull origin $CCN_TEST_BRANCH
}

SourcesChanged () {
	: For now, always rebuild
	true
}

ScriptChanged () {
	trap "rm .~ctloop~" 0
	tail -n +2 $THIS > .~ctloop~
	diff .~ctloop~ csrc/util/ccntestloop.sh && return 1
	return 0 # Yes, it changed.
}

Rebuild () {
	local LOG;
	Echo Building for run $1
	LOG=javasrc/testout/TEST-buildlog.txt
	mkdir -p javasrc/testout
	(./configure && $MAKE; ) > $LOG 2>&1 && return 0
	tail $LOG
	Echo build failed
	return 1
}

RunCTest () {
	local LOG;
	test "$CCN_CTESTS" = "NO" && return 0
	Echo Running csrc tests...
	LOG=javasrc/testout/TEST-csrc-testlog.txt
	mkdir -p javasrc/testout
	( cd csrc && $MAKE test TESTS="$CCN_CTESTS" 2>&1 ) > $LOG && return 0
	tar cf javasrc/testout/csrc-tests.tar csrc/tests
	gzip javasrc/testout/csrc-tests.tar
	Echo csrc tests failed
	return 1
}

RunJavaTest () {
	local LOG;
	test "${CCN_JAVATESTS}" = "NO" && return 0
	Echo Running javasrc tests...
	LOG=javasrc/testout/TEST-javasrc-testlog.txt
	
	(cd javasrc && \
	  ant -DCHATTY=${CCN_LOG_LEVEL_ALL}             \
	      -DTEST_PORT=${CCN_LOCAL_PORT_BASE:-63000}  \
              "${CCN_JAVATESTS:-test}"; ) > $LOG        \
	  && return 0
	tail $LOG
	Echo javasrc tests failed
	return 1
}

RunTest () {
	Echo Starting run $1 at `date`
	RunCTest && RunJavaTest && SaveLogs $1 && PruneOldLogs && return 0
	SaveLogs $1.FAILED
	return 1
}

LastRunNumber () {
	ls -td testdir/testout.* 2>/dev/null | head -n 1 | cut -d . -f 2
}

ThisRunNumber () {
	echo $((`LastRunNumber` + 1))
}

# Finally, here's what we actually want to do
GetConfiguration

ProvideDefaults

CheckDirectory

CheckLogLevel

RUN=`ThisRunNumber`

UpdateSources $RUN

if ScriptChanged; then
	Echo "*** Script changed - will clean, rebuild and restart"
	$MAKE clean || Fail make clean
	$MAKE || Fail make
	echo Pausing for 10 seconds before restart...
	sleep 10
	exec ./bin/ccntestloop || Fail exec
fi

if SourcesChanged; then
	Rebuild $RUN || Fail make
fi

RunTest $RUN || Fail RunTest - stopping
Echo Run number $RUN was successful
Echo BUILD Failure rate is `ls -d testdir/testout*FAILED 2>/dev/null | wc -l` / $RUN
sleep 2
exec ./bin/ccntestloop || Fail exec
