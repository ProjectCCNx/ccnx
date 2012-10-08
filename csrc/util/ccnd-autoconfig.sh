# Source file: util/ccnd-autoconfig.sh
# 
# Script that tries to (automatically) discover of a local ccnd gateway
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

# This script should be installed in the same place as ccnd, ccndc, ccndsmoketest, ...
# adjust the path to get consistency.
D=`dirname "$0"`
export PATH="$D:$PATH"

ccndstatus | grep 224.0.23.170:59695 > /dev/null
MCAST_EXISTED=$?

# Removing any previously created (either by this script or ccndc srv command) default route
for i in `ccndstatus | grep "ccnx:/autoconf-route face" | awk '{print $3}'`; do 
   ccndc del / face $i
   ccndc del /autoconf-route face $i
done

# Set temporary multicast face
ccndc -t 10 add "/local/ndn" udp  224.0.23.170 59695 

# Get info from local hub, if available
info=`ccncat -s 2 /local/ndn/udp`
if [ "x$info" = "x" ]; then
   echo "Local hub is not availble, trying to use DNS to get local configuration"
   # Try to use DNS search list to get default route information
   ccndc srv

   if [ $MCAST_EXISTED -eq 1 ]; then
      # destroying multicast face
      ccndstatus | grep 224.0.23.170:59695 | awk '{print $2}' | xargs ccndc destroy face
   fi
   exit 1
fi

echo Setting default route to a local hub: "$info"
echo "$info" | xargs ccndc add / udp
echo "$info" | xargs ccndc add /autoconf-route udp

if [ $MCAST_EXISTED -eq 1 ]; then
   # destroying multicast face
   ccndstatus | grep 224.0.23.170:59695 | awk '{print $2}' | xargs ccndc destroy face
fi

