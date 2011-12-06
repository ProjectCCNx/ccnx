# Copyright (C) 2009,2010,2011 Palo Alto Research Center, Inc.
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

APK_NAME := CCNx-Android-StartupExamples-debug.apk
BIN := bin_ant
GEN := gen_ant

############
# Nothing tweekable down here

.PHONY: all environment clean simpleclean distclean 

.SUFFIXES: .jar .properties .xml

##########

GENERATED_SOURCE_FILES :=

# This is a lit of the targets in our libs directory
JARS := libs/ccn.jar

TARGET  := $(BIN)/$(APK_NAME)

default all: $(TARGET) 

SRC := $(wildcard src/org/ccnx/android/examples/startup/*.java) AndroidManifest.xml

$(TARGET): $(JARS) local.properties build.xml $(SRC)
	ant debug

# This will also generate a build.xml, which we remove because
# it's a bad version.
# Due to a bug in android update project, cannot have a --library on the command line
# if the default.properties (or something else?) does not exist yet. 
local.properties:
	$(ANDROID_SDK)/tools/android update project -p . -t android-7
	$(ANDROID_SDK)/tools/android update project -p . -t android-7 --library ../../CCNx-Android-Lib/
	rm build.xml

# Need to build local.properties first, because that creates a bogus build.xml
build.xml: build.xml.in local.properties
	cp build.xml.in build.xml

libs/ccn.jar: ../../../javasrc/ccn.jar
	mkdir -p $(dir $@)
	rm -f $@
	ln -s ../$^ $@

libs/ccnChat.jar: ../../../apps/ccnChat/ccnChat.jar
	mkdir -p $(dir $@)
	rm -f $@
	ln -s ../$^ $@

######################################
# Maintenance targets
#
install:
	adb install -r $(BIN)/$(APK_NAME)

uninstall:
	adb uninstall org.ccnx.android.examples.startup

environment:
ifeq ($(ANDROID_SDK),)
	$(error Please set ANDROID_SDK path to point to an r7 or later SDK)
endif
	@echo "Executing in $(CURDIR)"

clean: simpleclean

# Does not remove NDK object files
simpleclean:
	rm -rf $(BIN) $(GEN)
	rm -rf libs/*
	rm -f local.properties

distclean: environment simpleclean
	$(ANDROID_NDK)/ndk-build clean
	rm -f $(GENERATED_SOURCE_FILES)

test:
	@echo "No automated tests for services"
