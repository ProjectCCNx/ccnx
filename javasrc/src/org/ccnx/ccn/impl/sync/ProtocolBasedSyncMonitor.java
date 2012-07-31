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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Stack;
import java.util.Timer;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.CCNSyncHandler;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.encoding.BinaryXMLDecoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ConfigSlice;
import org.ccnx.ccn.io.content.SyncNodeComposite;
import org.ccnx.ccn.io.content.SyncNodeComposite.SyncNodeElement;
import org.ccnx.ccn.io.content.SyncNodeComposite.SyncNodeType;
import org.ccnx.ccn.profiles.sync.Sync;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

/**
 * Note: We purposely don't decode SyncNodeComposites in handlers since they are big and slow and we risk
 * timing out the handler by doing so. 
 *
 */
public class ProtocolBasedSyncMonitor extends SyncMonitor implements CCNContentHandler, CCNInterestHandler, Runnable {
	public static final int DECODER_SIZE = 756;
	public enum SyncCompareState {INIT, PRELOAD, BUSY, COMPARE, DONE};

	public final int COMPARE_INTERVAL = 100; // ms
	protected CCNHandle _handle;
	protected BinaryXMLDecoder _decoder;
	protected Object _timerLock = new Object();
	protected boolean _needToCompare = true;
	protected boolean _keepComparing = false;
	protected Timer _timer = new Timer(false);
	protected NodeFetchHandler _nfh = new NodeFetchHandler();
	protected Object _compareLock = new Object();
	
	protected ContentName _topo;
	protected byte[] _sliceHash;
	
	protected SyncTreeEntry _nextRoot;
	protected SyncTreeEntry _currentRoot;
	protected Stack<SyncTreeEntry> _currentStack = new Stack<SyncTreeEntry>();
	protected Stack<SyncTreeEntry> _nextStack = new Stack<SyncTreeEntry>();
	protected Object _stateLock = new Object();
	protected SyncCompareState _state = SyncCompareState.DONE;
	protected HashMap<HashEntry, SyncTreeEntry> _hashes = new HashMap<HashEntry, SyncTreeEntry>();
	protected ArrayList<SyncTreeEntry> _pendingEntries = new ArrayList<SyncTreeEntry>();
	
	protected class HashEntry {
		protected byte[] _hash;
		
		protected HashEntry(byte[] hash) {
			_hash = hash;
		}
		public boolean equals(Object hash) {
			return Arrays.equals(((HashEntry)hash)._hash, _hash);
		}
		public int hashCode() {
			return Arrays.hashCode(_hash);
		}
	}
	
	public ProtocolBasedSyncMonitor(CCNHandle handle) {
		_handle = handle;
		_decoder = new BinaryXMLDecoder();
		_decoder.setInitialBufferSize(DECODER_SIZE);
	}

	public void registerCallback(CCNSyncHandler syncHandler, ConfigSlice slice) throws IOException {
		_topo = slice.topo;
		_sliceHash = slice.getHash();
		_nextRoot = new SyncTreeEntry(_sliceHash, _decoder);
		_currentRoot = new SyncTreeEntry(_sliceHash, _decoder);
		_hashes.put(new HashEntry(_sliceHash), _nextRoot);
		synchronized (callbacks) {
			registerCallbackInternal(syncHandler, slice);
		}
		ContentName rootAdvise = new ContentName(slice.topo, Sync.SYNC_ROOT_ADVISE_MARKER, slice.getHash());
		Interest interest = new Interest(rootAdvise);
		interest.scope(1);
		_handle.registerFilter(rootAdvise, this);
		_handle.expressInterest(interest, this);
	}

	public void removeCallback(CCNSyncHandler syncHandler, ConfigSlice slice) {
		synchronized (callbacks) {
			removeCallbackInternal(syncHandler, slice);
		}
	}

	public Interest handleContent(ContentObject data, Interest interest) {
		ContentName name = data.name();
		int hashComponent = name.containsWhere(Sync.SYNC_ROOT_ADVISE_MARKER);
		if (hashComponent < 0) {
			if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
				Log.info(Log.FAC_SYNC, "Received incorrect content in sync: {0}", name);
			return null;
		}
		Log.info(Log.FAC_SYNC, "Saw new content from sync: {0}", name);
		boolean newRound = false;
		synchronized (this) {
			SyncTreeEntry ste = new SyncTreeEntry(_decoder);
			ste.setRawContent(data.content());
			_pendingEntries.add(ste);
			if (_state == SyncCompareState.DONE) {
				_state = SyncCompareState.INIT;
				newRound = true;
			}
		}
		if (newRound)
			kickCompare();	// Don't want to do this inside lock
		return null;
	}
	
	public boolean handleInterest(Interest interest) {
		Interest newInterest = new Interest(interest.name());
		newInterest.scope(1);
		try {
			_handle.expressInterest(newInterest, this);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return true;
	}
	
	private void kickCompare() {
		synchronized (_timerLock) {
			_keepComparing = true;
			if (_needToCompare) {
				SystemConfiguration._systemTimers.schedule(this, COMPARE_INTERVAL, TimeUnit.MILLISECONDS);
				_needToCompare = false;
			}
		}
	}
	
	private SyncTreeEntry addHash(byte[] hash) {
		synchronized (this) {
			SyncTreeEntry entry = _hashes.get(new HashEntry(hash));
			if (null == entry) {
				entry = new SyncTreeEntry(hash, _decoder);
				_hashes.put(new HashEntry(hash), entry);
			}
			return entry;
		}
	}
	
	/**
	 * 
	 * @param sr
	 * @param topo
	 * @param srt
	 * @return false if no preloads requested
	 */
	private boolean doPreload(ContentName topo, SyncTreeEntry srt) {
		Boolean ret = false;
		synchronized (this) {
			SyncNodeComposite snc = srt.getNodeX();
			if (null != snc) {
				for (SyncNodeElement sne : snc.getRefs()) {
					if (sne.getType() == SyncNodeType.HASH) {
						byte[] hash = sne.getData();
						ret = requestNode(topo, hash);
					}
				}
			}
		}
		return ret;
	}
	
	/**
	 * 
	 * @param topo
	 * @param sr
	 * @param hash
	 * @return true if request made
	 */
	private boolean requestNode(ContentName topo, byte[] hash) {
		boolean ret = false;
		if (null != topo) {
			synchronized (this) {
				SyncTreeEntry tsrt = addHash(hash);
				if (tsrt.getNodeX() == null && !tsrt.getPending()) {
					tsrt.setPending(true);
					Log.info("requesting node for hash: {0}", Component.printURI(hash));
					ret = getHash(topo, _sliceHash, hash);
				}
			}
		}
		return ret;
	}
	
	private boolean getHash(ContentName topo, byte[] sliceHash, byte[] hash) {
		boolean ret = false;
		Interest interest = new Interest(new ContentName(topo, Sync.SYNC_NODE_FETCH_MARKER, sliceHash, hash));
		interest.scope(1);
		if (Log.isLoggable(Log.FAC_SYNC, Level.FINE))
			Log.fine(Log.FAC_TEST, "Requesting node for hash: {0}", interest.name());
		try {
			_handle.expressInterest(interest, _nfh);
			ret = true;
		} catch (IOException e) {
			Log.warning(Log.FAC_SYNC, "Preload failed: {0}", e.getMessage());
		}
		return ret;
	}
	
	private void doComparison(ContentName topo) {
Log.info("started compare");
		HashMap<ConfigSlice, ArrayList<CCNSyncHandler>> tCallbacks = null;
		
		synchronized(callbacks) {
			// We can't call the callback with locks held so extract what we will call 
			// under the lock
			tCallbacks = new HashMap<ConfigSlice, ArrayList<CCNSyncHandler>>(callbacks.size());
			for (ConfigSlice cs : callbacks.keySet()) {
				ArrayList<CCNSyncHandler> tal = new ArrayList<CCNSyncHandler>();
				for (CCNSyncHandler csh : callbacks.get(cs)) {
					tal.add(csh);
				}
				tCallbacks.put(cs, tal);
			}
		}
		doComparisonWithCallbacks(topo, tCallbacks);
	}
		
	private void doComparisonWithCallbacks(ContentName topo, HashMap<ConfigSlice, ArrayList<CCNSyncHandler>> callbacks) {
		SyncTreeEntry srtY = null;
		SyncTreeEntry srtX = null;
		
		synchronized (this) {
			srtY = getNextHead();
			srtX = getCurrentHead();
		}
		while (null != srtY) {
			boolean lastPos = false;
			SyncNodeComposite.SyncNodeElement sneX = null;
			SyncNodeComposite.SyncNodeElement sneY = null;
			synchronized (this) {
				SyncNodeComposite snc = srtY.getNodeX();
				if (null != snc) {
					sneY = srtY.getCurrentElement();
					if (null == sneY)
						lastPos = true;
				}
				if (null != srtX) {
					snc = srtX.getNodeX();
					if (null != snc) {
						sneX = srtX.getCurrentElement();
					}
				}
			}
			if (null == sneX && null != sneY && !lastPos) {
Log.info("Comparing hash for {0}, type is {1}", Component.printURI(srtY.getHash()), sneY.getType());
				switch (sneY.getType()) {
				case LEAF:
					doCallbacks(callbacks, sneY);
					break;
				case HASH:
					byte[] hash = sneY.getData();
					SyncTreeEntry entry = _hashes.get(new HashEntry(hash));
					if (null == entry) {
						if (requestNode(topo, sneY.getData())) {
Log.info("requested from compare");
							synchronized (this) {
								_state = SyncCompareState.PRELOAD;
							}
						}
					} else {
						pushNext(entry);
					}
					break;
				default:
					break;
				}
				synchronized (this) {
					srtY.incPos();
					while (null != srtY && srtY.lastPos()) {
						popNext();
						srtY = getNextHead();
					}
				}
			} else if (null == sneY  && !lastPos) {
				Log.info("No data for {0}, pos is {1}", Component.printURI(srtY.getHash()), srtY.getPos());
				requestNode(topo, srtY.getHash());
				return;
			} else if (null != sneX) {
				if (sneX.getType() == SyncNodeType.LEAF && sneY.getType() == SyncNodeType.LEAF) {
					int comp = sneX.getName().compareTo(sneY.getName());
Log.info("name 1 is {0}, name 2 is {1}, comp is {2}", sneX.getName(), sneY.getName(), comp);
					if (comp == 0) {
						srtX.incPos();
						srtY.incPos();
					} else if (comp > 0) {
						srtX.incPos();
					} else {
						doCallbacks(callbacks, sneY);
						srtY.incPos();
					}
				}
			} else {
				synchronized (this) {
					while (null != srtY && srtY.lastPos()) {
						popNext();
						getNextHead();
					}
				}
			}
		}
		synchronized (this) {
			if (_state == SyncCompareState.COMPARE)
				_state = SyncCompareState.DONE;
		}
Log.info("Exited 1 and status was: {0}", _state);
	}
	
	private void doCallbacks(HashMap<ConfigSlice, ArrayList<CCNSyncHandler>> callbacks, SyncNodeComposite.SyncNodeElement sne) {
		ContentName name = sne.getName().parent();	// remove digest here
		for (ConfigSlice cs : callbacks.keySet()) {
			if (cs.prefix.isPrefixOf(name)) {
				for (CCNSyncHandler handler: callbacks.get(cs)) {
					handler.handleContentName(cs, name);
				}
			}
		}
	}
	
	protected void pushNext(SyncTreeEntry srt) {
		_nextStack.push(srt);
	}
		
	protected SyncTreeEntry popNext() {
		if (!_nextStack.isEmpty()) {
			SyncTreeEntry srt =  _nextStack.pop();
Log.info("popping entry with hash: {0}", Component.printURI(srt._hash));
		return srt;
		}
	return null;
	}
		
	protected SyncTreeEntry getNextHead() {
		if (! _nextStack.isEmpty())
			return _nextStack.lastElement();
		return null;
	}
		
	protected void pushCurrent(SyncTreeEntry srt) {
		_currentStack.push(srt);
	}
		
	protected SyncTreeEntry popCurrent() {
		if (!_currentStack.isEmpty())
			return _currentStack.pop();
		return null;
	}
		
	protected SyncTreeEntry getCurrentHead() {
		if (! _currentStack.isEmpty())
			return _currentStack.lastElement();
		return null;
	}
		
		
	protected void nextRound() {
		_currentRoot = _nextRoot;
		for (SyncTreeEntry tsrt : _hashes.values()) {
			tsrt.setCurrent(true);
		}
	}
	
	public void run() {
		try {
			do {
				synchronized (_timerLock) {
					_needToCompare = false;
				}
				switch (_state) {
				case BUSY:
					break;
				case INIT:
					synchronized (this) {
						if (_pendingEntries.size() == 0) {
							_state = SyncCompareState.DONE;
							break;
						}
						nextRound();
						for (SyncTreeEntry ste : _pendingEntries) {
							SyncNodeComposite snc = ste.getNodeX();
							if (null != snc) {
								_hashes.put(new HashEntry(ste.setHash()), ste);
								pushNext(ste);
							}
						}
						_pendingEntries.clear();
						_state = SyncCompareState.PRELOAD;
					}
					// Fall through
				case PRELOAD:
					synchronized (_timerLock) {
						if (null == getNextHead()) {
							_keepComparing = false;
							break;
						}
					}
					if (doPreload(_topo, getNextHead())) {
						break;
					}
					_state = SyncCompareState.COMPARE;
					// Fall through
				case COMPARE:
					doComparison(_topo);
					// Fall through
				case DONE:
					synchronized (this) {
						if (_pendingEntries.size() > 0) {
							_state = SyncCompareState.INIT;
							break;
						}
					}
				default:
					synchronized (_timerLock) {
						_keepComparing = false;
					}
					break;
				}				
				synchronized (_timerLock) {
					if (!_keepComparing)
						_needToCompare = true;
				}
			} while (_keepComparing);
		} catch (Exception ex) {Log.logStackTrace(Log.FAC_SYNC, Level.WARNING, ex);} 	
		  catch (Error er) {Log.logStackTrace(Log.FAC_SYNC, Level.WARNING, er);} 
	}
	
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
			if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
				Log.info(Log.FAC_SYNC, "Saw data from nodefind: hash: {0}", name);
			synchronized (this) {
				SyncTreeEntry srt = null;
				srt = addHash(hash);
				srt.setRawContent(data.content());
				srt.setPending(false);
				if (null != srt) {
					pushNext(srt);
					_state = SyncCompareState.PRELOAD;
					kickCompare();
				}
			}
			return null;
		}
	}
}
