ccn_ccnbtoxml.o: ccn_ccnbtoxml.c ../include/ccn/charbuf.h \
  ../include/ccn/coding.h ../include/ccn/extend_dict.h
ccn_splitccnb.o: ccn_splitccnb.c ../include/ccn/coding.h
ccn_xmltoccnb.o: ccn_xmltoccnb.c ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/extend_dict.h
ccnbasicconfig.o: ccnbasicconfig.c ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccnd.h ../include/ccn/uri.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/signing.h \
  ../include/ccn/keystore.h
ccnbuzz.o: ccnbuzz.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccnbx.o: ccnbx.c ../include/ccn/charbuf.h ../include/ccn/coding.h \
  ../include/ccn/ccn.h ../include/ccn/indexbuf.h
ccnc.o: ccnc.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccn_private.h ../include/ccn/lned.h ../include/ccn/uri.h
ccncat.o: ccncat.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h ../include/ccn/uri.h \
  ../include/ccn/fetch.h
ccnsimplecat.o: ccnsimplecat.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccncatchunks.o: ccncatchunks.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccncatchunks2.o: ccncatchunks2.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/schedule.h \
  ../include/ccn/uri.h
ccndumpnames.o: ccndumpnames.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccndumppcap.o: ccndumppcap.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccnd.h
ccnfilewatch.o: ccnfilewatch.c
ccnguestprefix.o: ccnguestprefix.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccnpeek.o: ccnpeek.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccnhexdumpdata.o: ccnhexdumpdata.c ../include/ccn/coding.h \
  ../include/ccn/ccn.h ../include/ccn/charbuf.h ../include/ccn/indexbuf.h
ccninitkeystore.o: ccninitkeystore.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/keystore.h
ccnls.o: ccnls.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccnnamelist.o: ccnnamelist.c ../include/ccn/coding.h ../include/ccn/uri.h \
  ../include/ccn/charbuf.h
ccnpoke.o: ccnpoke.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h ../include/ccn/uri.h \
  ../include/ccn/keystore.h ../include/ccn/signing.h
ccnrm.o: ccnrm.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccnsendchunks.o: ccnsendchunks.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h \
  ../include/ccn/keystore.h ../include/ccn/signing.h
ccnseqwriter.o: ccnseqwriter.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h \
  ../include/ccn/seqwriter.h
ccnsnew.o: ccnsnew.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h
ccnsyncwatch.o: ccnsyncwatch.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/sync.h ../include/ccn/uri.h
ccnsyncslice.o: ccnsyncslice.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/sync.h ../include/ccn/uri.h
ccn_fetch_test.o: ccn_fetch_test.c ../include/ccn/fetch.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccnlibtest.o: ccnlibtest.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h
ccnslurp.o: ccnslurp.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
dataresponsetest.o: dataresponsetest.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccninitaeskeystore.o: ccninitaeskeystore.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/keystore.h \
  ../include/ccn/openssl_ex.h
