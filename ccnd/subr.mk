$(CSRC) $(HSRC) $(SCRIPTSRC):
	test -f $(SRCDIR)/$@ && ln -s $(SRCDIR)/$@

$(DUPDIR):
	test -d $(SRCDIR)/$(DUPDIR) && mkdir $(DUPDIR) && cp -p $(SRCDIR)/$(DUPDIR)/* $(DUPDIR)

$(OBJDIR)/Makefile: Makefile
	test -d $(OBJDIR) || mkdir $(OBJDIR)
	test -f $(OBJDIR)/Makefile && mv $(OBJDIR)/Makefile $(OBJDIR)/Makefile~ ||:
	cp -p Makefile $(OBJDIR)/Makefile

depend: Makefile $(CSRC)
	echo $(CSRC) | xargs gcc -MM $(CPREFLAGS) > depend
	tail -n `wc -l < depend` Makefile | diff - depend
