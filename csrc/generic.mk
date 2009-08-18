# This is a generic Makefile that is put into each subdirectory
# so that the developer make easily build just that subdirectory,
# possibly with different CFLAGS, etc.
# This is not used at all for a make from the top level.

default all clean depend test check shared install uninstall: _always
	SELF=`basename \`pwd\``; (cd .. && $(MAKE) SUBDIRS=$$SELF $@)
_always:
