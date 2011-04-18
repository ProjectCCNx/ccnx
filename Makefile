# Top level Makefile for CCNx
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009-2011 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

# Subdirectories we build in
TOPSUBDIRS = doc/manpages doc/technical csrc schema javasrc apps `cat local.subdirs 2>/dev/null || :`
# Packing list for packaging
PACKLIST = Makefile README LICENSE NEWS NOTICES configure doc/index.txt $(TOPSUBDIRS) android experiments
BLDMSG = printf '=== %s ' 'Building $@ in' && pwd

default all: _always
	for i in $(TOPSUBDIRS); do         \
	  (cd "$$i" && pwd && $(MAKE) $@) || exit 1;	\
	done
	(cd csrc/lib && { test -f "$$HOME/.ccnx/.ccnx_keystore" || $(MAKE) test; }; )
	mkdir -p ./lib ./bin
	test -d ./include || ln -s ./csrc/include
	(cd csrc && $(MAKE) install INSTALL_BASE=`pwd`/..)
	(cd javasrc && $(MAKE) install INSTALL_BASE=`pwd`/..)
	(cd apps && $(MAKE) install INSTALL_BASE=`pwd`/..)

clean depend test check shared: _always
	for i in $(TOPSUBDIRS); do         \
	  (cd "$$i" && pwd && $(MAKE) $@) || exit 1;	\
	done
	@rm -f _always

testinstall install uninstall: _always
	IB=`[ -z '$(INSTALL_BASE)' ] && grep ^INSTALL_BASE csrc/conf.mk 2>/dev/null | sed -e 's/ //g' || echo INSTALL_BASE=$(INSTALL_BASE)`; \
	for i in $(TOPSUBDIRS); do         \
	  (cd "$$i" && pwd && $(MAKE) $$IB $@) || exit 1;	\
	done
	@rm -f _always

documentation dist-docs: _always
	for i in $(TOPSUBDIRS) android; do         \
	  (cd "$$i" && pwd && $(MAKE) $@) || exit 1;	\
	done
	@rm -f _always

clean-documentation: _always
	rm -rf doc/ccode
	rm -rf doc/javacode
	rm -rf doc/android
	(cd doc/manpages && pwd && $(MAKE) clean-documentation)
	(cd doc/technical && pwd && $(MAKE) clean-documentation)

clean: clean-testinstall
clean-testinstall: _always
	rm -rf bin include lib

# The rest of this is for packaging purposes.
_manifester:
	rm -f _manifester
	test -d .svn && { type svn >/dev/null 2>/dev/null; } && ( echo svn list -R $(PACKLIST) > _manifester; ) || :
	test -f _manifester || ( git branch >/dev/null 2>/dev/null && echo git ls-files $(PACKLIST) > _manifester; ) || :
	test -f _manifester || ( test -f MANIFEST && echo cat MANIFEST > _manifester ) || :
	test -f _manifester || ( test -f 00MANIFEST && echo cat 00MANIFEST > _manifester ) || :
	test -f _manifester || ( echo false > _manifester )

MANIFEST: _manifester
	echo MANIFEST > MANIFEST.new
	@cat _manifester
	sh _manifester | grep -v -e '/$$' -e '^MANIFEST$$' -e '[.]gitignore' >> MANIFEST.new
	sort MANIFEST.new | uniq > MANIFEST
	rm MANIFEST.new
	rm _manifester

tar:	ccnx.tar
ccnx.tar: MANIFEST
	tar cf ccnx.tar -T MANIFEST
	mv MANIFEST 00MANIFEST

distfile: tar
	echo $(VERSION) > version
	# make sure VERSION= has been provided
	grep '^[0-9]....' version
	# check the Doxyfiles for good version information
	# fail on the next step if the directory already exists
	mkdir ccnx-$(VERSION)
	( cd ccnx-$(VERSION) && tar xf ../ccnx.tar && $(MAKE) fixupversions VERSION=$(VERSION) && $(MAKE) MD5 SHA1 )
	# Build the documentation
	( cd ccnx-$(VERSION) && $(MAKE) dist-docs 2>&1) > ccnx-$(VERSION)-documentation.log
	tar cf ccnx-$(VERSION).tar ccnx-$(VERSION)
	gzip -9 ccnx-$(VERSION).tar
	ls -l ccnx-$(VERSION).tar.gz

fixupversions: _always
	Fix1 () { sed -e '/^PROJECT_NUMBER/s/=.*$$/= $(VERSION)/' $$1 > DTemp && mv DTemp $$1; } && Fix1 csrc/Doxyfile && Fix1 csrc/Doxyfile.dist && Fix1 javasrc/Doxyfile && Fix1 javasrc/Doxyfile.dist && Fix1 doc/manpages/Makefile && Fix1 android/Doxyfile && Fix1 android/Doxyfile.dist

IGNORELINKS = -e android/CCNx-Android-Services/jni/csrc -e android/CCNx-Android-Services/jni/openssl/openssl-armv5
MD5: _always
	grep -v $(IGNORELINKS) MANIFEST | xargs openssl dgst > MD5

SHA1: _always
	grep -v $(IGNORELINKS) MANIFEST | xargs openssl dgst -sha1 > SHA1

_always:
.PHONY: _always
