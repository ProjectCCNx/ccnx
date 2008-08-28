SHEXT=dylib
SHLIBNAME=libccn.1.$(SHEXT)
SHLIBDEPS=/usr/lib/dylib1.o
SHARED_LD_FLAGS = -dylib -arch `/usr/bin/arch` -install_name $(SHLIBNAME) -all_load /usr/lib/dylib1.o -lSystem
PLATCFLAGS=-fno-common
CWARNFLAGS = -Wall -Wpointer-arith -Wreturn-type -Wstrict-prototypes
OPENSSL_CFLAGS = -I/opt/local/include
OPENSSL_LIBS = -L/opt/local/lib
