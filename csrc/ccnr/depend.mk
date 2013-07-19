ccnr_dispatch.o: ccnr_dispatch.c ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ../sync/SyncBase.h \
  ../include/ccn/loglevels.h ../sync/sync_plumbing.h ccnr_private.h \
  ../include/ccn/seqwriter.h ccnr_dispatch.h ccnr_forwarding.h ccnr_io.h \
  ccnr_link.h ccnr_match.h ccnr_msg.h ccnr_proto.h ccnr_sendq.h \
  ccnr_stats.h ccnr_store.h ccnr_sync.h ccnr_util.h
ccnr_forwarding.o: ccnr_forwarding.c ../include/ccn/bloom.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h ccnr_forwarding.h ccnr_io.h ccnr_link.h \
  ccnr_match.h ccnr_msg.h ../include/ccn/loglevels.h ccnr_stats.h \
  ccnr_util.h
ccnr_init.o: ccnr_init.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ../sync/sync_plumbing.h \
  ../sync/SyncActions.h ../sync/SyncBase.h ../include/ccn/loglevels.h \
  ../sync/sync_plumbing.h ../sync/SyncRoot.h ../sync/SyncUtil.h \
  ../sync/IndexSorter.h ccnr_private.h ../include/ccn/seqwriter.h \
  ccnr_init.h ccnr_dispatch.h ccnr_forwarding.h ccnr_internal_client.h \
  ccnr_io.h ccnr_msg.h ccnr_net.h ccnr_proto.h ccnr_sendq.h ccnr_store.h \
  ccnr_sync.h ccnr_util.h
ccnr_internal_client.o: ccnr_internal_client.c ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/schedule.h ../include/ccn/sockaddrutil.h \
  ../include/ccn/uri.h ../include/ccn/keystore.h ccnr_private.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/seqwriter.h \
  ccnr_internal_client.h ccnr_forwarding.h ../include/ccn/hashtb.h \
  ccnr_io.h ccnr_msg.h ../include/ccn/loglevels.h ccnr_proto.h \
  ccnr_util.h
ccnr_io.o: ccnr_io.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h ccnr_io.h ccnr_forwarding.h \
  ccnr_internal_client.h ccnr_link.h ccnr_msg.h \
  ../include/ccn/loglevels.h ccnr_sendq.h ccnr_stats.h
ccnr_link.o: ccnr_link.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h ccnr_link.h ccnr_forwarding.h \
  ccnr_internal_client.h ccnr_io.h ccnr_match.h ccnr_msg.h \
  ../include/ccn/loglevels.h ccnr_sendq.h ccnr_stats.h ccnr_store.h \
  ccnr_util.h
ccnr_main.o: ccnr_main.c ccnr_private.h ../include/ccn/ccn_private.h \
  ../include/ccn/coding.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/charbuf.h ../include/ccn/schedule.h \
  ../include/ccn/seqwriter.h ccnr_init.h ccnr_dispatch.h ccnr_msg.h \
  ../include/ccn/loglevels.h ccnr_stats.h
ccnr_match.o: ccnr_match.c ../include/ccn/bloom.h \
  ../include/ccn/btree_content.h ../include/ccn/btree.h \
  ../include/ccn/charbuf.h ../include/ccn/hashtb.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccn_private.h ../include/ccn/face_mgmt.h \
  ../include/ccn/sockcreate.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h ccnr_match.h ccnr_forwarding.h ccnr_io.h \
  ccnr_msg.h ../include/ccn/loglevels.h ccnr_sendq.h ccnr_store.h
ccnr_msg.o: ccnr_msg.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h ../include/ccn/uri.h \
  ccnr_private.h ../include/ccn/ccn_private.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/schedule.h ../include/ccn/seqwriter.h ccnr_msg.h \
  ../include/ccn/loglevels.h
ccnr_net.o: ccnr_net.c ccnr_private.h ../include/ccn/ccn_private.h \
  ../include/ccn/coding.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/charbuf.h ../include/ccn/schedule.h \
  ../include/ccn/seqwriter.h ccnr_net.h ccnr_io.h ccnr_msg.h \
  ../include/ccn/loglevels.h
ccnr_proto.o: ccnr_proto.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccn_private.h ../include/ccn/hashtb.h \
  ../include/ccn/schedule.h ../include/ccn/sockaddrutil.h \
  ../include/ccn/uri.h ../sync/SyncBase.h ../include/ccn/loglevels.h \
  ../sync/sync_plumbing.h ccnr_private.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/seqwriter.h ccnr_proto.h ccnr_dispatch.h \
  ccnr_forwarding.h ccnr_init.h ccnr_io.h ccnr_msg.h ccnr_sendq.h \
  ccnr_store.h ccnr_sync.h ccnr_util.h
ccnr_sendq.o: ccnr_sendq.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h ccnr_sendq.h ccnr_io.h ccnr_link.h \
  ccnr_msg.h ../include/ccn/loglevels.h ccnr_store.h
ccnr_stats.o: ccnr_stats.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/schedule.h ../include/ccn/sockaddrutil.h \
  ../include/ccn/hashtb.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/ccn_private.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/seqwriter.h ccnr_stats.h ccnr_io.h ccnr_msg.h \
  ../include/ccn/loglevels.h
ccnr_store.o: ccnr_store.c ../include/ccn/bloom.h \
  ../include/ccn/btree_content.h ../include/ccn/btree.h \
  ../include/ccn/charbuf.h ../include/ccn/hashtb.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccn_private.h ../include/ccn/face_mgmt.h \
  ../include/ccn/sockcreate.h ../include/ccn/flatname.h \
  ../include/ccn/schedule.h ../include/ccn/reg_mgmt.h \
  ../include/ccn/uri.h ccnr_private.h ../include/ccn/seqwriter.h \
  ccnr_stats.h ccnr_store.h ccnr_init.h ccnr_link.h ccnr_util.h \
  ccnr_proto.h ccnr_msg.h ../include/ccn/loglevels.h ccnr_sync.h \
  ccnr_match.h ccnr_sendq.h ccnr_io.h
ccnr_sync.o: ccnr_sync.c ../include/ccn/btree.h ../include/ccn/charbuf.h \
  ../include/ccn/hashtb.h ../include/ccn/btree_content.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/indexbuf.h \
  ../include/ccn/schedule.h ../sync/SyncBase.h ../include/ccn/loglevels.h \
  ../sync/sync_plumbing.h ccnr_private.h ../include/ccn/ccn_private.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/seqwriter.h ccnr_dispatch.h \
  ccnr_io.h ccnr_link.h ccnr_msg.h ccnr_proto.h ccnr_store.h ccnr_sync.h \
  ccnr_util.h ../sync/sync_plumbing.h
ccnr_util.o: ccnr_util.c ../include/ccn/bloom.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h ../include/ccn/ccn_private.h \
  ../include/ccn/face_mgmt.h ../include/ccn/sockcreate.h \
  ../include/ccn/hashtb.h ../include/ccn/schedule.h \
  ../include/ccn/reg_mgmt.h ../include/ccn/uri.h ccnr_private.h \
  ../include/ccn/seqwriter.h ccnr_util.h
