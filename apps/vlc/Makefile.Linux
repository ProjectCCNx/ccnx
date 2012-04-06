# vlc/Makefile.Linux
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009-2012 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#
libdir := $(shell pkg-config --variable=libdir vlc-plugin )
# in a future VLC release (1.2.0?) we expect to see a pkg-config variable for the plugin directory.
vlcplugindir := $(shell pkg-config --atleast-version=1.1.0 vlc-plugin && echo $(libdir)/vlc/plugins || echo $(libdir)/vlc/modules)
vlcaccessdir := $(vlcplugindir)/access
VLCPLUGINVERDEF := $(shell pkg-config --modversion vlc-plugin | awk -F. '{printf "-DVLCPLUGINVER=%d%02d%02d",$$1,$$2,$$3}')

all: libaccess_ccn_plugin.so

libaccess_ccn_plugin.so: libaccess_ccn_plugin.o
	gcc -g -shared -std=gnu99 $< `pkg-config  --libs vlc-plugin`  -Wl,-soname -Wl,$@ -o $@ -L../../lib -L/usr/local/lib -lccn -lcrypto

libaccess_ccn_plugin.o: ccn.c
	gcc -c -fPIC -g -O3 -std=gnu99  $< -I../../include/ `pkg-config  --cflags vlc-plugin` -D__PLUGIN__  -DMODULE_STRING=\"ccn\" $(VLCPLUGINVERDEF) -o $@  

clean:
	rm -f libaccess_ccn_plugin.o libaccess_ccn_plugin.so

install: all
	mkdir -p $(DESTDIR)$(vlcaccessdir)
	install -m 0755 libaccess_ccn_plugin.so $(DESTDIR)$(vlcaccessdir)

install-strip: all
	mkdir -p $(DESTDIR)$(vlcaccessdir)
	install -s -m 0755 libaccess_ccn_plugin.so $(DESTDIR)$(vlcaccessdir)

uninstall:
	rm -f -- $(DESTDIR)$(vlcaccessdir)/libaccess_ccn_plugin.so

.PHONY: all clean install uninstall
