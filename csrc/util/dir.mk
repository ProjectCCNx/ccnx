# util/dir.mk
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

SCRIPTSRC = ccndstart.sh shebang
PROGRAMS = $(INSTALLED_PROGRAMS)
INSTALLED_PROGRAMS = ccndstart

default all: $(SCRIPTSRC) $(PROGRAMS)

ccndstart: ccndstart.sh
	./shebang $(SH) ccndstart.sh > ccndstart
	chmod +x ccndstart

clean:
	rm -f $(PROGRAMS)

test:
	@echo "Sorry, no libexec unit tests at this time"
