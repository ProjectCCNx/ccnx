# Hakefile for csrc directory

SUBDIRS = lib ccnd libexec cmd tests
OBJTREE = build
MAKEF =  -f ../conf.mk -f dir.mk -f ../subr.mk
CFLAGS = -g

default all clean depend coverage test check shared install uninstall config_subdir: conf.mk subr.mk generic.mk _always
	test -d include/ccn || (test -d ../include/ccn && mkdir -p include && ln -s ../../include/ccn include/ccn)
	for i in $(SUBDIRS); do         \
	  (cd "$$i" && pwd && $(MAKE) $(MAKEF) "COPT=$(CFLAGS)" CFLAGS='$$(REAL_CFLAGS)' SRCDIR=../$(SRCDIR)/$$i $@) || exit 1;	\
	done
	@test -d $(OBJTREE) && echo "+++ NOTE - $(OBJTREE) exists" ||:
	@rm -f _always

subr.mk generic.mk:
	test -f ./$(SRCDIR)/$@ && ln -s ./$(SRCDIR)/$@

conf.mk:
	./configure

test check: default

objtree: conf.mk _always
	mkdir -p $(OBJTREE)
	echo SRCDIR = ..  > $(OBJTREE)/Makefile
	cat Makefile     >> $(OBJTREE)/Makefile
	for i in $(SUBDIRS); do						\
	  mkdir -p $(OBJTREE) &&					\
	    (cd $$i && pwd &&						\
		$(MAKE) $(MAKEF) OBJDIR=../$(OBJTREE)/$$i SRCDIR=. 	\
			../$(OBJTREE)/$$i/dir.mk	 		\
	     || exit 1);						\
	done
	cd $(OBJTREE) && ../configure

cleanall: _always
	find . -type l -print -exec rm '{}' ';'

_manifester:
	rm -f _manifester
	test -d .svn && { type svn >/dev/null 2>/dev/null; } && ( echo svn list -R > _manifester; ) || :
	test -f _manifester || ( git branch >/dev/null 2>/dev/null && echo git ls-files > _manifester; ) || :
	test -f _manifester || ( echo false > _manifester )

MANIFEST: _manifester
	echo MANIFEST > MANIFEST.new
	sh _manifester | grep -v -e '/$$' >> MANIFEST.new
	mv MANIFEST.new MANIFEST
	rm _manifester

FAVORITES: MANIFEST
	./makefavorites
	mv MANIFEST 00MANIFEST

tar:	ccn-c.tar
ccn-c.tar: MANIFEST
	tar cf ccnd.tar -T MANIFEST
	mv MANIFEST 00MANIFEST

documentation: _always
	doxygen

_always:

.PHONY: _always
