MORE_CFLAGS = -mt -Kpic
MORE_LDLIBS = -L/usr/apache2/lib -lmtmalloc -lnsl -lsocket
CPREFLAGS = -I../include -I/usr/local/ssl/include
SHEXT = so
SHLIBNAME=libccn.$(SHEXT).1
SHLIBDEPS=
SHARED_LD_FLAGS = -G -z allextract
PLATCFLAGS = -KPIC
