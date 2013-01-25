# Source file: util/ccndstatus.sh
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
# This script should be installed in the same place as ccnd, ccndc, ccndsmoketest, ...
# adjust the path to get consistency.
Usage () {
	echo $0 [-T host] - display the status of a running ccnd >&2
	exit 1
}

D=`dirname "$0"`
export PATH="$D:$PATH"
host=
xmlout=
while getopts T:x name; do
	case $name in
	T) host="$OPTARG";;
  x) xmlout="-x";;
	?) Usage;;
	esac
done

if [ ! -z "$host" ]; then
	set -- -T "$host"
else
	shift $#
fi

ccndsmoketest "$@" status "$xmlout"
