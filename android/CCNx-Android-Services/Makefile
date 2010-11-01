# Copyright (C) 2009,2010 Palo Alto Research Center, Inc.
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

APK_NAME := CCNx-Android-Services-debug.apk
BIN := bin_ant
GEN := gen_ant

############
# Nothing tweekable down here

NDK_PATH := $(ANDROID_NDK)/ndk-build

.PHONY: all ccnd environment prereq clean simpleclean distclean checkccnxdeps

.SUFFIXES: .jar .properties .xml

##########

# This is a lit of the targets in our libs directory
JARS := libs/ccn.jar

TARGET  := $(BIN)/$(APK_NAME)

default all: $(TARGET) 

$(TARGET): $(JARS) ccnd local.properties default.properties build.xml
	ant debug

# This will also generate a build.xml, which we remove because
# it's a bad version.
# Due to a bug in android update project, cannot have a --library on the command line
# if the default.properties (or something else?) does not exist yet. 
local.properties default.properties:
	@mkdir -p libs
	$(ANDROID_SDK)/tools/android update project -p . -t android-7
	$(ANDROID_SDK)/tools/android update project -p . -t android-7 --library ../CCNx-Android-Lib/
	rm build.xml

# Need to build local.properties first, because that creates a bogus build.xml
build.xml: build.xml.in local.properties
	cp build.xml.in build.xml

# Compensate for the lack of proper dependencies by removing any ccnx object
# files that are older than the newest ccnx header file.  The android build
# seems to deal OK with changes to the .c files themselves.
#
# The NDK does not know about bin_ant, so it is always under bin
CCNXOBJDIRS = bin/ndk/local/armeabi/objs/ccnd

checkccnxdeps:
	touch -r `find ../../../ccnx/csrc -name \*.h | xargs ls -t | head -n 1` ccnxheader.date
	-if [ -d bin/ndk/local/armeabi/objs ]; then \
		find $(CCNXOBJDIRS) \! -newer ccnxheader.date -name \*.o -print -exec rm {} \; ; fi
	rm ccnxheader.date

ccnd: environment checkccnxdeps
	@echo "Building CCND"
	mkdir -p libs/armeabi
	$(NDK_PATH)

libs/ccn.jar: ../../javasrc/ccn.jar
	mkdir -p $(dir $@)
	rm -f $@
	ln -s ../$^ $@

######################################
# Maintenance targets
#
install:
	adb install -r $(BIN)/$(APK_NAME)

uninstall:
	adb uninstall org.ccnx.android.services

environment:
ifeq ($(ANDROID_NDK),)
	$(error Please set ANDROID_NDK path)
endif
ifeq ($(ANDROID_SDK),)
	$(error Please set ANDROID_SDK path to point to an r7 or later SDK)
endif
	@echo "Executing in $(CURDIR)"

clean: simpleclean

# Does not remove NDK object files
simpleclean:
	rm -rf $(BIN) $(GEN)
	rm -rf libs/*
	rm -f default.properties
	rm -f local.properties

distclean: environment simpleclean
	$(ANDROID_NDK)/ndk-build clean
	rm -f $(GENERATED_SOURCE_FILES)

test:
	@echo "No automated tests for services"
