# Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
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

PROJECT = CCNx-Android-Lib
APK_NAME = $(PROJECT)-debug.apk
BIN = bin
GEN = gen

############
# Nothing tweekable down here

.PHONY: all environment prereq clean simpleclean distclean 

.SUFFIXES: .jar .properties

##########

JARS = libs/ccn.jar

TARGET = $(BIN)/$(APK_NAME)

default: project.properties default.properties local.properties libs/ccn.jar

all: $(TARGET) 

$(TARGET): $(JARS) environment project.properties default.properties local.properties build.xml
	ant debug 

# Guard against a bug in Android SDK r16	
# You need to have android.library=true for rest of build to work
local.properties project.properties default.properties:
	$(ANDROID_SDK)/tools/android update lib-project -p . -t android-7
	grep -q android.library=true project.properties || echo android.library=true >> project.properties

checkccnjar:
	@test -f ../../javasrc/ccn.jar || (echo Missing ccn.jar.  Please make CCNx javasrc before the Android port; \
	exit 1;)

libs/ccn.jar: checkccnjar
	rm -f $@
	mkdir -p libs
	ln -s ../../../javasrc/ccn.jar $@

install:

uninstall:

environment:
	@if test "$(ANDROID_SDK)" = "" ; then \
		echo "Please set ANDROID_SDK path to point to an r16 or later SDK" && exit 1; \
	fi

clean: simpleclean

simpleclean:
	rm -rf $(BIN) $(GEN) libs/ccn.jar

distclean: simpleclean

test:
	@echo "No automated tests for services"
