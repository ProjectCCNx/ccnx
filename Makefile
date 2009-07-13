# Top level Makefile for ccn

SUBDIRS = src Java_CCN apps/containerApp Documentation/technical

default all clean depend test check shared install uninstall: _always
	for i in $(SUBDIRS); do         \
	  (cd "$$i" && pwd && $(MAKE) $@) || exit 1;	\
	done
	@rm -f _always

_manifester:
	rm -f _manifester
	test -d .svn && { type svn >/dev/null 2>/dev/null; } && ( echo svn list -R $(SUBDIRS) > _manifester; ) || :
	test -f _manifester || ( git branch >/dev/null 2>/dev/null && echo git ls-files $(SUBDIRS) > _manifester; ) || :
	test -f _manifester || ( test -f MANIFEST && cat MANIFEST > _manifester ) || :
	test -f _manifester || ( test -f 00MANIFEST && cat 00MANIFEST > _manifester ) || :
	test -f _manifester || ( echo false > _manifester )

MANIFEST: _manifester
	echo MANIFEST > MANIFEST.new
	sh _manifester | grep -v -e '/$$' -e '^MANIFEST$$' >> MANIFEST.new
	mv MANIFEST.new MANIFEST
	rm _manifester

tar:	ccn.tar
ccn.tar: MANIFEST
	tar cf ccn.tar -T MANIFEST
	mv MANIFEST 00MANIFEST

_always:
.PHONY: _always