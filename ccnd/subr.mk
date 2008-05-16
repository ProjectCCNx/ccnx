$(CSRC) $(HSRC):
	test -f $(SRCDIR)/$@ && ln -s $(SRCDIR)/$@

$(OBJDIR)/Makefile: Makefile
	test -d $(OBJDIR) || mkdir $(OBJDIR)
	test -f $(OBJDIR)/Makefile && mv $(OBJDIR)/Makefile $(OBJDIR)/Makefile~ ||:
	cp -p Makefile $(OBJDIR)/Makefile

depend: Makefile $(CSRC)
	gcc -MM $(CSRC) $(CPREFLAGS) > depend
	tail -n `wc -l < depend` Makefile | diff - depend
