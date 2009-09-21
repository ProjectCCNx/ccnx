# Source file: util/ccndstart.sh
# Start ccnd in the background and set up forwarding according to configuration
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009 Palo Alto Research Center, Inc.
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
D=`dirname "$0"`
export PATH="$D:$PATH"

# Source a file containing settings, if present.
# To learn about things that you can set, use this command: ccnd -h
test -f $HOME/.ccnx/ccndrc && . $HOME/.ccnx/ccndrc

# Provide defaults
: ${CCND_CAP:=50000}
: ${CCND_DEBUG:=''}
export CCN_LOCAL_PORT CCND_CAP CCND_DEBUG

# If a ccnd is already running, try to shut it down cleanly.
ccndsmoketest -t 55 kill recv 2>/dev/null

# Fork ccnd, with a log file if requested.
if [ "$CCND_LOG" = "" ]
then
	ccnd &
else
	: >"$CCND_LOG" || exit 1
	ccnd 2>"$CCND_LOG" &
fi

# Stuff cached info about public keys, etc. into ccnd
ccndsmoketest `find "$HOME/.ccnx/keyCache" -type f -name \*.ccnb`

# Run ccndc if a static config file is present.
test -f $HOME/.ccnx/ccnd.conf && ccndc -f $HOME/.ccnx/ccnd.conf
