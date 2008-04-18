# $Id$
SUBDIRS = lib agent udplink
default all clean depend:
	for i in $(SUBDIRS); do (cd "$$i" && pwd && $(MAKE) $@ || exit 1); done

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
