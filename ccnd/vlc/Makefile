libdir = $(shell pkg-config --variable=libdir vlc-plugin )
vlclibdir = $(libdir)/vlc

all: libccn_plugin.so

libccn_plugin.so: libccn_plugin.o
	gcc -shared -std=gnu99 $< `pkg-config  --libs vlc-plugin`  -Wl,-soname -Wl,$@ -o $@

libccn_plugin.o: ccn.c
	gcc -c -std=gnu99  $< -I../include/ `pkg-config  --cflags vlc-plugin` -D__PLUGIN__  -DMODULE_STRING=\"ccn\" -o $@  

clean:
	rm -f libccn_plugin.o libccn_plugin.so ccn.o

install: all
	mkdir -p $(DESTDIR)$(vlclibdir)/access/
	install -m 0755 libccn_plugin.so $(DESTDIR)$(vlclibdir)/access/

install-strip: all
	mkdir -p $(DESTDIR)$(vlclibdir)/access/
	install -s -m 0755 libccn_plugin.so $(DESTDIR)$(vlclibdir)/access/

uninstall:
	rm -f -- $(DESTDIR)$(vlclibdir)/access/libccn_plugin.so

.PHONY: all clean install uninstall
