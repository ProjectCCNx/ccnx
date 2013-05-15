In order to build the CCN plugin for Wireshark you will first need to install
a Wireshark source distribution.  For example, for Ubuntu Linux:

	sudo apt-get source wireshark

Then -- in your wireshark source directory you should make a directory
plugins/ccn and copy the contents of the CCN distribution's
ccn/apps/wireshark/ccn there.

Follow the instructions in wireshark file doc/README.plugins
regarding existing Wireshark files that need to be edited to add a new plugin.

The following files (README.plugins sec 3) need to be updated

	configure.in
	CMakeLists.txt
	epan/Makefile.am
	Makefile.am
	packaging/nsis/Makefile.nmake
	packaging/nsis/wireshark.nsi
	plugins/Makefile.am
	plugins/Makefile.nmake

The patch file wireshark-1.8.6.patch can be applied to make the
necessary changes.  It may apply, with offsets, back through 1.8.0.

    cd wireshark-1.8.6
    patch -p1 < .../ccn/apps/wireshark/wireshark-1.8.6.patch

When you've made the changes per README.plugins or by applying the patch file,
you must run wireshark's "autogen.sh" and "configure" to setup your wireshark build
area, and then recompile wireshark.  You *may* be able to get away with only
doing a "make" in the plugins/ccn directory rather than recompiling all of wireshark,
however this has not been extensively tested.  Your mileage may vary.

