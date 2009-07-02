OPENSSL_CFLAGS = -I/opt/local/include
OPENSSL_LIBS = -L/opt/local/lib
SHEXT=dylib
SHLIBNAME=libccn.1.$(SHEXT)
SHLIBDEPS=/usr/lib/dylib1.o
SHARED_LD_FLAGS = -dylib -arch `/usr/bin/arch` -install_name $(SHLIBNAME) $(OPENSSL_LIBS) -all_load /usr/lib/dylib1.o -lcrypto -lSystem
PLATCFLAGS=-fno-common
CWARNFLAGS = -Wall -Wpointer-arith -Wreturn-type -Wstrict-prototypes

