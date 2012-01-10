In order to build the CCN plugin for Wireshark you will first need to install
a Wireshark source distribution.

Then -- in your wireshark source directory you should make a directory
plugins/ccn and copy the contents of the CCN distribution's
ccn/apps/wireshark/ccn there.

You'll need to follow the instructions in wireshark file doc/README.plugins
regarding existing Wireshark files that need to be edited to add a new plugin.

For wireshark 1.4.2 (we have tested up through 1.4.6), you need to change
the following files (README.plugins sec 3)

	configure.in
	CMakeLists.txt
	epan/Makefile.am
	Makefile.am
	packaging/nsis/Makefile.nmake
	packaging/nsis/wireshark.nsi
	plugins/Makefile.am
	plugins/Makefile.nmake

The patch file wireshark-1.4.2.patch (works on 1.4.6 too) can be applied to
make the necessary changes:

    cd wireshark-1.4.2
    patch -p1 < .../ccn/apps/wireshark/wireshark-1.4.2.patch

When you've made the changes per README.plugins or by applying the patch file,
you must run wireshark's autogen.sh and configure to setup your wireshark build
area, and then recompile wireshark.

