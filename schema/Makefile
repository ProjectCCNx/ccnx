# Makefile for schema directory
SUBDIRS = validation

default all clean depend coverage check test shared install uninstall:
	for i in $(SUBDIRS); do         \
	  (cd "$$i" && pwd && $(MAKE) $@) || break;			\
	done
