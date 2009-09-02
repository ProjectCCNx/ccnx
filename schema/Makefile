# Makefile for ccn/schema

SUBDIRS = validation

default all clean depend coverage check test shared testinstall install uninstall: _always
	for i in $(SUBDIRS); do         \
	  (cd "$$i" && pwd && $(MAKE) $@) || break;			\
	done

documentation: _always

dictionary: tagname.csvdict
	cp tagname.csvdict ../javasrc/src/org/ccnx/ccn/impl/encoding/

_always: dictionary

.PHONY: _always 
