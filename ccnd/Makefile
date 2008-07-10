# $Id$
SUBDIRS = lib agent udplink python
OBJTREE = obj.`uname`
MAKEF =  -f ../conf.mk -f Makefile -f ../conf.mk -f ../subr.mk

default all clean depend coverage test:
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

MANIFEST:
	svn list -R | grep -v -e '/$$' -e 'agent/5586.ccnb.gz' > MANIFEST

FAVORITES: MANIFEST
	./makefavorites
	rm MANIFEST

tar:	ccnd.tar
ccnd.tar: MANIFEST
	tar cf ccnd.tar -T MANIFEST
	rm MANIFEST
