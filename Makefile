# Top level Makefile for ccn

SUBDIRS = src schema Java_CCN apps/containerApp Documentation/technical
PACKLIST = Makefile build.xml README $(SUBDIRS)

default all clean depend test check shared documentation testinstall install uninstall: _always
	for i in $(SUBDIRS); do         \
	  (cd "$$i" && pwd && $(MAKE) $@) || exit 1;	\
	done
	@rm -f _always

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
	mv MANIFEST.new MANIFEST
	rm _manifester

tar:	ccn.tar
ccn.tar: MANIFEST
	tar cf ccn.tar -T MANIFEST
	mv MANIFEST 00MANIFEST

distfile: tar
	echo $(VERSION) > version
	# make sure VERSION= has been provided
	grep '^[0-9].....' version
	# check the Doxyfiles for good version information
	# fail on the next step if the directory already exists
	mkdir ccn-$(VERSION)
	( cd ccn-$(VERSION) && tar xf ../ccn.tar && $(MAKE) fixupversions && $(MAKE) MD5 SHA1 )
	tar cf ccn-$(VERSION).tar ccn-$(VERSION)
	gzip -9 ccn-$(VERSION).tar
	ls -l ccn-$(VERSION).tar.gz
	
fixupversions: _always
	Fix1 () { sed -e '/^PROJECT_NUMBER/s/=.*$$/= $(VERSION)/' $$1 > DTemp && mv DTemp $$1; } && Fix1 src/Doxyfile && Fix1 Java_CCN/Doxyfile

MD5: _always
	openssl dgst `cat MANIFEST` > MD5

SHA1: _always
	openssl dgst -sha1 `cat MANIFEST` > SHA1

_always:
.PHONY: _always
