# Copyright (C) 2010 Palo Alto Research Center, Inc.
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

PROJECT := CCNx-Android-Lib
APK_NAME := $(PROJECT)-debug.apk
BIN := bin
GEN := gen

############
# Nothing tweekable down here

.PHONY: all environment prereq clean simpleclean distclean 

.SUFFIXES: .jar .properties

##########

JARS := libs/ccn.jar

TARGET  := $(BIN)/$(APK_NAME)

default: local.properties libs/ccn.jar

all: $(TARGET) 

$(TARGET): $(JARS) environment local.properties build.xml
	ant compile

local.properties project.properties default.properties:
	$(ANDROID_SDK)/tools/android update lib-project -p . -t android-7

libs/ccn.jar: ../../javasrc/ccn.jar
	rm -f $@
	mkdir -p libs
	ln -s ../$^ $@

install:

uninstall:

environment:
ifeq ($(ANDROID_SDK),)
	$(error Please set ANDROID_SDK path to point to an r7 or later SDK)
endif
	@echo "Executing in $(CURDIR)"

clean: simpleclean

simpleclean:
	rm -rf $(BIN) $(GEN) libs/ccn.jar

distclean: simpleclean

test:
	@echo "No automated tests for services"
