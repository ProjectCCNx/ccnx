default all clean depend test check shared install uninstall: _always
	(cd ../src && pwd && $(MAKE) $@)
	test -f ../src/ccnd/ccnd && mkdir -p agent && ln -s ../../src/ccnd/ccnd agent/ccnd ||:

_always:
