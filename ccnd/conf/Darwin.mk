SHEXT=dylib
SHLIBNAME=libccn.1.$(SHEXT)
SHLIBDEPS=/usr/lib/dylib1.o
SHARED_LD_FLAGS = -dylib -arch `/usr/bin/arch` -install_name $(SHLIBNAME) -all_load /usr/lib/dylib1.o -lSystem
PLATCFLAGS=-fno-common
CWARNFLAGS = -Wall
