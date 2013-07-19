IndexSorter.o: IndexSorter.c IndexSorter.h
SyncActions.o: SyncActions.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/ccn_private.h ../include/ccn/schedule.h SyncActions.h \
  SyncBase.h ../include/ccn/loglevels.h sync_plumbing.h SyncRoot.h \
  SyncUtil.h IndexSorter.h SyncNode.h SyncMacros.h SyncPrivate.h \
  SyncTreeWorker.h SyncHashCache.h
SyncBase.o: SyncBase.c SyncMacros.h SyncBase.h ../include/ccn/loglevels.h \
  sync_plumbing.h ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h SyncPrivate.h \
  SyncRoot.h SyncUtil.h IndexSorter.h ../include/ccn/uri.h
SyncHashCache.o: SyncHashCache.c SyncBase.h ../include/ccn/loglevels.h \
  sync_plumbing.h ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h SyncHashCache.h \
  SyncNode.h SyncMacros.h SyncRoot.h SyncUtil.h IndexSorter.h
SyncNode.o: SyncNode.c SyncNode.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h SyncMacros.h SyncRoot.h SyncUtil.h \
  IndexSorter.h
SyncRoot.o: SyncRoot.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/digest.h ../include/ccn/schedule.h ../include/ccn/uri.h \
  ../include/ccn/loglevels.h SyncMacros.h SyncPrivate.h SyncBase.h \
  sync_plumbing.h SyncRoot.h SyncUtil.h IndexSorter.h SyncHashCache.h
SyncTest.o: SyncTest.c SyncActions.h ../include/ccn/charbuf.h SyncBase.h \
  ../include/ccn/loglevels.h sync_plumbing.h ../include/ccn/ccn.h \
  ../include/ccn/coding.h ../include/ccn/indexbuf.h SyncRoot.h SyncUtil.h \
  IndexSorter.h SyncHashCache.h SyncNode.h SyncMacros.h SyncPrivate.h \
  SyncTreeWorker.h ../include/ccn/digest.h ../include/ccn/fetch.h \
  ../include/ccn/uri.h ../include/ccn/seqwriter.h
SyncTreeWorker.o: SyncTreeWorker.c SyncMacros.h SyncNode.h \
  ../include/ccn/ccn.h ../include/ccn/coding.h ../include/ccn/charbuf.h \
  ../include/ccn/indexbuf.h SyncTreeWorker.h SyncHashCache.h SyncUtil.h \
  IndexSorter.h
SyncUtil.o: SyncUtil.c SyncBase.h ../include/ccn/loglevels.h \
  sync_plumbing.h ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h SyncHashCache.h \
  SyncNode.h SyncMacros.h SyncPrivate.h SyncRoot.h SyncUtil.h \
  IndexSorter.h ../include/ccn/uri.h
UpdateSketch.o: UpdateSketch.c
sync_api.o: sync_api.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/digest.h ../include/ccn/loglevels.h \
  ../include/ccn/schedule.h ../include/ccn/sync.h ../include/ccn/uri.h \
  ../include/ccn/ccn_private.h sync_diff.h SyncHashCache.h SyncRoot.h \
  SyncTreeWorker.h SyncUtil.h IndexSorter.h SyncNode.h SyncMacros.h \
  SyncPrivate.h SyncBase.h sync_plumbing.h
sync_diff.o: sync_diff.c ../include/ccn/ccn.h ../include/ccn/coding.h \
  ../include/ccn/charbuf.h ../include/ccn/indexbuf.h \
  ../include/ccn/digest.h ../include/ccn/schedule.h ../include/ccn/sync.h \
  ../include/ccn/uri.h IndexSorter.h SyncNode.h SyncMacros.h \
  SyncPrivate.h SyncBase.h ../include/ccn/loglevels.h sync_plumbing.h \
  SyncRoot.h SyncUtil.h SyncTreeWorker.h SyncHashCache.h sync_diff.h
