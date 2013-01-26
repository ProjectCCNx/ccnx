# Copyright (C) 2009-2013 Palo Alto Research Center, Inc.
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
############
# User-settable things

APK_NAME = CCNx-Android-Services-debug.apk
BIN = bin
GEN = gen

############
# Nothing tweekable down here

.PHONY: all ccnd environment prereq clean simpleclean distclean checkccnxdeps
.SUFFIXES: .jar .properties .xml

##########
# This is a lit of the targets in our libs directory
JARS = libs/ccn.jar

TARGET = $(BIN)/$(APK_NAME)

default all: $(TARGET) 

with-bcp: clean bcprov default

bcprov:
	./download.sh libs http://repo2.maven.org/maven2/org/bouncycastle/bcprov-jdk16/1.43 bcprov-jdk16-1.43.jar

$(TARGET): $(JARS) ccnd local.properties project.properties build.xml
	ant debug

local.properties project.properties:
	@mkdir -p libs
	$(ANDROID_SDK)/tools/android update project --name CCNx-Android-Services -p . -t android-15 --library ../CCNx-Android-Lib/

# Need to revisit this for newer NDK.
# Compensate for the lack of proper dependencies by removing any ccnx object
# files that are older than the newest ccnx header file.  The android build
# seems to deal OK with changes to the .c files themselves.
#
# The NDK does not know about bin_ant, so it is always under bin
CCNXOBJDIRS = bin/ndk/local/armeabi/objs/ccnd

checkccnxdeps:
	touch -r `find ../../csrc -name \*.h | xargs ls -t | head -n 1` ccnxheader.date
	-if [ -d bin/ndk/local/armeabi/objs ]; then \
		find $(CCNXOBJDIRS) \! -newer ccnxheader.date -name \*.o -print -exec rm {} \; ; fi
	rm ccnxheader.date

ccnd: environment checkccnxdeps
	@echo "Building CCND"
	mkdir -p libs
	if test -n "$$APP_ABI"; then \
			$(ANDROID_NDK)/ndk-build APP_ABI="$$APP_ABI" ; \
	else \
				$(ANDROID_NDK)/ndk-build ; \
	fi

checkccnjar:
	@test -f ../../javasrc/ccn.jar || (echo Missing ccn.jar.  Please make CCNx javasrc before the Android port; \
	exit 1;)

libs/ccn.jar: checkccnjar
	mkdir -p $(dir $@)
	rm -f $@

######################################
# Maintenance targets
#
install:
	adb install -r $(BIN)/$(APK_NAME)

uninstall:
	adb uninstall org.ccnx.android.services

environment:
	@if test "$(ANDROID_SDK)" = "" ; then \
		echo "Please set ANDROID_SDK path to point to an r16 or later" && exit 1; \
	fi
	@if test "$(ANDROID_NDK)" = "" ; then \
		echo "Please set ANDROID_NDK path to point to an r7 or later" && exit 1; \
	fi

clean: simpleclean

# Does not remove NDK object files
simpleclean:
	rm -rf $(BIN) $(GEN)
	rm -rf libs/*
	rm -f project.properties
	rm -f local.properties

distclean: environment simpleclean
	$(ANDROID_NDK)/ndk-build clean
	rm -f $(GENERATED_SOURCE_FILES)

test:
	@echo "No automated tests for services"
