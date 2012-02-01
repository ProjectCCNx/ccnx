#!/bin/bash

# Copyright (C) 2009,2010 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.

# $1 = download directory
# $2 = canonical repostory
# $3 = package file name
# $4 = http options

DOWNDIR=$1
REPO=$2
PKG=$3
OPTS=$4

# get it from $1

CURL=`which curl`
WGET=`which wget`
OPENSSL=`which openssl`

if [[ "$OPTS" != "" ]]; then
	HTTP_OPTS="?"$OPTS
	echo "Using opts $HTTP_OPTS"
else
	HTTP_OPTS=""
fi

function getit {
	if [[ "$CURL" != "" ]]; then
		echo "curl -L ${1}${HTTP_OPTS} > $2"
		$CURL -L "${1}${HTTP_OPTS}" > $2
	elif [[ "$WGET" != "" ]]; then
		echo "$WGET \"${1}${HTTP_OPTS}\""
		$WGET "${1}${HTTP_OPTS}"
	else
		echo "Could not find curl or wget!"
		exit -2
	fi
}

function checkhash {
	if [[ ! -r hashes/$PKG.sha1 ]]; then
		echo "Missing hash of $REPO"
		exit -3
	fi

	if [[ ! -r ${DOWNDIR}/$PKG ]]; then
		echo "File $PKG missing in directory '${DOWNDIR}'"
		return 1;
	fi

	# check the hash
	HASH_true=`cat hashes/$PKG.sha1`
	HASH_file=`${OPENSSL} dgst -sha1 ${DOWNDIR}/$PKG | awk '{print $2}'`

	if [[ "${HASH_true}" == "${HASH_file}" ]]; then
		echo "Match hash for ${DOWNDIR}/$PKG"
		exit 0
	else
		echo "Hash mismatch for $REPO, will re-download"
		echo "   true: $HASH_true"
		echo "   file: $HASH_file"
		return 1 
	fi
}

if [[ "${DOWNDIR}" == "" ]]; then
	echo "You must set DOWNDIR"
	exit -1
fi

if [[ "${OPENSSL}" == "" ]]; then
	echo "Could not find openssl"
	exit -1
fi

# If it is readable in directory and matches md5,
# exit

if [[ -r ${DOWNDIR}/$PKG ]]; then
	checkhash $PKG
fi

echo "Downloading $PKG from $REPO"
mkdir -p ${DOWNDIR}
pushd ${DOWNDIR}
getit $REPO/$PKG $PKG
popd

checkhash $PKG


