# Copyright (C) 2010 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#
export

# If we don't have the right environment settings, skip the build.
# Do this as a warning.
ifeq ($(ANDROID_NDK),)
SKIP_BUILD := true
endif
ifeq ($(ANDROID_SDK),)
SKIP_BUILD := true
endif

#
# Make all the Java jars and put them in "obj" directory.
# pjproject-1.4.5 is configured for the host platform and built,
# but not installed anywhere.

SUBDIRS := external CCNx-Android-Services apps
SUBDIRS_clean := $(addsuffix .clean,$(SUBDIRS))
SUBDIRS_distclean := $(addsuffix .distclean,$(SUBDIRS))

.PHONY: subdirs $(SUBDIRS_clean) $(SUBDIRS) $(SUBDIRS_distclean) _always

ifeq ($(SKIP_BUILD),)
default all: $(SUBDIRS)

else
default all: 
	@echo 
	@echo '*************************************************'
	@echo '    Missing either ANDROID_SDK or ANDROID_NDK,'
	@echo '           NOT building Android targets.'
	@echo
	@echo '  ANDROID_SDK should point to a R7 or later SDK'
	@echo '     with Android 2.1 installed.'
	@echo '  ANDROID_NDK should point to an R4 or later NDK.'
	@echo '*************************************************'
	@echo 
endif

../javasrc/ccn.jar:
	$(error Please make CCN before the Android port)

CCNx-Android-Lib: 

CCNx-Android-Services: external ../javasrc/ccn.jar

apps: 

subdirs: $(SUBDIRS)

# Build externals without parallism
$(SUBDIRS):
	$(MAKE) -C $@ -j 1

install uninstall: 
	$(MAKE) -C CCNx-Android-Services $@
	$(MAKE) -C apps $@

clean: $(SUBDIRS_clean)

distclean: $(SUBDIRS_distclean)

$(SUBDIRS_clean):
	-$(MAKE) -C $(basename $@) clean

$(SUBDIRS_distclean):
	-$(MAKE) -C $(basename $@) distclean

documentation: _always
	doxygen

dist-docs: _always
	doxygen Doxyfile.dist

_always:

