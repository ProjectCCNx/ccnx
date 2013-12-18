ccndc-log.o: ccndc-log.c
ccndc-main.o: ccndc-main.c ccndc.h ../include/ccn/charbuf.h ccndc-log.h \
  ccndc-srv.h
ccndc-srv.o: ccndc-srv.c ccndc.h ../include/ccn/charbuf.h ccndc-srv.h \
  ccndc-log.h ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h \
  ../include/ccn/reg_mgmt.h
ccndc.o: ccndc.c ccndc.h ../include/ccn/charbuf.h ccndc-log.h ccndc-srv.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h ../include/ccn/uri.h ../include/ccn/signing.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/strategy_mgmt.h
udplink.o: udplink.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h
