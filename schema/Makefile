# Makefile for ccn/schema

SUBDIRS = validation

default all clean depend coverage check test shared testinstall install uninstall: _always
	for i in $(SUBDIRS); do         \
	  (cd "$$i" && pwd && $(MAKE) $@) || break;			\
	done

documentation: _always

_always:

.PHONY: _always
