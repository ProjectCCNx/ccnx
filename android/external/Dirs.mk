# Copyright (C) 2010-2013 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.

#
# This makefile creates the directories in $(SUBDIRS)

.SUFFIXES: .jar

#################################
# User-settable things

# This is the source file to download
OPENSSL_HOST = http://www.openssl.org/source
OPENSSL_SRC = openssl-1.0.0d.tar.gz

# This is the output directory for the armv5 version of openssl
# This must match the link in CCNx-Android-Serices/jni/openssl
OPENSSL_TARGET = openssl-armv5


#################################
# Nothing user-settable down here
#
SUBDIRS = $(OPENSSL_TARGET)


JARDIR = obj

all: $(SUBDIRS) $(SUBDIRS_static)

###########################
## OPENSSL

downloads/$(OPENSSL_SRC):
	./download.sh downloads $(OPENSSL_HOST) $(OPENSSL_SRC)

# Only setup openssl if we don't have a make_timestamp
$(OPENSSL_TARGET): $(OPENSSL_TARGET)/make_timestamp

# These are the Android make files used by the build
SSLMKS = openssl_android_mks/Android.mk openssl_android_mks/crypto/Android.mk openssl_android_mks/ssl/Android.mk

$(OPENSSL_TARGET)/make_timestamp: downloads/$(OPENSSL_SRC) $(SSLMKS)
	mkdir -p $(OPENSSL_TARGET)
	tar -xzf $< -C $(OPENSSL_TARGET) --strip-components 1
	cp -r openssl_android_mks/* $(OPENSSL_TARGET)
	(	cd $(OPENSSL_TARGET) && \
		patch -p 1 < dlfcn_patch && \
	   	./Configure linux-generic32 no-idea no-bf no-cast no-cms no-camelia no-gmp no-mdc2 no-seed no-md2 no-rc5 no-ocsp no-engine -DL_ENDIAN)
	touch openssl_android_mks/make_timestamp

###########################

distclean:
	rm -rf $(SUBDIRS)
	rm -rf downloads
