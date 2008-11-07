MORE_LDLIBS=$(CCNLIBDIR)/getaddrinfo/getaddrinfo.o
PLATCFLAGS=-DNEED_GETADDRINFO_COMPAT -Wl,--enable-auto-import -I$(CCNLIBDIR)/getaddrinfo


