# $Id$
SUBDIRS = lib agent udplink flowtest python
OBJTREE = obj.`uname`
MAKEF =  -f ../conf.mk -f Makefile -f ../conf.mk -f ../subr.mk

default all clean depend coverage test shared:
	test -f conf.mk || touch conf.mk
	test -d include/ccn || (test -d ../include/ccn && mkdir -p include && ln -s ../../include/ccn include/ccn)
	test -f subr.mk || (test -f ../subr.mk && ln -s ../subr.mk)
	for i in $(SUBDIRS); do         \
	  (cd "$$i" && pwd && $(MAKE) $(MAKEF) SRCDIR=../$(SRCDIR)/$$i $@) || break;	\
	done
	@test -d $(OBJTREE) && echo "+++ NOTE - $(OBJTREE) exists" ||:

objtree:
	mkdir -p $(OBJTREE)
	echo SRCDIR = ..  > $(OBJTREE)/Makefile
	cat Makefile     >> $(OBJTREE)/Makefile
	for i in $(SUBDIRS); do						\
	  mkdir -p $(OBJTREE) &&					\
	    (cd $$i && pwd &&						\
		$(MAKE) $(MAKEF) OBJDIR=../$(OBJTREE)/$$i SRCDIR=. 	\
			../$(OBJTREE)/$$i/Makefile	 		\
	     || exit 1);						\
	done
	cd $(OBJTREE) && ../configure

cleanall:
	find . -type l -print -exec rm '{}' ';'

_manifester:
	rm -f _manifester
	test -d .svn && { type svn >/dev/null 2>/dev/null; } && ( echo svn list -R > _manifester; ) || :
	test -f _manifester || ( git branch >/dev/null 2>/dev/null && echo git ls-files > _manifester; ) || :
	test -f _manifester || ( echo false > _manifester )
	
MANIFEST: _manifester
	echo MANIFEST > MANIFEST.new
	sh _manifester | grep -v -e '/$$' -e 'agent/5586.ccnb.gz' >> MANIFEST.new
	mv MANIFEST.new MANIFEST
	rm _manifester

FAVORITES: MANIFEST
	./makefavorites
	mv MANIFEST 00MANIFEST

tar:	ccnd.tar
ccnd.tar: MANIFEST
	tar cf ccnd.tar -T MANIFEST
	mv MANIFEST 00MANIFEST
