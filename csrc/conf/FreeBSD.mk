SHEXT=so
SHLIBNAME=libccn.$(SHEXT).1
SHLIBDEPS=
SHARED_LD_FLAGS= -shared -whole-archive -soname=$(SHLIBNAME) -lc
PLATCFLAGS=-fPIC
RESOLV_LIBS=
CWARNFLAGS = -Wall -Wpointer-arith -Wreturn-type -Wstrict-prototypes
