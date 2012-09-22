# Copyright (C) 2010-2012 Palo Alto Research Center, Inc.  All Rights Reserved.
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

SUBDIRS = CCNx-Android-Chat CCNx-Android-StartupExamples

.PHONY: subdirs mkdirs install $(SUBDIRS)

default all: $(SUBDIRS)

# Build externals without parallism
$(SUBDIRS): 
	for i in $(SUBDIRS); do \
		(cd "$$i" && pwd && $(MAKE) -j 1) || exit 1; \
	done

install uninstall:
	for f in $(SUBDIRS); do \
		(cd "$$f" && pwd && $(MAKE) $@) || exit 1; \
	done

with-bcp:
	for f in $(SUBDIRS); do \
		(cd "$$f" && pwd && $(MAKE) with-bcp $@) || exit 1; \
	done

clean distclean:
	for i in $(SUBDIRS); do \
		(cd "$$i" && pwd && $(MAKE) $@) || exit 1; \
	done
	@rm -f _always

