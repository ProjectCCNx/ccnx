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

APK_NAME := CCNx-Android-Chat-debug.apk
BIN := bin
GEN := gen

############
# Nothing tweekable down here

.PHONY: all environment clean simpleclean distclean

.SUFFIXES: .jar .properties .xml

##########

GENERATED_SOURCE_FILES :=

# This is a lit of the targets in our libs directory
JARS := libs/ccn.jar libs/ccnChat.jar

TARGET  := $(BIN)/$(APK_NAME)

default all: $(TARGET) 

honeycomb: clean bcprov default

bcprov:
	./download.sh libs http://repo2.maven.org/maven2/org/bouncycastle/bcprov-jdk16/1.43 bcprov-jdk16-1.43.jar

SRC := $(wildcard src/org/ccnx/android/apps/chat/*.java) AndroidManifest.xml

$(TARGET): $(JARS) local.properties build.xml $(SRC)
	ant debug

local.properties default.properties project.properties:
	$(ANDROID_SDK)/tools/android update project --name CCNx-Android-Chat -p . -t android-7 --library ../../CCNx-Android-Lib/

libs/ccn.jar: ../../../javasrc/ccn.jar
	mkdir -p $(dir $@)
	rm -f $@

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
	adb uninstall org.ccnx.android.apps.chat

environment:
ifeq ($(ANDROID_SDK),)
	$(error Please set ANDROID_SDK path to point to an r16 or later SDK)
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
