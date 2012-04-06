# conf/SunOS.sh
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
PATH=/usr/xpg6/bin:/usr/xpg4/bin:$PATH
export PATH
echo SH = `which sh` >> conf.mk
whichcc=`which cc`
gcciscc="`echo $whichcc | grep gnu`"
echo Using CC found in $whichcc
if test \( -z "$gcciscc" \)
then
	echo "PLATCFLAGS = -mt -Kpic" >> conf.mk
else
	echo "PLATCFLAGS = " >> conf.mk
fi 
