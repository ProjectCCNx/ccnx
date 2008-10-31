#!/bin/sh
PORT=`id -u`
killall udplink
sleep 1
udplink/udplink -h 224.239.204.110 -r $PORT -l $PORT -t 5 &
#udplink/udplink -h heneryhawk -r 2$PORT -l 2$PORT &
