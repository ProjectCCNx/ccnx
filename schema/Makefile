# Makefile for ccn/schema

SUBDIRS = validation

default all clean depend coverage check test shared install uninstall: _always
	for i in $(SUBDIRS); do         \
	  (cd "$$i" && pwd && $(MAKE) $@) || break;			\
	done

_always:

.PHONY: _always
