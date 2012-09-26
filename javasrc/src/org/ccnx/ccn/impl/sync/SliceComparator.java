/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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

import java.io.IOException;
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
import org.ccnx.ccn.impl.encoding.BinaryXMLDecoder;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
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
 */
public class SliceComparator implements Runnable {
	public static final int DECODER_SIZE = 756;
	public static enum SyncCompareState {INIT, PRELOAD, COMPARE, DONE};

	public ScheduledThreadPoolExecutor _executor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1);
	public final int COMPARE_INTERVAL = 100; // ms
	protected BinaryXMLDecoder _decoder;
	protected Object _timerLock = new Object();
	protected boolean _needToCompare = true;
	protected boolean _comparing = false;
	
	// Prevents the comparison task from being run more than once simultaneously
	protected Semaphore _compareSemaphore = new Semaphore(1);
	protected NodeFetchHandler _nfh = new NodeFetchHandler();
	protected Object _compareLock = new Object();
	
	protected ConfigSlice _slice;
	protected CCNSyncHandler _callback;
	protected CCNHandle _handle;
	protected ProtocolBasedSyncMonitor _pbsm = null;

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
	
	/**
	 * Start a comparison on a slice which will call back "callback" each time
	 * a previously unseen name is seen. Note that with the 0 length hash we can only base the "start" of our
	 * reporting on the hash we get back from the root advise request. If there is more than one repository reporting
	 * and the first answer we get is disjoint from some other answer, we may receive names later that are earlier than
	 * the "start" so this is just a best attempt.
	 * 
	 * @param pbsm the SyncMonitor which created us
	 * @param callback callback to user code
	 * @param slice corresponding slice
	 * @param startHash if null, report all names seen, if 0 length attempt to not report anything before this was called,
	 * 				if contains a hash, report all names that are not in that hash.
	 * @param startName report all names seen after we see this name
	 * @param handle the CCNHandle to use
	 */
	public SliceComparator(ProtocolBasedSyncMonitor pbsm, CCNSyncHandler callback, ConfigSlice slice, 
				SyncTreeEntry startHash, ContentName startName, CCNHandle handle) {
		if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
			Log.info(Log.FAC_SYNC, "Beginning sync monitoring {0}", null == startHash ? "from start"
					: "starting with: " + Component.printURI(startHash.getHash()));
		_pbsm = pbsm;
		_slice = slice;
		_callback = callback;
		_handle = handle;
		_startHash = startHash;
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
	public void addPendingContent(byte[] data) {
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
				_pendingEntries.remove(ste);
			}
		}
		return best;
	}
	
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
	 * 
	 * Must be called synchronized with "this".
	 */
	protected void checkNextRound() {
		if (_state == SyncCompareState.DONE) {
			_state = SyncCompareState.INIT;
		}
	}
	
	/**
	 * Get callback associated with this comparator
	 * @return the callback
	 */
	public CCNSyncHandler getCallback() {
		return _callback;
	}

	/**
	 * Start compare process if not already running
	 */
	public void kickCompare() {
		synchronized (_timerLock) {
			if (! _comparing) {
				_comparing = true;
				_needToCompare = false;
				_executor.schedule(this, COMPARE_INTERVAL, TimeUnit.MILLISECONDS);
			} else
				_needToCompare = true;
		}
	}
	
	/**
	 * Request nodes that we will need for the compare. By requesting multiple nodes
	 * simultaneously we can speed up the process.
	 * 
	 * @param srt
	 * @return false if no preloads requested
	 * @throws SyncException 
	 */
	private boolean doPreload(SyncTreeEntry srt) throws SyncException {
		Boolean ret = false;
		SyncNodeComposite snc = null;
		synchronized (this) {
			snc = srt.getNode(_decoder);
		}
		if (null != snc) {
			for (SyncNodeElement sne : snc.getRefs()) {
				if (sne.getType() == SyncNodeType.HASH) {
					byte[] hash = sne.getData();
					ret = requestNode(hash);
				}
			}
		}
		return ret;
	}
	
	/**
	 * Request node if not already requested.
	 * 
	 * @param topo
	 * @param sr
	 * @param hash
	 * @return true if request made
	 * @throws IOException 
	 */
	private boolean requestNode(byte[] hash) throws SyncException {
		boolean ret = false;
		SyncTreeEntry tsrt = _pbsm.addHash(hash);
		synchronized (this) {
			if (tsrt.getNode(_decoder) == null && !tsrt.getPending()) {
				tsrt.setPending(true);
				ret = ProtocolBasedSyncMonitor.requestNode(_slice, hash, _handle, _nfh);
			}
		}
		return ret;
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
	 * This is the critical routine which actually does the comparison. It continues
	 * the compare as long as it can - either until completion or until it can't continue
	 * due to missing information. A callback is generated for any node which hasn't been
	 * seen before. Completed hashes on the Y side are marked "covered".
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
			synchronized (this) {
				sncY = srtY.getNode(_decoder);
			}
			if (null != sncY) {
				sneY = srtY.getCurrentElement();
			}
			if (null == sneY) {
				if (Log.isLoggable(Log.FAC_SYNC, Level.FINE))
					Log.fine(Log.FAC_SYNC, "No data for Y: {0}, pos is {1}", Component.printURI(srtY.getHash()), srtY.getPos());
				requestNode(srtY.getHash());
				return;
			}
			
			while (null != srtX && srtX.lastPos()) {
				pop(_current);
				srtX = getHead(_current);
			}
			if (null != srtX) {
				synchronized (this) {
					sncX = srtX.getNode(_decoder);
				}
				if (null != sncX) {
					sneX = srtX.getCurrentElement();
				}
				if (null == sneX) {
					if (Log.isLoggable(Log.FAC_SYNC, Level.FINE))
						Log.fine(Log.FAC_SYNC, "No data for X: {0}, pos is {1}", Component.printURI(srtY.getHash()), srtY.getPos());
					requestNode(srtX.getHash());
					return;
				}
			}
			
			if (null == sneX) {
				// We only have a Y tree so we will output all LEAF names that we see	
				if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST))
					Log.finest(Log.FAC_SYNC, "Y hash only for {0}, type is {1}", Component.printURI(srtY.getHash()), sneY.getType());
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
					SyncTreeEntry entryY = _pbsm.getHash(sneY.getData());
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
						if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
							Log.finest(Log.FAC_SYNC, "Compare NODE {0} to LEAF {1}", Component.printURI(srtX.getHash()), Component.printURI(srtY.getHash()));
							Log.finest(Log.FAC_SYNC, "minname is {0}, maxname is {1}, pos X is {2}, pos Y is {3}", SegmentationProfile.getSegmentNumber(sncX.getMinName().getName().parent()), 
									SegmentationProfile.getSegmentNumber(sncX.getMaxName().getName().parent()), srtX.getPos(), srtY.getPos());
						}
						int resMin = sneY.getName().compareTo(sncX.getMinName().getName());
						int resMax = sneY.getName().compareTo(sncX.getMaxName().getName());
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
		SyncTreeEntry entry = _pbsm.getHash(hash);
		if (null == entry) {
			if (requestNode(sne.getData())) {
				changeState(SyncCompareState.PRELOAD);
			}
		}
		return entry;
	}
	
	/**
	 * Here's where we do the callback back to the user for an unseen content name (which
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
		_callback.handleContentName(_slice, name);
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
			updateCurrent();
			_updateNames.clear();
		}
	}
	
	/**
	 * We keep a running tree of what we already have in "X". Update it here to reflect what we
	 * got on the last round.
	 */
	protected void updateCurrent() {
		TreeSet<ContentName> neededNames = new TreeSet<ContentName>();
		int leafCount = 0;
		int tmpLeafCount = 0;
		boolean newHasNodes = false;
		boolean redo = false;
		SyncTreeEntry ste = getHead(_current);
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
					pop(_current);
					ste = getHead(_current);
				}
				if (null != ste) {
					snc = ste.getNode();
					if (null != snc) {
						sne = ste.getCurrentElement();
					}
					if (null == snc || null == sne) {
						Log.warning(Log.FAC_SYNC, "Missing node for {0} should not be missing", Component.printURI(ste.getHash()));
						return;
					}
				
					switch (sne.getType()) {
					case HASH:
						newHasNodes = true;
						SyncTreeEntry entry = _pbsm.getHash(sne.getData());
						if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
							Log.finest(Log.FAC_SYNC, "Update compare - moving to: {0}", entry == null ? null : Component.printURI(entry.getHash()));
						}
						if (null == entry)
							return;	// Shouldn't happen I don't think
						thisHashElement = ste.getCurrentElement();
						ste.incPos();
						if (redo) {
							snc = entry.getNode();
							for (SyncNodeElement tsne: snc.getRefs()) {
								neededNames.add(tsne.getName());
							}
						} else {
							thisHashStartName = entry.getNode().getMinName().getName();
							tmpLeafCount = entry.getNode().getLeafCount();
							push(entry, _current);
							ste = entry;
							ste.setPos(0);
						}
						break;
					case LEAF:
						int comp = sne.getName().compareTo(name);
						if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST) && _updateNames.size() > 0) {
							Log.finest(Log.FAC_SYNC, "Update compare: name from tree (expanded) is {0}, name  is {1}, comp is {2}", sne.getName(), name, comp);
						}
						if (ste.getPos() == 0 && !redo) {
							// If we are after everything in X, no need to compare to X
							int comp2 = snc.getMaxName().getName().compareTo(name);
							if (comp2 < 0) {
								pop(_current);
								ste = getHead(_current);
								if (Log.isLoggable(Log.FAC_SYNC, Level.FINEST)) {
									if (null != ste)
										Log.finest(Log.FAC_SYNC, "Shortcut in update - popping back to {0}, pos {1}", Component.printURI(ste.getHash()), ste.getPos());
									else
										Log.finest(Log.FAC_SYNC, "Shortcut in update - popping last node");
								}
								if (thisHashElement != null && thisHashStartName != null) {
									leafCount += snc.getLeafCount();
									nodeElements.put(thisHashStartName, thisHashElement);
								}
								continue;
							}
						}
						found = true;
						if (comp < 0 || redo) {
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
							ste = pop(_current);
						} else  {
							ste.incPos();
							if (ste.lastPos() && thisHashElement != null && thisHashStartName != null) {
								leafCount += tmpLeafCount;
								nodeElements.put(thisHashStartName, thisHashElement);
							}
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
					SyncNodeComposite tsnc = origSte.getNode(_decoder);
					if (null != tsnc) {
						for (SyncNodeElement tsne: tsnc.getRefs()) {
							neededNames.add(tsne.getName());
						}
					}
				}
				redo = true;
				neededNames.add(name);
			}
		}
		
		while (neededNames.size() > 0) {
			ContentName firstName = neededNames.first();
			newHead = SyncTreeEntry.newLeafNode(neededNames);
			_pbsm.putHashEntry(newHead);
			if (neededNames.size() > 0) {	// Need to split
				newHasNodes = true;
			}
			if (newHasNodes)
				nodeElements.put(firstName, new SyncNodeElement(newHead.getHash()));
		}
		if (redo && newHasNodes) {
			leafCount += neededNames.size();
			// OK its a little bogus to use newHead for what really should be a temp variable here,
			// but if we can't find a hash (which we actually always should) it works out right
			// with the rest of the code and if we find it, we reset newHead to the proper value.
			SyncNodeElement firstNodeElement = nodeElements.get(nodeElements.firstKey());
			newHead = _pbsm.getHash(firstNodeElement.getData());
			if (null != newHead) {
				SyncNodeComposite snc = newHead.getNode();
				if (null == snc) {
					Log.warning(Log.FAC_SYNC, "Couldn't get first node in update for {0} - shouldn't happen", 
							Component.printURI(newHead.getHash()));
					newHead = null;
				} else {
					SyncNodeElement minElement = snc.getMinName();
					SyncNodeElement lastNodeElement = nodeElements.get(nodeElements.lastKey());
					newHead = _pbsm.getHash(lastNodeElement.getData());
					if (null != newHead) {
						snc = newHead.getNode();
						if (null == snc) {
							Log.warning(Log.FAC_SYNC, "Couldn't get last node in update for {0} - shouldn't happen", 
									Component.printURI(newHead.getHash()));
							newHead = null;
						} else {
							// Have to make sure the refs are in correct order
							ArrayList<SyncNodeElement> refs = new ArrayList<SyncNodeElement>();
							for (ContentName tname : nodeElements.keySet()) {
								refs.add(nodeElements.get(tname));
							}
							SyncNodeElement maxElement = snc.getMaxName();
							snc = new SyncNodeComposite(refs, minElement, maxElement, leafCount);
							newHead = _pbsm.addHash(snc.getHash());
							newHead.setNode(snc);
							newHead.setCovered(true);
						}
					}
				}
			}
		}
		_current.clear();
		if (null != newHead) {
			push(newHead, _current);
			_currentRoot = newHead;
			if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
				Log.info(Log.FAC_SYNC, "Resetting current to created head: {0}", Component.printURI(newHead.getHash()));
		} else {
			origSte.setPos(0);
			push(origSte, _current);  // Should already be _currentRoot
		}
	}
	
	/**
	 * Separate thread for running comparisons. We run until we can't do anything
	 * anymore, then rely on "kickCompare" to restart the thread. It uses a state
	 * machine to figure out where it left off and where to start back in again.
	 */
	public void run() {
		_compareSemaphore.acquireUninterruptibly();
		boolean keepComparing = true;
		try {
			do {
				switch (getState()) {
				case INIT:		// Starting a new compare
					byte[] data = null;
					if (null != _startHash && _startHash.getHash().length != 0) {
						_currentRoot = _startHash;
						_startHash = null;
						nextRound();
					}
					do {
						data = getPendingContent();
						if (null != data) {
							SyncNodeComposite snc = new SyncNodeComposite();
							snc.decode(data);
							SyncTreeEntry ste = _pbsm.addHash(snc.getHash());
							ste.setNode(snc);
							if (null != _startHash) {  // has to be 0 length so start with "current"
								_currentRoot = ste;
								_startHash = null;
								nextRound();
							} else {	// No sense doing a compare with ourself!
								addPending(ste);
							}
						}
					} while (null != data);
					
					SyncTreeEntry ste = getPending();
					if (null != ste) {
						ste.setPos(0);
						push(ste, _next);
						if (null == _currentRoot)
							_currentRoot = ste;
						changeState(SyncCompareState.PRELOAD);
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
					// Fall through
				case PRELOAD:	// Need to load data for the compare
					if (null == getHead(_next)) {
						keepComparing = false;
						break;
					}
					if (doPreload(getHead(_next))) {
						keepComparing = false;
						break;
					}
					changeState(SyncCompareState.COMPARE);
					// Fall through
				case COMPARE:	// We are currently in the process of comparing
					doComparison();
					if (getState() == SyncCompareState.COMPARE) {
						keepComparing = false;
					}
					break;
				case DONE:	// Compare is done. Start over again if we have pending data
							// for another compare
					nextRound();
					synchronized (this) {
						if (_pendingEntries.size() > 0) {
							changeState(SyncCompareState.INIT);
							break;
						}
					}
					
				default:
					keepComparing = false;
					break;
				}
				synchronized (_timerLock) {
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
		} catch (Exception ex) {Log.logStackTrace(Log.FAC_SYNC, Level.WARNING, ex);} 	
		  catch (Error er) {Log.logStackTrace(Log.FAC_SYNC, Level.WARNING, er);} 
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
			SyncTreeEntry ste = _pbsm.addHash(hash);
			synchronized (this) {
				ste.setRawContent(data.content());
				ste.setPending(false);
				kickCompare();
			}
			return null;
		}
	}
}
