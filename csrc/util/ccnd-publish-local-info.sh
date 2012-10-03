#!/bin/sh

udp_face_info=$1
local_prefix=$2

if [ "x$udp_face_info" = "x" -o "x$local_prefix" = "x" ]; then
   echo "Usage: "
   echo "      " $0 " <udp_face_info> <routable_prefix>"
   exit 1
fi

echo $udp_face_info | ccnseqwriter -r -x 5 "/local/ndn/udp"
echo $local_prefix  | ccnseqwriter -r -x 5 "/local/ndn/prefix"

