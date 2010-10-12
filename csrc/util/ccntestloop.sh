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

# Provide defaults for environment
: ${CCN_LOG_LEVEL_ALL:=WARNING}
: ${CCN_TEST_BRANCH:=HEAD}
: ${CCN_TEST_GITCOMMAND:=`command -v git || echo :`}
: ${MAKE:=make}
test -d .git || CCN_TEST_GITCOMMAND=:
export CCN_LOG_LEVEL_ALL
export CCN_TEST_BRANCH
export CCN_TEST_GITCOMMAND
export MAKE

THIS=$0

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
	test -d javasrc/testout && Fail javasrc/testout directory is present.
}

PrintRelevantSettings () {
	uname -a
	set | grep ^CCN
	$CCN_TEST_GITCOMMAND rev-list --max-count=1 HEAD
	$CCN_TEST_GITCOMMAND status
}

PrintDetails () {
	uname -a
	$CCN_TEST_GITCOMMAND rev-list --max-count=1 HEAD
	$CCN_TEST_GITCOMMAND status
	$CCN_TEST_GITCOMMAND diff
	head -999 csrc/conf.mk
}

SaveLogs () {
	test -d javasrc/testout || return 1
	PrintDetails > javasrc/testout/TEST-details.txt
	grep -e BUILD -e 'Total time.*minutes' javasrc/testout/TEST-javasrc-testlog.txt
	mv javasrc/testout javasrc/testout.$1
}

PruneOldLogs () {
	Echo Pruning logs from older successful runs
	# Leave 20 most recent successes
	(cd javasrc; rm -rf `ls -dt testout.*[0123456789] | tail -n +20`; )
	true
}

UpdateSources () {
	Echo Updating for run $1
	$CCN_TEST_GITCOMMAND checkout $CCN_TEST_BRANCH
	$CCN_TEST_GITCOMMAND pull --no-commit origin $CCN_TEST_BRANCH
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
	(./configure && $MAKE; ) 2>&1 > $LOG && return 0
	tail $LOG
	Echo build failed
	return 1
}

RunCTest () {
	local LOG;
	Echo Running csrc tests...
	LOG=javasrc/testout/TEST-csrc-testlog.txt
	mkdir -p javasrc/testout
	( cd csrc && $MAKE test 2>&1 ) > $LOG && return 0
	tail $LOG
	Echo csrc tests failed
	return 1
}

RunJavaTest () {
	local LOG;
	Echo Running javasrc tests...
	LOG=javasrc/testout/TEST-javasrc-testlog.txt
	(cd javasrc && ant -DCHATTY=${CCN_LOG_LEVEL_ALL} test; ) > $LOG && return 0
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
	ls -td javasrc/testout.* 2>/dev/null | head -n 1 | cut -d . -f 2
}

ThisRunNumber () {
	echo $((`LastRunNumber` + 1))
}

# Finally, here's what we actually want to do

CheckLogLevel

CheckDirectory

RUN=`ThisRunNumber`

PrintRelevantSettings

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
Echo BUILD Failure rate is `ls -d javasrc/testout*FAILED 2>/dev/null | wc -l` / $RUN
sleep 2
exec ./bin/ccntestloop || Fail exec
