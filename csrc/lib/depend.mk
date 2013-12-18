basicparsetest.o: basicparsetest.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/face_mgmt.h \
  ../include/ccn/sockcreate.h ../include/ccn/strategy_mgmt.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/header.h
ccn_aes_keystore.o: ccn_aes_keystore.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/digest.h \
  ../include/ccn/keystore.h ../include/ccn/aeskeystoreasn1.h \
  ../include/ccn/openssl_ex.h
ccn_aes_keystore_asn1.o: ccn_aes_keystore_asn1.c \
  ../include/ccn/aeskeystoreasn1.h
ccn_bloom.o: ccn_bloom.c ../include/ccn/bloom.h
ccn_btree.o: ccn_btree.c ../include/ccn/charbuf.h \
  ../include/ccn/flatname.h ../include/ccn/hashtb.h \
  ../include/ccn/btree.h
ccn_btree_content.o: ccn_btree_content.c ../include/ccn/btree.h \
  ../include/ccn/charbuf.h ../include/ccn/hashtb.h \
  ../include/ccn/btree_content.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/indexbuf.h \
  ../include/ccn/bloom.h ../include/ccn/flatname.h
ccn_btree_store.o: ccn_btree_store.c ../include/ccn/btree.h \
  ../include/ccn/charbuf.h ../include/ccn/hashtb.h
ccn_buf_decoder.o: ccn_buf_decoder.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccn_buf_encoder.o: ccn_buf_encoder.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/signing.h \
  ../include/ccn/ccn_private.h
ccn_bulkdata.o: ccn_bulkdata.c ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccn_charbuf.o: ccn_charbuf.c ../include/ccn/charbuf.h
ccn_client.o: ccn_client.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccn_private.h ../include/ccn/ccnd.h \
  ../include/ccn/digest.h ../include/ccn/hashtb.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/schedule.h \
  ../include/ccn/signing.h ../include/ccn/keystore.h ../include/ccn/uri.h
ccn_coding.o: ccn_coding.c ../include/ccn/coding.h
ccn_digest.o: ccn_digest.c ../include/ccn/digest.h
ccn_dtag_table.o: ccn_dtag_table.c ../include/ccn/coding.h
ccn_extend_dict.o: ccn_extend_dict.c ../include/ccn/charbuf.h \
  ../include/ccn/extend_dict.h ../include/ccn/coding.h
ccn_face_mgmt.o: ccn_face_mgmt.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/face_mgmt.h \
  ../include/ccn/sockcreate.h
ccn_fetch.o: ccn_fetch.c ../include/ccn/fetch.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccn_flatname.o: ccn_flatname.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/flatname.h \
  ../include/ccn/uri.h
ccn_header.o: ccn_header.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/header.h
ccn_indexbuf.o: ccn_indexbuf.c ../include/ccn/indexbuf.h
ccn_interest.o: ccn_interest.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccn_keystore.o: ccn_keystore.c ../include/ccn/keystore.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h
ccn_match.o: ccn_match.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/digest.h
ccn_merkle_path_asn1.o: ccn_merkle_path_asn1.c \
  ../include/ccn/merklepathasn1.h
ccn_name_util.o: ccn_name_util.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/random.h
ccn_nametree.o: ccn_nametree.c ../include/ccn/charbuf.h \
  ../include/ccn/flatname.h ../include/ccn/nametree.h
ccn_reg_mgmt.o: ccn_reg_mgmt.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/reg_mgmt.h
ccn_schedule.o: ccn_schedule.c ../include/ccn/schedule.h
ccn_seqwriter.o: ccn_seqwriter.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/seqwriter.h
ccn_setup_sockaddr_un.o: ccn_setup_sockaddr_un.c ../include/ccn/ccnd.h \
  ../include/ccn/ccn_private.h ../include/ccn/charbuf.h
ccn_signing.o: ccn_signing.c ../include/ccn/merklepathasn1.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/signing.h \
  ../include/ccn/random.h ../include/ccn/openssl_ex.h
ccn_sockaddrutil.o: ccn_sockaddrutil.c ../include/ccn/charbuf.h \
  ../include/ccn/sockaddrutil.h
ccn_sockcreate.o: ccn_sockcreate.c ../include/ccn/sockcreate.h
ccn_strategy_mgmt.o: ccn_strategy_mgmt.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/strategy_mgmt.h
ccn_traverse.o: ccn_traverse.c ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccn_uri.o: ccn_uri.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h ../include/ccn/uri.h
ccn_verifysig.o: ccn_verifysig.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/keystore.h \
  ../include/ccn/signing.h
ccn_versioning.o: ccn_versioning.c ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/uri.h \
  ../include/ccn/ccn_private.h
ccnbtreetest.o: ccnbtreetest.c ../include/ccn/btree.h \
  ../include/ccn/charbuf.h ../include/ccn/hashtb.h \
  ../include/ccn/btree_content.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/indexbuf.h \
  ../include/ccn/flatname.h ../include/ccn/uri.h
encodedecodetest.o: encodedecodetest.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/bloom.h ../include/ccn/uri.h \
  ../include/ccn/digest.h ../include/ccn/keystore.h \
  ../include/ccn/signing.h ../include/ccn/random.h
hashtb.o: hashtb.c ../include/ccn/hashtb.h ../include/ccn/siphash24.h
hashtbtest.o: hashtbtest.c ../include/ccn/hashtb.h
lned.o: lned.c ../include/ccn/lned.h
nametreetest.o: nametreetest.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/flatname.h \
  ../include/ccn/nametree.h ../include/ccn/uri.h
signbenchtest.o: signbenchtest.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/keystore.h
siphash24.o: siphash24.c
skel_decode_test.o: skel_decode_test.c ../include/ccn/charbuf.h \
  ../include/ccn/coding.h
