# $Id$
SUBDIRS = lib agent udplink
OBJTREE = obj.`uname`
MAKEF =  -f ../conf.mk -f Makefile -f ../subr.mk

default all clean depend:
	test -f conf.mk || touch conf.mk
	test -d include/ccn || (test -d ../include/ccn && mkdir include && ln -s ../../include/ccn include/ccn)
	test -f subr.mk || (test -f ../subr.mk && ln -s ../subr.mk)
	for i in $(SUBDIRS); do								\
	  (cd "$$i" && pwd && $(MAKE) $(MAKEF) SRCDIR=../$(SRCDIR)/$$i $@ || exit 1);	\
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
	find . -type d -name obj.\* | while read i; do    \
          rm -f $$i/*.o $$i/*.ko* $$i/depend $$i/.depend; \
        done
	find . -type l -exec rm '{}' ';'

MANIFEST:
	svn list -R | grep -v '/$$' > MANIFEST

tar:	ccnd.tar
ccnd.tar: MANIFEST
	tar cf ccnd.tar -T MANIFEST
	rm MANIFEST
