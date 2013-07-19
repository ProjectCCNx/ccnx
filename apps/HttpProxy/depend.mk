HttpProxy.o: HttpProxy.c ProxyUtil.h SockHop.h ProxyUtil.h \
  ../include/ccn/fetch.h ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h ../include/ccn/uri.h
NetFetch.o: NetFetch.c ProxyUtil.h SockHop.h ProxyUtil.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h \
  ../include/ccn/keystore.h ../include/ccn/signing.h
ProxyUtil.o: ProxyUtil.c ProxyUtil.h
SockHop.o: SockHop.c SockHop.h ProxyUtil.h ProxyUtil.h
