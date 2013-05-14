/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2012, 2013 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.ccnx.ccn.impl.sync;

import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNSyncHandler;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.encoding.BinaryXMLDecoder;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.sync.SyncNodeCache.Pending;
import org.ccnx.ccn.io.content.ConfigSlice;
import org.ccnx.ccn.io.content.SyncNodeComposite;
import org.ccnx.ccn.io.content.SyncNodeComposite.SyncNodeElement;
import org.ccnx.ccn.io.content.SyncNodeComposite.SyncNodeType;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.sync.Sync;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

/**
 * This class is the main comparator used to decode sync items and determine what has newly been seen that
 * has not already been seen. The algorithm is based on the similar one implemented in the C code.
 * 
 * The basic idea is that we do a continuous compare of hash trees arriving from sync. The first hash tree
 * of course will contain all names that have not been seen before. After that we do a double tree walk of the
 * the "last" (labeled 'X') and the new (labeled 'Y') hash trees. As we walk we compare the trees to find nodes
 * that are as yet unseen and report the results (a ContentName) to the user via a callback. Every hash that
 * has been walked through in the 'Y' phase is marked 'covered' so that we can immediately discard any exactly
 * matching hashes we see in the future. Since incoming hashes can be disjoint, at the start of each new comparison,
 * we also update the X tree to contain any new items which were seen on the last pass. This can often cause the
 * construction of new hashes which are not the same as hashes we have seen from the network.
 * 
 * New hashes seen from the network are fed into the system via the "addPending" methods.
 * 
 * Note: We purposely don't decode SyncNodeComposites in handlers since they are big and slow and we risk
 * timing out the handler by doing so.
 * 
 * Note about synchronization:  This class is multi-threaded and contains many class global fields which at first
 * glance would seem to need synchronization. However care has been taken to insure that the "run" loop can not be
 * run more than once simultaneously and that all unsynchronized global fields are only referenced from the run
 * routine or by internal methods called only by it so that synchronization is in fact unnecessary.
 *
 */
public class SliceComparator implements Runnable {
	public static final int DECODER_SIZE = 756;
	public static enum SyncCompareState {INIT, PRELOAD, COMPARE, DONE, UPDATE};

	public ScheduledThreadPoolExecutor _executor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1);
	public final int COMPARE_INTERVAL = 100; // ms
	protected BinaryXMLDecoder _decoder;
	protected boolean _needToCompare = true;
	protected boolean _comparing = false;
	protected boolean _shutdown = false;
	
	// Prevents the comparison task from being run more than once simultaneously
	protected Semaphore _compareSemaphore = new Semaphore(1);
	protected NodeFetchHandler _nfh = new NodeFetchHandler();
	protected Object _compareLock = new Object();
	
	protected ConfigSlice _slice;
	protected ArrayList<CCNSyncHandler> _callbacks = new ArrayList<CCNSyncHandler>();
	protected ArrayList<CCNSyncHandler> _pendingCallbacks = new ArrayList<CCNSyncHandler>();
	protected SliceComparator _leadComparator;
	protected CCNHandle _handle;
	protected SyncNodeCache _snc = null;

	protected Stack<SyncTreeEntry> _current = new Stack<SyncTreeEntry>();
	protected Stack<SyncTreeEntry> _next = new Stack<SyncTreeEntry>();
	protected SyncCompareState _state = SyncCompareState.INIT;
	protected ArrayList<SyncTreeEntry> _pendingEntries = new ArrayList<SyncTreeEntry>();
	protected Queue<byte[]> _pendingContent = new ConcurrentLinkedQueue<byte[]>();
	protected SyncTreeEntry _currentRoot = null;
	protected SyncTreeEntry _startHash = null;
	protected ContentName _startName = null;
	protected boolean _doCallbacks = true;
	protected TreeSet<ContentName> _updateNames = new TreeSet<ContentName>();
	protected NodeBuilder _nBuilder = new NodeBuilder();
	
	protected SyncHashCache _shc = new SyncHashCache();
	
	/**
	 * Start a comparison on a slice which will call back each registered "callback" each time
	 * a previously unseen name is seen. Note that with the 0 length hash we can only base the "start" of our
	 * reporting on the hash we get back from the root advise request. If there is more than one repository reporting
	 * and the first answer we get is disjoint from some other answer, we may receive names later that are earlier than
	 * the "start" so this is just a best attempt.
	 * 
	 * @param leadComparator - the lead comparator on this slice. If we have multiple comparators for a slice, we only
	 * 				need to check differences for one round after the first one starts (which becomes the lead comparator), 
	 *              then the callbacks can just be reregistered with the lead comparator
	 * @param snc global node cache to use
	 * @param callback callback to user code
	 * @param slice corresponding slice
	 * @param startHash if null, report all names seen, if 0 length attempt to not report anything before this was called,
	 * 				if contains a hash, report all names that are not in that hash.
	 * @param startName report all names seen after we see this name
	 * @param handle the CCNHandle to use
	 */
	public SliceComparator(SliceComparator leadComparator, SyncNodeCache snc, CCNSyncHandler callback, ConfigSlice slice, 
				byte[] startHash, ContentName startName, CCNHandle handle) {
		if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
			Log.info(Log.FAC_SYNC, "Beginning sync monitoring {0}", null == startHash ? "from start"
					: "starting with: " + Component.printURI(startHash));
		_leadComparator = leadComparator;
		if (null == _leadComparator)
			_leadComparator = this;
		_snc = snc;
		_slice = slice;
		_callbacks.add(callback);
		_handle = handle;
		if (null != startHash)
			_startHash = _shc.addHash(startHash, _snc);
		_startName = startName;
		if (null != startName)
			_doCallbacks = false;
		_decoder = new BinaryXMLDecoder();
		_decoder.setInitialBufferSize(DECODER_SIZE);
	}
	
	/**
	 * Add new data into the system - either a hash which must be looked up or node
	 * data which can be directly decoded. These will be used to start a new hash tree
	 * for the next compare when the current one has completed.
	 * 
	 * These will normally be called by handlers in ProtocolBasedSyncMonitor so
	 * it is therefore handler code (so we should not decode anything here). 
	 *
	 * @param ste entry for new hash
	 * @return
	 */
	public boolean addPending(SyncTreeEntry ste) {
		synchronized (this) {
			for (SyncTreeEntry tste : _pendingEntries) {
				if (ste.equals(tste)) {
					return false;
				}
			}
			for (SyncTreeEntry tste: _next) {
				if (ste.equals(tste)) {
					return false;
				}
			}
			_pendingEntries.add(ste);
			return true;
		}
	}
	
	/**
	 * Add pending hash from seen data
	 * @param data data seen. The hash isn't known until we decode the data
	 */
	public synchronized void addPendingContent(byte[] data) {
		_pendingContent.add(data);
	}
	
	/**
	 * Get the best (highest hash) pending entry for a new comparison. Also
	 * prunes duplicates out of the tree.
	 * 
	 * @return
	 */
	protected SyncTreeEntry getPending() {
		SyncTreeEntry best = null;
		ArrayList<SyncTreeEntry> removes = new ArrayList<SyncTreeEntry>();
		synchronized (this) {
		  outerLoop:
			for (SyncTreeEntry ste : _pendingEntries) {
				for (SyncTreeEntry tste : _current) {
					if (ste.equals(tste)) {
						removes.add(tste);
						continue outerLoop;
					}
				}
				if (!ste.isCovered()) {
					if (best == null)
						best = ste;
					else {
						if (DataUtils.compare(ste.getHash(), best.getHash()) > 0) {
							best = ste;
						}
					}
				} else
					removes.add(ste);
			}
			for (SyncTreeEntry ste : removes) {
				SyncTreeEntry removeMe = null;
				for (SyncTreeEntry tste : _pendingEntries) {
					if (tste.equals(ste)) {
						removeMe = tste;
						break;
					}
				}
				if (null != removeMe)
					_pendingEntries.remove(removeMe);
			}
		}
		return best;
	}
	
	/**
	 * Pending content is used when we don't know the hash ahead of time - we can
	 * only find it by decoding the content. We store and retrieve it because we don't
	 * want to do the decoding in a handler.
	 * 
	 * @return the raw content
	 */
	protected byte[] getPendingContent() {
		synchronized (this) {
			try {
				return _pendingContent.remove();
			} catch (NoSuchElementException nsee) {}
		}
		return null;
	}
	
	/**
	 * Restart compare process if its not currently in process.
	 */
	protected synchronized void checkNextRound() {	
		if (_state == SyncCompareState.DONE) {
			_state = SyncCompareState.INIT;
		}
	}
	
	/**
	 * Switch a callback onto a comparator. The hash is to avoid adding the callback too
	 * early - we don't want to start calling back if we haven't completed working on a hash
	 * already done by the callback. Possibly this isn't necessary as the checks on whether the
	 * comparator has been kicked make this unlikely I think.
	 * 
	 * @param callback
	 * @param hash
	 */
	public void addCallback(CCNSyncHandler callback) {
		synchronized (this) {
			_pendingCallbacks.add(callback);
		}
	}
	
	/**
	 * Get the callbacks
	 * WARNING - any outside changes to this must be synced
	 * @return
	 */
	public synchronized ArrayList<CCNSyncHandler> getCallbacks() {
		return _callbacks;
	}
	
	/**
	 * Remove a callback associated with this comparator
	 * @return the callback
	 */
	public void removeCallback(CCNSyncHandler callback) {
		synchronized (this) {
			_callbacks.remove(callback);
		}
	}
	
	/**
	 * "Useless" is defined as having no callbacks registered
	 * @return
	 */
	public boolean shutdownIfUseless() {
		synchronized (this) {
			if (_callbacks.size() == 0) {
				_shutdown = true;
				_executor.shutdownNow();
			}
			return _shutdown;
		}
	}
	
	public boolean isShutdown() {
		synchronized (this) {
			return _shutdown;
		}
	}
	
	public CCNHandle getHandle() {
		return _handle;
	}
	
	public SyncHashCache getHashCache() {
		return _shc;
	}
	
	public SyncNodeCache getNodeCache() {
		return _snc;
	}
	
	/**
	 * Must return a copy because the STE can be used in a different comparator
	 * @return
	 */
	public SyncTreeEntry getCurrentRoot() {
		synchronized (this) {
			return _currentRoot != null ? new SyncTreeEntry(_currentRoot.getHash(), _snc) : null;
		}
	}
	
	/**
	 * Start compare process if not already running
	 */
	public void kickCompare() {
		synchronized (this) {
			if (! _comparing) {
				_comparing = true;
				_needToCompare = false;
				_executor.schedule(this, COMPARE_INTERVAL, TimeUnit.MILLISECONDS);
			} else
				_needToCompare = true;
		}
	}
	
	public synchronized boolean comparing() {
		return _comparing || _needToCompare;
	}
	
	/**
	 * Request nodes that we will need for the compare. By requesting multiple nodes
	 * simultaneously we can speed up the process.
	 * 
	 * @param srt
	 * @return false if no preloads requested
	 * @throws SyncException 
	 */
	private void doPreload(SyncTreeEntry srt) throws SyncException {
		SyncNodeComposite snc = null;
		snc = srt.getNode(_decoder);
		if (null != snc) {
			for (SyncNodeElement sne : snc.getRefs()) {
				if (sne.getType() == SyncNodeType.HASH) {
					SyncTreeEntry tsrt = _shc.addHash(sne.getData(), _snc);
					getOrRequestNode(tsrt, false);
				}
			}
		}
	}
	
	/**
	 * Nodes can be shared across comparators so if we are missing a node, we really only want to
	 * do one request for the node for the whole slice. Then when the node is returned other comparators
	 * that want it can just retrieve the information from the cache. We use a boolean lock to accomplish this.
	 * In some cases (preload) we want to issue the request but we don't want to wait for an answer. That's
	 * what the wait flag is for.
	 * 
	 * Note: Since we are potentially waiting for an outside object here, we probably don't want to call
	 * this synchronized to avoid potential deadlocks.
	 * 
	 * @param srt
	 * @param wait Wait for the node if true
	 * @return null if node not found but request made
	 * @throws SyncException
	 */
	private SyncNodeComposite getOrRequestNode(SyncTreeEntry srt, boolean wait) throws SyncException {
		SyncNodeComposite node = srt.getNode(_decoder);
		if (null != node)
			return node;
		Pending lock = _snc.pending(srt.getHash());
		long ourTime = System.currentTimeMillis();
		long endTime = ourTime + SystemConfiguration.LONG_TIMEOUT;
		while (wait && lock.getPending()) {
			try {
				synchronized (lock) {
					lock.wait(SystemConfiguration.LONG_TIMEOUT);
				}
			} catch (InterruptedException e) {
				return null;
			}
			node = srt.getNode(_decoder);
			if (null != node)
				return node;
			ourTime = System.currentTimeMillis();
			if (ourTime > endTime) {
				throw new SyncException("Node fetch timeout for: " + Component.printURI(srt.getHash()));
			}
		}
		
		synchronized (lock) {
			if (lock.getPending())	// For non wait case when someone else already requested it
				return null;
			lock.setPending(true);
		}
		ProtocolBasedSyncMonitor.requestNode(_slice, srt.getHash(), _handle, _nfh);
		return null;
	}
	
	private void changeState(SyncCompareState state) {
		synchronized (this) {
			_state = state;
		}
	}
	
	private SyncCompareState getState() {
		synchronized (this) {
			return _state;
		}
	}
	
	/**
	 * This is the critical routine which actually does the comparison. The algorithm involves a double tree 
	 * walk of X and Y and comparison at the leaves.  The compare continues as long as it can - either until 
	 * completion or until it can't continue due to missing information. Callbacks are generated for any node 
	 * which hasn't been seen before. Completed hashes on the Y side are marked "covered". If we see a Y hash 
	 * which matches an X hash marked "covered" we know we can immediately discard the Y hash as containing no 
	 * new information.
	 * 
	 * @throws SyncException
	 */
	private void doComparison() throws SyncException {	
		SyncTreeEntry srtY = getHead(_next);
		SyncTreeEntry srtX = getHead(_current);

		while (null != srtY) {
			SyncNodeComposite sncX = null;
			SyncNodeComposite sncY = null;
			SyncNodeComposite.SyncNodeElement sneX = null;
			SyncNodeComposite.SyncNodeElement sneY = null;
			
			// Get the current X and Y entries for comparison
			// If we have no Y entry we are done. If we have just completed
			// an entire Y hash, mark it covered.
			while (null != srtY && (srtY.lastPos() || srtY.isCovered())) {
				if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
					if (srtY.isCovered())
					Log.finest(Log.FAC_SYNC, "Skipping covered entry {0}", Component.printURI(srtY.getHash()));
				}
				srtY.setCovered(true);
				pop(_next);
				srtY = getHead(_next);
			}
			if (null == srtY) {
				break;  // we're done
			}
			sncY = getOrRequestNode(srtY, true);;
			if (null == sncY) {
				if (Log.isLoggable(Log.FAC_SYNC, Level.FINE))
					Log.fine(Log.FAC_SYNC, "No data for Y: {0}, pos is {1}", Component.printURI(srtY.getHash()), srtY.getPos());
				return;
			}
			if (srtY.lastPos())
				continue;
			sneY = srtY.getCurrentElement();
			
			while (null != srtX && srtX.lastPos()) {
				pop(_current);
				srtX = getHead(_current);
			}
			if (null != srtX) {
				sncX = getOrRequestNode(srtX, true);
				if (null == sncX) {
					if (Log.isLoggable(Log.FAC_SYNC, Level.FINE))
						Log.fine(Log.FAC_SYNC, "No data for X: {0}, pos is {1}", Component.printURI(srtX.getHash()), srtX.getPos());
					return;
				}
				if (srtX.lastPos())
					continue;
				sneX = srtX.getCurrentElement();
			}
				
			if (null == sneX) {
				// We only have a Y tree so we will output all LEAF names that we see	
				if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST))
					Log.finest(Log.FAC_SYNC, "Y hash only for {0}, type is {1}, pos is {2}", 
							Component.printURI(srtY.getHash()), sneY.getType(), srtY.getPos());
				switch (sneY.getType()) {
				case LEAF:
					newName(sneY);
					srtY.incPos();
					break;
				case HASH:
					// The entry is a hash 
					SyncTreeEntry entry = handleHashNode(sneY);
					if (null != entry) {
						entry.setPos(0);
						push(entry, _next);
						srtY.incPos();
						srtY = entry;
					} else {
						return;
					}
				default:
					break;
				}
			} else {
				// We have both X and Y entries. Compare is different depending on what type the entries are
				SyncNodeType typeX = sneX.getType();
				SyncNodeType typeY = sneY.getType();
				if (typeY == SyncNodeType.HASH) {
					SyncTreeEntry entryY = _shc.getHash(sneY.getData());
					if (null != entryY && entryY.isCovered()) {
						if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST))
							Log.finest(Log.FAC_SYNC, "Skipping covered subentry {0}", Component.printURI(entryY.getHash()));
						srtY.incPos();
						continue;
					}
				    if (typeX == SyncNodeType.HASH) {
						// Both nodes
						if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
							Log.finest(Log.FAC_SYNC, "Compare NODE {0} to NODE {1}", Component.printURI(srtX.getHash()), Component.printURI(srtY.getHash()));
							Log.finest(Log.FAC_SYNC, "pos X is {0}, pos Y is {1}", srtX.getPos(), srtY.getPos());
						}
						entryY = handleHashNode(sneY);
						if (null != entryY) {
							entryY.setPos(0);
							SyncTreeEntry entryX = handleHashNode(sneX);
							if (null != entryX) {
								entryX.setPos(0);
								srtX.incPos();
								srtY.incPos();
								push(entryY, _next);
								push(entryX, _current);
								srtX = entryX;
								srtY = entryY;
								if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
									Log.finest(Log.FAC_SYNC, "Pushing X entry {0} and Y entry {1}", Component.printURI(srtX.getHash()), Component.printURI(srtY.getHash()));
								}
							} else
								return;
						} else
							return;
				    } else {
						// X is leaf Y is Node
						if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
							Log.finest(Log.FAC_SYNC, "Compare LEAF {0} to NODE {1}", Component.printURI(srtX.getHash()), Component.printURI(srtY.getHash()));
							Log.finest(Log.FAC_SYNC, "minname is {0}, maxname is {1}, X name is {2}, pos X is {3}, pos Y is {4}", SegmentationProfile.getSegmentNumber(sncY.getMinName().getName().parent()), 
									SegmentationProfile.getSegmentNumber(sncY.getMaxName().getName().parent()), SegmentationProfile.getSegmentNumber(sneX.getName().parent()), srtX.getPos(), srtY.getPos());
							Log.finest(Log.FAC_SYNC, "minname (expanded) is {0}, maxname (expanded) is {1}", sncY.getMinName().getName(), sncY.getMaxName().getName());
							Log.finest(Log.FAC_SYNC, "sneX (expanded) is {0}, comp is {1}", sneX.getName(), sneX.getName().compareTo(sncY.getMinName().getName()));
						}
						if (sneX.getName().compareTo(sncY.getMinName().getName()) < 0) {
							srtX.incPos();
						} else {
							SyncTreeEntry entry = handleHashNode(sneY);
							if (null != entry) {
								entry.setPos(0);
								srtY.incPos();
								push(entry, _next);
								srtY = entry;
							} else {
								return;
							}
						}
				    }
				} else {
					if (typeX == SyncNodeType.HASH) {
						// X is node Y is leaf
						int resMin = sneY.getName().compareTo(sncX.getMinName().getName());
						int resMax = sneY.getName().compareTo(sncX.getMaxName().getName());
						if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
							Log.finest(Log.FAC_SYNC, "Compare NODE {0} to LEAF {1}, resMin is {2}, resMax is {3}", Component.printURI(srtX.getHash()), Component.printURI(srtY.getHash()), resMin, resMax);
							Log.finest(Log.FAC_SYNC, "minname is {0}, maxname is {1}, pos X is {2}, pos Y is {3}", SegmentationProfile.getSegmentNumber(sncX.getMinName().getName().parent()), 
									SegmentationProfile.getSegmentNumber(sncX.getMaxName().getName().parent()), srtX.getPos(), srtY.getPos());
						}
						if (resMin < 0) {
							newName(sneY);
							srtY.incPos();
						} else if (resMin == 0) {
							srtY.incPos();
						} else if (resMax > 0) {
							srtX.incPos();
						} else if (resMax < 0) {
							SyncTreeEntry entry = handleHashNode(sneX);
							if (null != entry) {
								entry.setPos(0);
								srtX.incPos();
								push(entry, _current);
								srtX = entry;
							} else {
								return;
							}
						} else if (resMax == 0) {
							srtX.incPos();
							srtY.incPos();
						} else {
							throw new SyncException("Bogus comparison");
						}
					} else {	
						// Both leaves
						int comp = sneX.getName().compareTo(sneY.getName());
						if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
							Log.finest(Log.FAC_SYNC, "Compare LEAF {0} to LEAF {1}", Component.printURI(srtX.getHash()), Component.printURI(srtY.getHash()));
							Log.finest(Log.FAC_SYNC, "name X is {0}, pos X is {1}, name Y is {2}, pos Y is {3} comp is {4}", SegmentationProfile.getSegmentNumber(sneX.getName().parent()), 
									srtX.getPos(), SegmentationProfile.getSegmentNumber(sneY.getName().parent()), srtY.getPos(), comp);
							Log.finest(Log.FAC_SYNC, "name X (expanded) is {0}, name Y (expanded) is {1}", sneX.getName(), sneY.getName());
						}
						if (srtX.getPos() == 0) {
							// If the highest node in X is before the lowest node in Y no need to go through
							// everything in X
							int comp2 = sncX.getMaxName().getName().compareTo(sncY.getMinName().getName());
							if (comp2 < 0) {
								pop(_current);
								srtX = getHead(_current);
								if (null != srtX) {
									if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
										Log.finest(Log.FAC_SYNC, "Shortcut - popping X back to {0}, pos {1}", Component.printURI(srtX.getHash()), srtX.getPos());
									}
								}
								continue;	// Don't inc X - should have been pre-incremented
							}
						}
						if (comp == 0) {
							srtX.incPos();
							srtY.incPos();
						} else if (comp < 0) {
							srtX.incPos();
						} else {
							newName(sneY);
							srtY.incPos();
						}
					}
				}
			}
		}
		synchronized (this) {
			if (_state == SyncCompareState.COMPARE)
				_state = SyncCompareState.DONE;
		}
	}
	
	/**
	 * Check for the node reference for a hash entry. If we don't already have it, request it.
	 * @param sne
	 * @return
	 * @throws SyncException
	 */
	private SyncTreeEntry handleHashNode(SyncNodeElement sne) throws SyncException {
		byte[] hash = sne.getData();
		SyncTreeEntry entry = _shc.addHash(hash, _snc);
		SyncNodeComposite node = getOrRequestNode(entry, true);
		if (null == node) {
			changeState(SyncCompareState.PRELOAD);
			return null;
		}
		return entry;
	}
	
	/**
	 * Here's where we do the callback(s) back to the user for an unseen content name (which
	 * is within a node). We have to be sure we aren't holding any locks when this is called.
	 * We also add this name to the names already seen so we can update the X tree after this
	 * pass.
	 * 
	 * @param sne
	 */
	private void newName(SyncNodeComposite.SyncNodeElement sne) {
		ContentName name = sne.getName();
		_updateNames.add(name); // we want the digest here
		name = name.parent();  // remove digest here
		if (!_doCallbacks) {
			if (!name.equals(_startName))
				return;
			_doCallbacks = true;
		}
		for (CCNSyncHandler callback : _callbacks)
			callback.handleContentName(_slice, name);
	}
	
	protected void push(SyncTreeEntry srt, Stack<SyncTreeEntry> stack) {
		stack.push(srt);
	}
		
	protected SyncTreeEntry pop(Stack<SyncTreeEntry> stack) {
		if (!stack.empty()) {
			SyncTreeEntry srt =  stack.pop();
			return srt;
		}
		return null;
	}
		
	protected SyncTreeEntry getHead(Stack<SyncTreeEntry> stack) {
		if (! stack.empty()) {
			return stack.peek();
		}
		return null;
	}
	
	/**
	 * Start a new round of comparison. _currentRoot holds the head of
	 * the last Y hash tree so we move it to X (_current) here.
	 */
	protected void nextRound() {
		_current.clear();
		if (_currentRoot != null) {
			_currentRoot.setPos(0);
			push(_currentRoot, _current);
		}
	}
	
	/**
	 * We keep a running tree of what we already have in "X". Update it here to reflect what we
	 * got on the last round.
	 * @throws SyncException 
	 */
	protected boolean updateCurrent() throws SyncException {
		TreeSet<ContentName> neededNames = new TreeSet<ContentName>();
		Stack<SyncTreeEntry> updateStack = new Stack<SyncTreeEntry>();
		boolean newHasNodes = false;
		boolean redo = false;
		SyncTreeEntry ste = getHead(_current);
		push(ste, updateStack);
		SyncTreeEntry origSte = ste;
		SyncTreeEntry newHead = null;
		if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST) && _updateNames.size() > 0)
			Log.finest(Log.FAC_SYNC, "Starting update from hash {0}", Component.printURI(ste.getHash()));
		TreeMap<ContentName, SyncNodeElement> nodeElements = new TreeMap<ContentName, SyncNodeElement>();
		SyncNodeElement thisHashElement = null;
		ContentName thisHashStartName = null;
		
		for (ContentName name : _updateNames) {
			SyncNodeComposite snc = null;
			SyncNodeElement sne = null;
			boolean found = false;
			while (!found && null != ste) {
				while (null != ste && ste.lastPos()) {
					pop(updateStack);
					ste = getHead(updateStack);
				}
				if (null != ste) {
					snc = getOrRequestNode(ste, true);
					if (null == snc) {
						if (Log.isLoggable(Log.FAC_SYNC, Level.FINE))
							Log.fine(Log.FAC_SYNC, "No data for update entry: {0}", Component.printURI(ste.getHash()));
						return false;
					}
					if (ste.lastPos())
						continue;
					sne = ste.getCurrentElement();
					if (null == sne) {
						Log.warning("Missing element for {0} in update - shouldn't happen", Component.printURI(snc.getHash()));
						return false;
					}
				
					switch (sne.getType()) {
					case HASH:
						newHasNodes = true;
						SyncTreeEntry entry = _shc.addHash(sne.getData(), _snc);
						if (null == getOrRequestNode(entry, true)) {
							if (Log.isLoggable(Log.FAC_SYNC, Level.FINE))
								Log.fine(Log.FAC_SYNC, "No data for update entry: {0}", Component.printURI(sne.getData()));
							return false;
						}
						if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
							Log.finest(Log.FAC_SYNC, "Update compare - moving to: {0}", Component.printURI(entry.getHash()));
						}
						thisHashElement = ste.getCurrentElement();
						ste.incPos();
						if (redo) {
							addNeededNames(neededNames, entry);
						} else {
							thisHashStartName = entry.getNode().getMinName().getName();
							push(entry, updateStack);
							ste = entry;
							ste.setPos(0);
						}
						break;
					case LEAF:
						if (ste.getPos() == 0 && !redo) {
							// If we are after everything in X, no need to compare to X
							int comp2 = snc.getMaxName().getName().compareTo(name);
							if (comp2 < 0) {
								pop(updateStack);
								ste = getHead(updateStack);
								if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
									if (null != ste)
										Log.finest(Log.FAC_SYNC, "Shortcut in update - popping back to {0}, pos {1}", Component.printURI(ste.getHash()), ste.getPos());
									else
										Log.finest(Log.FAC_SYNC, "Shortcut in update - popping last node");
								}
								if (thisHashElement != null && thisHashStartName != null) {
									nodeElements.put(thisHashStartName, thisHashElement);
								}
								break;
							}
						}
						int comp = sne.getName().compareTo(name);
						if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST) && _updateNames.size() > 0) {
							Log.finest(Log.FAC_SYNC, "Update compare: name from tree (expanded) is {0}, name  is {1}, comp is {2}", sne.getName(), name, comp);
						}
						ste.incPos();
						if (comp > 0 || redo) {
							// We've have to redo everything starting from here (which means the start of this
							// original node).
							redo = true;
							if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
								Log.finest(Log.FAC_SYNC, "Update: starting redo of names because of {0} at {1}", name, snc.getMinName().getName());
							}
							neededNames.add(name);
							for (SyncNodeElement tsne: snc.getRefs()) {
								neededNames.add(tsne.getName());
							}
							pop(updateStack);
							ste = getHead(updateStack);
							break;
						} else if (comp == 0) {
							found = true;
						}
						if (ste.lastPos() && thisHashElement != null && thisHashStartName != null) {
							nodeElements.put(thisHashStartName, thisHashElement);
						}
						break;
					default:
						break;
					}
				}
			}
			if (!found) {
				if (!redo && !newHasNodes) {
					// This is the case of a single node tree where everything in the original
					// single node was covered but we have more to add afterwards. We need to
					// Add all the original names into ones to put into the new node. Those will
					// be in origSte
					if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
						Log.finest(Log.FAC_SYNC, "Update: starting redo because of extra names at end starting with {0}", name);
					}
					addNeededNames(neededNames, origSte);
				}
				redo = true;
				neededNames.add(name);
			}
		}
		
		while (neededNames.size() > 0) {
			ContentName firstName = neededNames.first();
			newHead = _nBuilder.newLeafNode(neededNames, _shc, _snc);
			_shc.putHashEntry(newHead);
			if (neededNames.size() > 0) {	// Need to split
				newHasNodes = true;
			}
			if (newHasNodes)
				nodeElements.put(firstName, new SyncNodeElement(newHead.getHash()));
		}
		if (redo && newHasNodes) {
			newHead = _nBuilder.createHeadRecursive(nodeElements.values(), _shc, _snc, 2);
		}
		_current.clear();
		if (null != newHead) {
			push(newHead, _current);
			_currentRoot = newHead;
			if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
				Log.info(Log.FAC_SYNC, "Resetting current to created head: {0}", Component.printURI(newHead.getHash()));
		} else {
			origSte.setPos(0);
			_currentRoot = origSte;
		}
		return true;
	}
	
	/**
	 * This entry will be redone - add all of its names to the list and remove it
	 */
	private void addNeededNames(TreeSet<ContentName> neededNames, SyncTreeEntry ste) {
		if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST))
			Log.finest(Log.FAC_SYNC, "Update: subsuming node", Component.printURI(ste.getHash()));
		for (SyncNodeElement tsne: ste.getNode().getRefs()) {
			if (tsne.getType() != SyncNodeType.LEAF) {
				if (tsne.getType() == SyncNodeType.HASH) {
					ste = _shc.getHash(tsne.getData());
					if (null != ste)
						addNeededNames(neededNames, ste);
				}
			} else
				neededNames.add(tsne.getName());
		}
		if (ste.isLocal())
			_shc.removeHashEntry(ste);
	}
	
	/**
	 * Separate thread for running comparisons. We run until we can't do anything
	 * anymore, then rely on "kickCompare" to restart the thread. It uses a state
	 * machine to figure out where it left off and where to start back in again.
	 */
	public void run() {
		boolean didARound = false;
		synchronized (this) {
			if (_shutdown)
				return;
		}
		if (Log.isLoggable(Log.FAC_SYNC, Level.FINE))
			Log.fine(Log.FAC_SYNC, "Starting comparator run - state is {0}, sc is {1}, this {2} the lead", 
					_state, this, (this == _leadComparator ? "is": "is not"));
		_compareSemaphore.acquireUninterruptibly();
		boolean keepComparing = true;
		try {
			do {
				synchronized (this) {
					if (_shutdown)
						return;
				}
				switch (getState()) {
				case INIT:		// Starting a new compare
					if (Log.isLoggable(Log.FAC_SYNC, Level.FINE)  && null != _startHash)
						Log.fine(Log.FAC_SYNC, "Init - startHash is {0}", Component.printURI(_startHash.getHash()));
					byte[] data = null;
					if (null != _startHash && _startHash.getHash().length > 0) {
						if (Log.isLoggable(Log.FAC_SYNC, Level.FINE))
							Log.fine(Log.FAC_SYNC, "Setting root to {0} based on startHash", 
									Component.printURI(_startHash.getHash()));
						_currentRoot = _startHash;
						_startHash = null;
						nextRound();
					}
					do {
						data = getPendingContent();
						if (null != data) {
							SyncNodeComposite snc = new SyncNodeComposite();
							snc.decode(data);
							SyncTreeEntry ste = _shc.addHash(snc.getHash(), _snc);
							ste.setNode(snc);
							if (null != _startHash) {  
								// has to be 0 length so start with "current"
								// This would be only in the case where we are starting a new
								// sync with a 0 length hash request (the other is where we already
								// had a sync running which is taken care of in ProtocolBasedSyncMonitor)
								if (Log.isLoggable(Log.FAC_SYNC, Level.FINE))
									Log.fine(Log.FAC_SYNC, "Setting root to {0} based on zero length startHash", 
											Component.printURI(ste.getHash()));
								_currentRoot = ste;
								_startHash = null;
								nextRound();
							} else {        // No sense doing a compare with ourself
								addPending(ste);
							}
						}
					} while (null != data);
					
					SyncTreeEntry ste = getPending();
					if (null != ste) {
						ste.setPos(0);
						_next.clear();
						push(ste, _next);
						if (null == _currentRoot)
							_currentRoot = ste;
						else
							nextRound();
						changeState(SyncCompareState.PRELOAD);
						didARound = true;
					}
						
					if (getState() == SyncCompareState.INIT) {
						changeState(SyncCompareState.DONE);
						break;
					} else {
						if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
							Log.info(Log.FAC_SYNC, "Starting new round with X = {0} and Y = {1}",
									(null == getHead(_current) ? "null" : Component.printURI(getHead(_current).getHash())),
									Component.printURI(getHead(_next).getHash()));
					}
					synchronized (this) {
						for (CCNSyncHandler callback : _pendingCallbacks) {
							_callbacks.add(callback);
						}
						_pendingCallbacks.clear();
					}
					// Fall through
				case PRELOAD:	// Need to load data for the compare
					synchronized (this) {
						if (_shutdown)
							return;
					}
					if (null == getHead(_next)) {
						keepComparing = false;
						break;
					}
					
					// We might have had to do some preloads but ending here is too risky since
					// someone else might be receiving the data. So we'll just wait in compare for
					// our data if we need it. At least we've now requested multiple data if we
					// need it
					doPreload(getHead(_next));
					changeState(SyncCompareState.COMPARE);
					// Fall through
				case COMPARE:	// We are currently in the process of comparing
					synchronized (this) {
						if (_shutdown)
							return;
					}
					doComparison();
					if (getState() == SyncCompareState.COMPARE) {
						keepComparing = false;
					}
					break;
				case DONE:	// Compare is done. Start over again if we have pending data
							// for another compare
					nextRound();
					changeState(SyncCompareState.UPDATE);
					// Fall Through
				case UPDATE:
					synchronized (this) {
						if (_shutdown)
							return;
					}
					if (_updateNames.size() > 0) {
						if (updateCurrent())
							_updateNames.clear();
						else
							break;
					}

					if (this != _leadComparator && didARound) {
						// If we aren't the lead comparator for this slice we might not need to 
						// continue - because we could just be doing redundant compares. But we need
						// to be careful about this. We don't want to do this if the lead comparator
						// is in the middle of doing a comparison. We also can only really safely do it if
						// our current (X) hash and the lead comparator's are equal. Otherwise we could end
						// up with disjoint hashes between ourself and the lead comparator leading to missing
						// or extra callbacks from the lead comparator.
						synchronized (_leadComparator) {
							boolean doSwitch = (! _leadComparator._needToCompare) && (_leadComparator.getState() == SyncCompareState.INIT);
							if (doSwitch) {
								SyncTreeEntry lXste = _leadComparator.getCurrentRoot();
								if (null == lXste || null == _currentRoot)	// May not happen?
									doSwitch = false;
								else
									doSwitch = DataUtils.compare(lXste.getHash(), _currentRoot.getHash()) == 0;
							}
							if (doSwitch) {
								// Yes we can switch the callback to the lead comparator.
								// Note that eventually this will lead to this comparator being culled.
								// We might want to do that explicitly but for now I'm not going to 
								// worry about it.
								for (CCNSyncHandler callback : _callbacks)	// There should never really be more than one here			
									_leadComparator.addCallback(callback);
								synchronized (this) {  // Is this dangerous? - I don't think so
									_shutdown = true;
								}
								if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
									Log.info(Log.FAC_SYNC, "Moving {0} callbacks to lead comparator", _callbacks.size());
							}
						}
					}
					synchronized (this) {
						changeState(SyncCompareState.INIT);
						if (_pendingEntries.size() > 0) {
							break;
						}
						notifyAll();
					}
					
				default:
					keepComparing = false;
					break;
				}
				synchronized (this) {
					if (!keepComparing) {
						if (_needToCompare) {
							keepComparing = true;
							_needToCompare = false;
						}
						else
							_comparing = false;
					}
				}
			} while (keepComparing);
			_compareSemaphore.release();
		} catch (Exception ex) {
			Log.logStackTrace(Log.FAC_SYNC, Level.WARNING, ex);
			changeState(SyncCompareState.INIT);

		} catch (Error er) {
			Log.logStackTrace(Log.FAC_SYNC, Level.WARNING, er);
			changeState(SyncCompareState.INIT);
		}
	}
	
	/**
	 * Get back data from a node fetch request
	 */
	protected class NodeFetchHandler implements CCNContentHandler {
	
		public Interest handleContent(ContentObject data, Interest interest) {
			ContentName name = data.name();

			int hashComponent = name.containsWhere(Sync.SYNC_NODE_FETCH_MARKER);
			if (hashComponent < 0 || name.count() < (hashComponent + 1)) {
				if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
					Log.info(Log.FAC_SYNC, "Received incorrect node content in sync: {0}", name);
				return null;
			}
			byte[] hash = name.component(hashComponent + 2);
			if (Log.isLoggable(Log.FAC_SYNC, Level.FINE))
				Log.fine(Log.FAC_SYNC, "Saw data from nodefind: hash: {0}", Component.printURI(hash));
			SyncTreeEntry ste = _shc.addHash(hash, _snc);
			ste.setRawContent(data.content());
			_snc.wakeupPending(hash);
			kickCompare();
			return null;
		}
	}
}
