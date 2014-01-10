ccnd_main.o: ccnd_main.c ccnd_private.h ../include/ccn/ccn_private.h \
  ../include/ccn/coding.h ../include/ccn/nametree.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/charbuf.h \
  ../include/ccn/schedule.h ../include/ccn/seqwriter.h ccnd_strategy.h
ccnd.o: ccnd.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/ccnd.h ../include/ccn/face_mgmt.h \
  ../include/ccn/sockcreate.h ../include/ccn/flatname.h \
  ../include/ccn/hashtb.h ../include/ccn/nametree.h \
  ../include/ccn/schedule.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/strategy_mgmt.h ../include/ccn/uri.h ccnd_private.h \
  ../include/ccn/seqwriter.h ccnd_strategy.h
ccnd_msg.o: ccnd_msg.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h ../include/ccn/flatname.h ../include/ccn/hashtb.h \
  ../include/ccn/uri.h ccnd_private.h ../include/ccn/ccn_private.h \
  ../include/ccn/nametree.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/schedule.h ../include/ccn/seqwriter.h ccnd_strategy.h
ccnd_stats.o: ccnd_stats.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h ../include/ccn/schedule.h \
  ../include/ccn/sockaddrutil.h ../include/ccn/hashtb.h \
  ../include/ccn/nametree.h ../include/ccn/uri.h ccnd_private.h \
  ../include/ccn/ccn_private.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/seqwriter.h ccnd_strategy.h
ccnd_internal_client.o: ccnd_internal_client.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/hashtb.h ../include/ccn/keystore.h \
  ../include/ccn/schedule.h ../include/ccn/sockaddrutil.h \
  ../include/ccn/uri.h ccnd_private.h ../include/ccn/nametree.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/seqwriter.h ccnd_strategy.h
ccnd_stregistry.o: ccnd_stregistry.c ccnd_stregistry.h ccnd_strategy.h
default_strategy.o: default_strategy.c ccnd_strategy.h ccnd_private.h \
  ../include/ccn/ccn_private.h ../include/ccn/coding.h \
  ../include/ccn/nametree.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/charbuf.h ../include/ccn/schedule.h \
  ../include/ccn/seqwriter.h
null_strategy.o: null_strategy.c ccnd_strategy.h
trace_strategy.o: trace_strategy.c ../include/ccn/charbuf.h \
  ccnd_strategy.h ccnd_private.h ../include/ccn/ccn_private.h \
  ../include/ccn/coding.h ../include/ccn/nametree.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/schedule.h \
  ../include/ccn/seqwriter.h ccnd_stregistry.h
parallel_strategy.o: parallel_strategy.c ccnd_strategy.h
loadsharing_strategy.o: loadsharing_strategy.c ccnd_strategy.h
faceattr_strategy.o: faceattr_strategy.c ../include/ccn/charbuf.h \
  ccnd_strategy.h
ccndsmoketest.o: ccndsmoketest.c ../include/ccn/ccnd.h \
  ../include/ccn/ccn_private.h
