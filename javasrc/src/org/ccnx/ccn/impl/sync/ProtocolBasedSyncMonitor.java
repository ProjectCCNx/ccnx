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
	
	protected HashMap<ContentName, HashMap<ConfigSlice, SliceReferences>> _rootsByTopo 
				= new HashMap<ContentName, HashMap<ConfigSlice, SliceReferences>>();
	
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
	
	protected class SliceReferences {
		protected byte[] _sliceHash;
		protected SyncTreeEntry _nextRoot;
		protected SyncTreeEntry _currentRoot;
		protected Stack<SyncTreeEntry> _currentStack = new Stack<SyncTreeEntry>();
		protected Stack<SyncTreeEntry> _nextStack = new Stack<SyncTreeEntry>();
		protected Object _stateLock = new Object();
		protected SyncCompareState _state = SyncCompareState.DONE;
		protected SyncTreeEntry _lastHash = null;
		protected SyncTreeEntry _nextHash = null;
		protected HashMap<HashEntry, SyncTreeEntry> _hashes = new HashMap<HashEntry, SyncTreeEntry>();
		
		protected SliceReferences(ContentName name, byte[] sliceHash) {
			_sliceHash = sliceHash;
			_nextRoot = new SyncTreeEntry(sliceHash, _decoder);
			_currentRoot = new SyncTreeEntry(sliceHash, _decoder);
			_hashes.put(new HashEntry(sliceHash), _nextRoot);
		}
		
		protected byte[] getSliceHash() {
			return _sliceHash;
		}
		
		protected void setState(SyncCompareState scs) {
			synchronized (_stateLock) {
				_state = scs;
			}
		}
		
		protected SyncCompareState getState() {
			synchronized (_stateLock) {
				return _state;
			}
		}
		
		/**
		 * Must be synchronized by caller
		 */
		protected SyncTreeEntry getByHash(HashEntry hash) {
			return _hashes.get(hash);
		}
		
		/**
		 * Must be synchronized by caller
		 */
		protected void addHash(byte[] hash, SyncTreeEntry srt) {
			_hashes.put(new HashEntry(hash), srt);
		}
		
		protected SyncTreeEntry getNextHash() {
			return _nextHash;
		}
		
		protected void addNextHash(SyncTreeEntry srt) {
			_nextHash = srt;
		}
		
		protected void pushNext(SyncTreeEntry srt) {
Log.info("pushing entry with hash: {0}", Component.printURI(srt._hash));
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
		
		/**
		 * Needs outside synchronization
		 */
		protected void nextRound(SyncTreeEntry srt) {
Log.info("Starting next round with hash: {0}", Component.printURI(srt.getHash()));
			_currentRoot = _nextRoot;
			_nextHash = srt;
			_nextRoot = new SyncTreeEntry(_sliceHash, _decoder);
			_state = SyncCompareState.INIT;
			if (null != _lastHash) {
				for (SyncTreeEntry tsrt : _hashes.values()) {
					tsrt.setCurrent(true);
				}
				pushCurrent(_lastHash);
			}
			_lastHash = _nextHash;
		}
	}
	
	public ProtocolBasedSyncMonitor(CCNHandle handle) {
		_handle = handle;
		_decoder = new BinaryXMLDecoder();
		_decoder.setInitialBufferSize(DECODER_SIZE);
	}

	public void registerCallback(CCNSyncHandler syncHandler, ConfigSlice slice) throws IOException {
		synchronized (callbacks) {
			registerCallbackInternal(syncHandler, slice);
		}
		synchronized (_rootsByTopo) {
			HashMap<ConfigSlice, SliceReferences> topoMap = _rootsByTopo.get(slice.topo);
			if (null == topoMap) {
				topoMap = new HashMap<ConfigSlice, SliceReferences>();
			}
			byte[] hash = slice.getHash();
			if (null == topoMap.get(slice)) {
				SliceReferences sr = new SliceReferences(slice.topo, hash);
				topoMap.put(slice, sr);
				_rootsByTopo.put(slice.topo, topoMap);
				addHash(sr, hash);
			}
		}
		ContentName rootAdvise = new ContentName(slice.topo, Sync.SYNC_ROOT_ADVISE_MARKER, slice.getHash());
		Interest interest = new Interest(rootAdvise);
		interest.scope(1);
		_handle.registerFilter(rootAdvise, this);
Log.info("Output root advise for {0}", rootAdvise);
		_handle.expressInterest(interest, this);
	}

	public void removeCallback(CCNSyncHandler syncHandler, ConfigSlice slice) {
		synchronized (callbacks) {
			removeCallbackInternal(syncHandler, slice);
		}
		synchronized (_rootsByTopo) {
			HashMap<ConfigSlice, SliceReferences> topoMap = _rootsByTopo.get(slice.topo);
			if (topoMap != null) {
				topoMap.remove(slice);
				if (topoMap.isEmpty()) {
					_rootsByTopo.remove(slice.topo);
				}
			}
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
		ContentName topo = name.cut(Sync.SYNC_ROOT_ADVISE_MARKER.getBytes());
		byte[] hash = name.component(hashComponent + 1);
		SliceReferences sr = getReferencesBySliceHash(topo, hash);
		if (null != sr) {
			SyncTreeEntry srt = null;
			synchronized (sr) {
				srt = genericContentHandler(data, topo, sr, hash);
				if (null != srt) {
					if (sr.getState() == SyncCompareState.DONE) {
						sr.nextRound(srt);
					}
				}
			}
			if (null != srt)
				kickCompare();	// Don't want to do this inside lock
		}
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
	
	/**
	 * Caller should synchronize
	 */
	private SyncTreeEntry genericContentHandler(ContentObject data, ContentName topo, SliceReferences sr, byte[] hash) {
		SyncTreeEntry srt = null;
		synchronized (sr) {
			srt = addHash(sr, hash);
			srt.setRawContent(data.content());
			srt.setPending(false);
		}
		return srt;
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
	
	private SyncTreeEntry addHash(SliceReferences sr, byte[] hash) {
		synchronized (sr) {
			SyncTreeEntry entry = sr.getByHash(new HashEntry(hash));
			if (null == entry) {
				entry = new SyncTreeEntry(hash, _decoder);
				sr.addHash(hash, entry);
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
	private boolean doPreload(ContentName topo, SliceReferences sr, SyncTreeEntry srt) {
		Boolean ret = false;
		synchronized (sr) {
			SyncNodeComposite snc = srt.getNode();
			if (null != snc) {
				for (SyncNodeElement sne : snc.getRefs()) {
					if (sne.getType() == SyncNodeType.HASH) {
						byte[] hash = sne.getData();
						ret = requestNode(topo, sr, hash);
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
	private boolean requestNode(ContentName topo, SliceReferences sr, byte[] hash) {
		boolean ret = false;
		synchronized (sr) {
			SyncTreeEntry tsrt = addHash(sr, hash);
			if (tsrt.getNode() == null && !tsrt.getPending()) {
				tsrt.setPending(true);
				Log.info("requesting node for hash: {0}", Component.printURI(hash));
				ret = getHash(topo, sr.getSliceHash(), hash);
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
	
	/**
	 * We could add a second HashMap to make this more efficient, but my belief is that its unlikely that
	 * we will normally have to deal with so many slices in the same topo here that that is really necessary.
	 * If that is not the case, this should be improved.
	 */
	private SliceReferences getReferencesBySliceHash(ContentName topo, byte[] hash) {
		synchronized (_rootsByTopo) {
			HashMap<ConfigSlice, SliceReferences> topoMap = _rootsByTopo.get(topo);
			if (null != topoMap) {
				for (ConfigSlice cs : topoMap.keySet()) {
					SliceReferences sr = topoMap.get(cs);
					if (Arrays.equals(hash, sr.getSliceHash()))
						return sr;
				}
			}
		}
		return null;
	}
	
	private void doComparison(ContentName topo, SliceReferences sr) {
Log.info("started compare");
		SyncTreeEntry srt = null;
		synchronized (sr) {
			srt = sr.getNextHead();
		}
		while (null != srt) {
			boolean lastPos = false;
			SyncNodeComposite.SyncNodeElement sne = null;
			synchronized (sr) {
				SyncNodeComposite snc = srt.getNode();
				if (null != snc) {
					sne = srt.getCurrentElement();
					if (null == sne)
						lastPos = true;
				}
			}
			if (!lastPos) {
				if (null != sne) {
Log.info("Comparing hash for {0}, type is {1}", Component.printURI(srt.getHash()), sne.getType());
					switch (sne.getType()) {
					case LEAF:
						ContentName name = sne.getName().parent();	// remove digest here
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
						for (ConfigSlice cs : tCallbacks.keySet()) {
							if (cs.prefix.isPrefixOf(name)) {
								for (CCNSyncHandler handler: tCallbacks.get(cs)) {
									handler.handleContentName(cs, name);
								}
							}
						}
						break;
					case HASH:
						if (requestNode(topo, sr, sne.getData())) {
	Log.info("requested from compare");
							synchronized (sr) {
								sr.setState(SyncCompareState.PRELOAD);
							}
						}
						break;
					default:
						break;
					}
				} else {
					Log.info("No data for {0}", Component.printURI(srt.getHash()));
					requestNode(topo, sr, srt.getHash());
					return;
				}
			}
			synchronized (sr) {
				srt.incPos();
				while (null != srt && srt.lastPos()) {
					sr.popNext();
					srt = sr.getNextHead();
				}
			}
		}
		synchronized (sr) {
			if (sr.getState() == SyncCompareState.COMPARE)
				sr.setState(SyncCompareState.DONE);
		}
Log.info("Exited 1 and status was: {0}", sr.getState());
	}
	
	public void run() {
		try {
			ArrayList<ContentName> topos = new ArrayList<ContentName>();		
			do {
				synchronized (_timerLock) {
					_needToCompare = false;
				}
				
				topos.clear();
				synchronized (_rootsByTopo) {
					for (ContentName topo : _rootsByTopo.keySet()) {
						topos.add(topo);
					}
				}
				
				for (ContentName topo : topos) {
					ArrayList<SliceReferences> slicesForTopo = new ArrayList<SliceReferences>();
					synchronized (_rootsByTopo) {
						HashMap<ConfigSlice, SliceReferences> hm = _rootsByTopo.get(topo);
						if (null != hm) {
							for (ConfigSlice cs : hm.keySet()) {
								slicesForTopo.add(hm.get(cs));
							}
						}
					}
					for (SliceReferences sr : slicesForTopo) {
						switch (sr.getState()) {
						case BUSY:
							break;
						case INIT:
							SyncTreeEntry srt = sr.getNextHash();
							if (null == srt)
								break;
							sr.pushNext(srt);
							sr.setState(SyncCompareState.PRELOAD);
							// Fall through
						case PRELOAD:
							synchronized (_timerLock) {
								if (null == sr.getNextHead()) {
									_keepComparing = false;
									break;
								}
							}
							if (doPreload(topo, sr, sr.getNextHead())) {
								break;
							}
							sr.setState(SyncCompareState.COMPARE);
							// Fall through
						case COMPARE:
							doComparison(topo, sr);
							// Fall through
						case DONE:
						default:
							synchronized (_timerLock) {
								_keepComparing = false;
							}
							break;
						}			
					}
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
			ContentName topo = name.cut(Sync.SYNC_NODE_FETCH_MARKER.getBytes());
			byte[] sliceHash = name.component(hashComponent + 1);
			byte[] hash = name.component(hashComponent + 2);
			if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
				Log.info(Log.FAC_SYNC, "Saw data from nodefind: hash: {0}", name);
			SliceReferences sr = getReferencesBySliceHash(topo, sliceHash);
			if (null != sr) {
				synchronized (sr) {
					SyncTreeEntry srt = genericContentHandler(data, topo, sr, hash);
					if (null != srt) {
						sr.pushNext(srt);
						sr.setState(SyncCompareState.PRELOAD);
						kickCompare();
					}
				}
			}
			return null;
		}
	}
}
