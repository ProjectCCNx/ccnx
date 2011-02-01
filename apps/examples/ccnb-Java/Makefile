# apps/examples/ccnb-Java/Makefile
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009,2011 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

WHINE = sh -c "type $(ANT) 2>/dev/null ||                  \
               echo Skipping java build in $$(pwd -L) -    \
                    $(ANT) is not installed; "
WARN_NO_INSTALL = echo Nothing installed from apps/examples/ccnb-Java/
JAR = ccnb-Java.jar

include ../../java.mk

all: ${JAR} 

ccn.jar: 

${JAR}: ccn.jar compile 

compile:
	$(ANT) > /dev/null && $(ANT) -k $@ || $(WHINE)

#clean:
#	$(ANT) clean
#	rm -f ccnb-Java.jar

default all clean depend: _always

_always:

.PHONY: _always

