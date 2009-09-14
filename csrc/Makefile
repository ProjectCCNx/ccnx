# Makefile for csrc directory

SUBDIRS = lib ccnd libexec cmd util tests
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

documentation: _always
	doxygen

_always:

.PHONY: _always
