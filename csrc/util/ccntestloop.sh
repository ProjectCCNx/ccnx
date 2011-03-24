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
# Results of the test runs are kept in the testdir subdirectory, which is
# created if necessary.  However, testdir may be a symlink to another
# directory (preferably on the same file system).  It is advisable to
# link to some location outside of the workspace to avoid loss of test
# results due to a "git clean" command.
#
# The testdir/config will be sourced as a sh script upon startup.  This allows
# the environment variables to be set up for the next round.  Creative use
# of this allows for such things as testing various combinations of parameters
# on each run.  Look in testdir/config.defaults for examples.
#
# There is provision for customization by means of executable hooks
# called at various stages:
# If testdir/hooks/update is present and executable, it will be executed
# to update sources befor each run.  If it returns non-zero status, the
# testing will be stopped.  The default behavior is to pull from a configurable
# git branch unless modifed files are present.
# If testdir/hooks/success is present and executable, it will be executed
# after every successful run.  It should return a status of 0 to continue
# on to the next run, or nonzero to stop.  The default is to continue.
# If testdir/hooks/failure is present and executable, it will be executed
# after every unsuccessful run.  The status checked in the same way, but
# the default is to stop.
# The run number is passed as a argument to these hooks. 
#
# One recommended strategy is to set up testdir/hooks/failure to notify
# you of the failure and then stop.  Then set up a cron job to start the
# test loop several times per day (and do nothing if it is running).
# That way you end up with a bounded number of failures to look at each day,
# but when all is well you test continuously. 
#
 
Usage () {
cat << EOM >&2
usage: ccntestloop [ start | stop | restart | status ]
With no argument test run in foreground
(Must be run from the top level of a ccnx source tree.)
EOM
exit 1
}

Defaults () {
CCN_TEST_GITCOMMAND=`command -v git || echo :`
test -d .git || CCN_TEST_GITCOMMAND=:

cat << EOF
# EDIT config, NOT config.defaults
# Control the overall log level of the java tests.
CCN_LOG_LEVEL_ALL=WARNING

# These may be used to contol which tests are run.
# Leave empty for the default set. Say NO for none.
CCN_CTESTS=
CCN_JAVATESTS=

# Occasionally a different make program is needed.
MAKE=make

# These are only used if hooks/update is not present
CCN_TEST_BRANCH=HEAD
CCN_TEST_GITCOMMAND=$CCN_TEST_GITCOMMAND
EOF
}

GetConfiguration () {
	Defaults > testdir/config.defaults || Fail testdir/config.defaults
	# Export all variables set while reading config
	set -a
	rm -f testdir/config~
	. testdir/config.defaults
	test -f testdir/config && . testdir/config && \
		cp testdir/config testdir/config~
	set +a
}

Echo () {
	echo "$*" >&2
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
	test -d javasrc || Fail ccntestloop is intended to be run at the top level of ccnx
	test -d testdir/. || mkdir testdir
	mkdir -p testdir/hooks
}

BackgroundPID () {
	local PID;
	if [ -f testdir/ccntestloop.pid ]; then
		PID=`cat testdir/ccntestloop.pid`
		kill -0 "$PID" 2>/dev/null || return 1
		echo ${1:-''}$PID
		return 0
	fi
	return 1
}

SetPIDFile () {
	local PID;
	if [ -f testdir/ccntestloop.pid ]; then
		PID=`cat testdir/ccntestloop.pid`
		test "$PID" = $$ && return 0
		kill -0 "$PID" 2>/dev/null && Fail ccntestloop already running as pid $PID
		rm testdir/ccntestloop.pid || Fail could not remove old pid file
	fi
	echo $$ > testdir/ccntestloop.pid || Fail could not write pid file
}

RemovePIDFile () {
	test "`cat testdir/ccntestloop.pid`" = $$ && rm -f testdir/ccntestloop.pid
}

CheckTestout () {
	test -d javasrc/testout               && \
	  rm -rf javasrc/testout~             && \
	  mv javasrc/testout javasrc/testout~ && \
	  Echo WARNING: existing javasrc/testout renamed to javasrc/testout~
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
	if [ -f javasrc/testout/KILLED ]; then
		rm -rf javasrc/testout
		exit
	fi
	PrintDetails > javasrc/testout/TEST-details.txt
	# grep -e BUILD -e 'Total time.*minutes' javasrc/testout/TEST-javasrc-testlog.txt
	mv javasrc/testout testdir/testout.$1
}

PruneOldLogs () {
	# Leave 20 most recent successes
	(cd testdir; rm -rf `ls -dt testout.*[0123456789] | tail -n +20`; )
	true
}

UpdateSources () {
	Echo Updating for run $1
	cp csrc/util/ccntestloop.sh testdir/.~ctloop~
	if [ -x testdir/hooks/update ]; then
		testdir/hooks/update $1 || Fail testdir/hooks/update
		return 0	
	fi
	$CCN_TEST_GITCOMMAND status | grep modified:        && \
	  Echo Modifications present - skipping update      && \
	  sleep 3 && return
	$CCN_TEST_GITCOMMAND checkout $CCN_TEST_BRANCH      && \
	  $CCN_TEST_GITCOMMAND pull origin $CCN_TEST_BRANCH
}

SourcesChanged () {
	: For now, always rebuild
	true
}

ScriptChanged () {
	diff testdir/.~ctloop~ csrc/util/ccntestloop.sh && return 1
	return 0 # Yes, it changed.
}

NoteTimes () {
	times > javasrc/testout/$1.times
}

Rebuild () {
	local LOG;
	Echo Building for run $1
	LOG=javasrc/testout/TEST-buildlog.txt
	mkdir -p javasrc/testout
	(./configure && $MAKE; ) > $LOG 2>&1
	if [ $? -eq 0 ]; then
		NoteTimes postb
		return 0
	fi
	tail $LOG
	Echo build failed
	return 1
}

RunCTest () {
	local LOG STATUS;
	test "${CCN_CTESTS:=}" = "NO" && return 0
	Echo Running csrc tests...
	LOG=javasrc/testout/TEST-csrc-testlog.txt
	mkdir -p javasrc/testout
	( cd csrc && $MAKE test TESTS="$CCN_CTESTS" 2>&1 ) > $LOG
	STATUS=$?
	NoteTimes postc
	test $STATUS -eq 0 && return 0
	tar cf javasrc/testout/csrc-tests.tar csrc/tests
	gzip javasrc/testout/csrc-tests.tar
	grep ^FAILING: $LOG | tee -a javasrc/testout/TEST-failures.txt
	Echo csrc tests failed
	return 1
}

RunJavaTest () {
	local LOG;
	test "${CCN_JAVATESTS:-}" = "NO" && return 0
	Echo Running javasrc tests...
	LOG=javasrc/testout/TEST-javasrc-testlog.txt
	(cd javasrc && \
	  ant -DCHATTY=${CCN_LOG_LEVEL_ALL}             \
	      -DTEST_PORT=${CCN_LOCAL_PORT_BASE:-63000} \
	      ${CCN_JAVATESTS:-test}; ) > $LOG
	STATUS=$?
	NoteTimes postj
	test $STATUS -eq 0 && return 0
	grep -B1 -e 'junit. Tests .*Failures: [^0]' \
	         -e 'junit. Tests .*Errors: [^0]'   \
	     $LOG 2>/dev/null | tee -a javasrc/testout/TEST-failures.txt
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

ExecSelf () {
	exec sh csrc/util/ccntestloop.sh || Fail exec
}

StartBackground () {
	BackgroundPID 'ccntestloop already running as pid ' && return 1
	sh -c "sh csrc/util/ccntestloop.sh &" < /dev/null >> testdir/log 2>&1
}

StopBackground () {
	local PID;
	PID=`BackgroundPID` && kill -HUP $PID && sleep 1 && \
	  echo STOPPED >> testdir/log
}

Status () {
	local STATUS;
	test -f testdir/log && tail -n 9 testdir/log
	BackgroundPID 'ccntestloop running as pid '
	STATUS=$?
	test $STATUS = 0 || echo ccntestloop not running
	return $STATUS
}

FailHook () {
	if [ -x testdir/hooks/failure ]; then
		testdir/hooks/failure $1 && sleep 10 && ExecSelf
	fi
	Fail run $1 failed - stopping
}

# Finally, here's what we actually want to do
CheckDirectory

GetConfiguration

CheckLogLevel

if [ $# -gt 1 ]; then
	Usage
fi

case "${1:---}" in
	--)      ;;
	-*)      Usage;;
	start)   StartBackground; exit $?;;
	stop)    StopBackground; exit 0;;
	restart) StopBackground; StartBackground; exit $?;;
	status)  Status; exit $?;;
	*)       Usage;;
esac

SetPIDFile
trap RemovePIDFile EXIT

CheckTestout

RUN=`ThisRunNumber`

UpdateSources $RUN

if ScriptChanged; then
	Echo "*** Script changed - will clean and restart"
	$MAKE clean >.make.clean.log 2>&1|| Fail make clean - see .make.clean.log
	rm -f .make.clean.log
	echo Pausing for 10 seconds before restart...
	sleep 10
	ExecSelf
fi

if SourcesChanged; then
	Rebuild $RUN || Fail make
fi

RunTest $RUN || FailHook $RUN
Echo Run number $RUN was successful
Echo BUILD Failure rate is `ls -d testdir/testout*FAILED 2>/dev/null | wc -l` / $RUN
if [ -x testdir/hooks/success ]; then
	testdir/hooks/success $RUN || exit 0
fi
sleep 2
ExecSelf
