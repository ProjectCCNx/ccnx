# Start ccnd in the background and set up forwarding according to configuration

# This script should be installed in the same place as ccnd, ccndc, ccndsmoketest, ...
# adjust the path to get consistency.
D=`dirname "$0"`
export PATH="$D:$PATH"

# Source a file containing settings, if present.
# To learn about things that you can set, use this command: ccnd -h
test -f $HOME/.ccn/ccndrc && . $HOME/.ccn/ccndrc

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
ccndsmoketest `find "$HOME/.ccn/keyCache" -type f -name \*.ccnb`

# Run ccndc if a static config file is present.
test -f $HOME/.ccn/ccnd.conf && ccndc -f $HOME/.ccn/ccnd.conf
