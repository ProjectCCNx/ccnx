MORE_CFLAGS = -mt
MORE_LDLIBS = -L/usr/apache2/lib -lmtmalloc -lnsl -lsocket
CPREFLAGS = -I../include -I/usr/apache2/include -DXML_STATUS_OK=0 -I/usr/local/ssl/include
