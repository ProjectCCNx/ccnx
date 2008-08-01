MORE_CFLAGS = -mt -Kpic
MORE_LDLIBS = -lmtmalloc -lnsl -lsocket -L/usr/sfw/lib -R/usr/sfw/lib
CPREFLAGS = -I../include -I/usr/sfw/include
SHEXT = so
SHLIBNAME=libccn.$(SHEXT).1
SHLIBDEPS=
SHARED_LD_FLAGS = -G -z allextract
PLATCFLAGS = -KPIC
