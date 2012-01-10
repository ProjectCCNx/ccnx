# Copyright (C) 2009, 2010 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#
export

#
# Make all the Java jars and put them in "obj" directory.
# pjproject-1.4.5 is configured for the host platform and built,
# but not installed anywhere.

SUBDIRS := 
SUBDIRS_clean := $(addsuffix .clean,$(SUBDIRS))

.PHONY: subdirs mkdirs $(SUBDIRS_clean) openssl

# This is just a reference, the makefile does
# not do anything with them
ANDROID_SUBDIRS := openssl-1.0.0d expat

default all: mkdirs
	touch all_made

subdirs: $(SUBDIRS)

# This creates the directories & downloads sources
mkdirs:
	$(MAKE) -f Dirs.mk

clean: 
	@echo Use distclean if you want to clean an external project

$(SUBDIRS_clean):
	-$(MAKE) -C $(basename $@) clean

distclean: $(SUBDIRS_clean)
	$(MAKE) -f Dirs.mk distclean all_made

