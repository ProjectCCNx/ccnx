# apps/examples/scripts/guestprefix.sh
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2012 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

# This is an example script that demonstrates how a node that is acting as
# a local hub can provide to local clients a guest prefix that can be routed
# to that client.
#
# In almost all cases, some local customization will be needed.
#
# To use: sh apps/examples/scripts/guestprefix.sh run
#

# This should be edited to contain a prefix that will get routed to
# our hub from everywhere.
PREFIX_STEM=ccnx:/xyzzy/example-site

# This is the prefix that we will use to publish the guest prefixes under.
# Since each guest gets a unique prefix, we publish them under distinct names.
# The IP address is incorporated into this distinctive part, since it is a
# piece of infomation shared by the guest node and the hub node.  Note that
# the IP address is not in the guest prefix itself, only in the name used to
# convey it to the guest.
LP=ccnx:/local/prefix

# for logging
SELF=$0

# put our start time in the generated names
GEN=`date +%s`

# We will need our CCNDID
# This is one way to get it, complete with %-escapes for use in a URI
ccndid () {
    ccndstatus | sed -n 's@^ ccnx:/ccnx/\(.................................*\) face: 0 .*$@\1@p'
}

CCNDID=`ccndid`

if [ "$CCNDID" = "" ]; then
    echo Unable to get CCNDID >&2
fi

# This is called when a new ipv4 face is made.
# This may be caused by traffic over udp, a configured prefix, or
# an incoming tcp connection that carries ccnx traffic.
incoming_ip4 () { # PROTO IP PORT
    # FLAGS and FACE are alredy set by caller
    PROTO=$1
    IP=$2
    PORT=$3
    case $IP in
        0.0.0.0) ;;   # Ignore wildcard
        127.0.0.1) ;; # Ignore localhost
        255.255.255.255) ;; # Ignore broadcast
        *.*.*.*)    # This pattern may be more specific, e.g. the local subnet
            GUEST_PREFIX=$PREFIX_STEM/$GEN/guest$FACE
            ccndc add $GUEST_PREFIX $PROTO $IP $PORT
            echo $GUEST_PREFIX | ccnseqwriter -x 5 -r $LP/ip~$IP
            ;;
        *)  logger -i -s -t $SELF -- I do not recognize $IP;;
    esac
}

# Used for splitting the host from the port.
# IPv6 numeric hosts may contain colons, so split at the last one.
split_last_colon () {
    echo $1 | sed -e 's/:\([^:]*\)$/ \1/'
}

# Called with each face status event.
process_event () { # FUNC FACE FLAGS REMOTE;
    FUNC=$1
    FACE=$2
    FLAGS=$3
    REMOTE=$4
    #echo FUNC=$FUNC FACE=$FACE FLAGS=$FLAGS REMOTE=$REMOTE
    case $FUNC+$FLAGS in
        newface+0x*10) incoming_ip4 tcp `split_last_colon $REMOTE`;;
        newface+0x*12) incoming_ip4 udp `split_last_colon $REMOTE`;;
    esac
}

# Reads the stream of events and calls process_event for each
process_events () {
  while read LINE; do
    echo =============== $LINE >&2
    echo $LINE | tr '(,);' '    ' | { read FUNC FACE FLAGS REMOTE ETC;
        process_event $FUNC $FACE $FLAGS $REMOTE; }
    done
}

init_temporary_repo () {
    export CCNR_DIRECTORY=/tmp/gp.$$.dir
    pfx=/local/$CCNDID
    mkdir -p $CCNR_DIRECTORY || exit 1
    echo CCNR_DIRECTORY=$CCNR_DIRECTORY     >> $CCNR_DIRECTORY/config
    echo CCNS_ENABLE=0                      >> $CCNR_DIRECTORY/config
    echo CCNR_START_WRITE_SCOPE_LIMIT=1     >> $CCNR_DIRECTORY/config
    echo CCNR_GLOBAL_PREFIX=$pfx            >> $CCNR_DIRECTORY/config
    ccnr 2>>$CCNR_DIRECTORY/log &
}

guestprefix_run () {
    ccnpeek -c ccnx:/ccnx/$CCNDID/notice.txt > /dev/null
    ccnsimplecat ccnx:/ccnx/$CCNDID/notice.txt | process_events
}

# Only run if asked, so that this script may be sourced in a wrapper script
if [ "$1" = "run" ]; then
    init_temporary_repo
    guestprefix_run
fi
