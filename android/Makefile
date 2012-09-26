# Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#


#
# Make all the Java jars and put them in "obj" directory.
# pjproject-1.4.5 is configured for the host platform and built,
# but not installed anywhere.

SUBDIRS = external CCNx-Android-Lib CCNx-Android-Services apps

.PHONY: subdirs $(SUBDIRS_clean) $(SUBDIRS) $(SUBDIRS_distclean) _always

default all: verifykits $(SUBDIRS)


# 
# Do we really want to instead just build it if it doesn't exist?
#
checkccnjar:
	@test -f ../javasrc/ccn.jar || (echo Missing ccn.jar.  Please make CCNx javasrc before the Android port; \
	exit 1;)

CCNx-Android-Lib: checkccnjar

# Since we've moved to a modern NDK, we should consider turning external projects
# into NDK library module projects, and these can then simply be added as modules 
# during the JNI build phase.  We'll have to still deal with external downloads 
# because of export control handling.
CCNx-Android-Services: checkccnjar external

apps: 

subdirs: $(SUBDIRS)

# Build externals without parallism
$(SUBDIRS): 
	for i in $(SUBDIRS); do \
		(cd "$$i" && pwd && $(MAKE) -j 1) || exit 1; \
	done

install uninstall:
	(cd CCNx-Android-Services && $(MAKE) $@)
	(cd apps && $(MAKE) $@)

with-bcp:
	(cd external && $(MAKE) -j 1)
	(cd CCNx-Android-Lib && $(MAKE))
	(cd CCNx-Android-Services && $(MAKE) $@)
	(cd apps && $(MAKE) $@)

clean distclean:
	for i in $(SUBDIRS); do \
		(cd "$$i" && pwd && $(MAKE) $@) || exit 1; \
	done
	@rm -f _always

documentation: _always
	doxygen

dist-docs: _always
	doxygen Doxyfile.dist

# If we don't have the right environment settings, skip the build.
# Do this as a warning (Why a warning, shouldn't this be a build failure?)
# We should probably consider verifying the versions of the kits but this 
# isn't an inherently clear item to verify since none of the tools provide
# clues as to version, so it may make sense to look in the configuration 
# files that come in the kits for these clues
verifykits: _always
	@test -d "$(ANDROID_NDK)" && test -d "$(ANDROID_SDK)" || (echo; \
	echo '*******************************************************'; \
	echo '    Missing either ANDROID_SDK or ANDROID_NDK,'; \
	echo '           NOT building Android targets.'; \
	echo; \
	echo '  ANDROID_SDK should point to a R16 or later SDK'; \
	echo '     with Android 2.1 (android-7) installed.'; \
	echo '  ANDROID_NDK should point to an R7 or later NDK.'; \
	echo '  Please set these environment variables and verify'; \
	echo '  the directory location existence before proceeding.'; \
	echo '*******************************************************'; \
	echo; \
	exit 1;)


_always:

