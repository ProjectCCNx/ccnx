REAL_CFLAGS = $(COPT) $(CWARNFLAGS) $(CPREFLAGS) $(PLATCFLAGS)

$(CSRC) $(HSRC) $(SCRIPTSRC) $(SRCLINKS):
	test -f $(SRCDIR)/$@ && ln -s $(SRCDIR)/$@

config_subdir: Makefile

Makefile:
	test -f $(SRCDIR)/../generic.mk && ln -s $(SRCDIR)/../generic.mk Makefile
	
$(DUPDIR):
	test -d $(SRCDIR)/$(DUPDIR) && mkdir $(DUPDIR) && cp -p $(SRCDIR)/$(DUPDIR)/* $(DUPDIR)

$(OBJDIR)/dir.mk: dir.mk
	test -d $(OBJDIR) || mkdir $(OBJDIR)
	test -f $(OBJDIR)/dir.mk && mv $(OBJDIR)/dir.mk $(OBJDIR)/dir.mk~ ||:
	cp -p dir.mk $(OBJDIR)/dir.mk

install_libs: $(LIBS)
	test -d $(INSTALL_LIB)
	for i in $(LIBS); do $(INSTALL) $$i $(INSTALL_LIB); done

install_programs: $(INSTALLED_PROGRAMS)
	test -d $(INSTALL_BIN)
	for i in $(INSTALLED_PROGRAMS); do $(INSTALL) $$i $(INSTALL_BIN); done

install: install_libs install_programs

uninstall_libs: $(LIBS)
	for i in $(LIBS); do $(RM) $(INSTALL_LIB)/$$i; done

uninstall_programs: $(PROGRAMS)
	for i in $(PROGRAMS); do $(RM) $(INSTALL_BIN)/$$i; done

uninstall: uninstall_libs uninstall_programs

coverage:
	X () { test -f $$1 || return 0; gcov $$*; }; X *.gc??

shared:

depend: dir.mk $(CSRC)
	for i in $(CSRC); do gcc -MM $(CPREFLAGS) $$i; done > depend
	tail -n `wc -l < depend` dir.mk | diff - depend

install_libs install_programs install uninstall_libs uninstall_programs uninstall coverage shared documentation depend config_subdir: _always
.PHONY: _always
_always:
