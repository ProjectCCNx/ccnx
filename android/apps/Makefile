# Copyright (C) 2010, 2011 Palo Alto Research Center, Inc.  All Rights Reserved.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
# for more details. You should have received a copy of the GNU General Public
# License along with this program; if not, write to the
# Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
# Boston, MA 02110-1301, USA.
#
export

#

SUBDIRS := CCNx-Android-Chat CCNx-Android-StartupExamples
SUBDIRS_clean := $(addsuffix .clean,$(SUBDIRS))
SUBDIRS_distclean := $(addsuffix .distclean,$(SUBDIRS))

.PHONY: subdirs mkdirs install $(SUBDIRS) $(SUBDIRS_clean)

default all: $(SUBDIRS)

# Build externals without parallism
$(SUBDIRS): 
	$(MAKE) -C $@ -j 1

install uninstall:
	for f in $(SUBDIRS); do \
	   	$(MAKE) -C $$f $@; \
	done

clean: $(SUBDIRS_clean)

distclean: $(SUBDIRS_distclean)

$(SUBDIRS_clean):
	-$(MAKE) -C $(basename $@) clean

$(SUBDIRS_distclean):
	-$(MAKE) -C $(basename $@) distclean

