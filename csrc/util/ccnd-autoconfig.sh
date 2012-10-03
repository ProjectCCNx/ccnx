#!/bin/sh

ccndc -t 10 add "/local/ndn" udp  224.0.23.170 59695 

info=`ccncat -s 2 /local/ndn/udp`
if [ "x$info" = "x" ]; then
   echo "Local hub is not availble, trying to use DNS to get local configuration"
   ccndc srv
   exit 1
fi

echo Setting default route to a local hub: "$info"
echo "$info" | xargs ccndc add / udp
