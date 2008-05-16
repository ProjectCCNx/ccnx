# $Id$
SUBDIRS = lib agent udplink
OBJTREE = obj.`uname`
default all clean depend:
	for i in $(SUBDIRS); do \
	  (cd "$$i" && pwd && $(MAKE) SRCDIR=../$(SRCDIR)/$$i $@ || exit 1); \
	done

objtree:
	mkdir -p $(OBJTREE)
	test -d $(OBJTREE)/include || (cd $(OBJTREE); ln -s ../include);
	echo SRCDIR = ..  > $(OBJTREE)/Makefile
	cat Makefile     >> $(OBJTREE)/Makefile
	for i in $(SUBDIRS); do						 \
	 mkdir -p $(OBJTREE)/$$i &&					 \
	 (cd "$$i" && pwd &&						 \
	  $(MAKE) OBJDIR=../$(OBJTREE)/$$i ../$(OBJTREE)/$$i/Makefile || \
	  exit 1);							 \
	done

cleanall:
	find . -type d -name obj.\* | while read i; do \
          rm -f $$i/*.o $$i/*.ko* $$i/depend $$i/.depend; \
        done
	find . -type l -exec rm '{}' ';'

MANIFEST:
	svn list -R | grep -v '/$$' > MANIFEST

tar:	ccnd.tar
ccnd.tar: MANIFEST
	tar cf ccnd.tar -T MANIFEST
	rm MANIFEST
