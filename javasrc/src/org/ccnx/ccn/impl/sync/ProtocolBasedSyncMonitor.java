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
	
	protected class SliceReferences {
		protected byte[] _sliceHash;
		protected SyncRootTree _nextRoot;
		protected SyncRootTree _currentRoot;
		protected Stack<SyncRootTree> _nextStack = new Stack<SyncRootTree>();
		protected Object _stateLock = new Object();
		protected SyncCompareState _state = SyncCompareState.INIT;
		protected SyncRootTree _nextHash = null;
		protected HashMap<byte[], SyncRootTree> _nextHashes = new HashMap<byte[], SyncRootTree>();
		
		protected SliceReferences(ContentName name, byte[] sliceHash) {
			_sliceHash = sliceHash;
			_nextRoot = new SyncRootTree(sliceHash, _decoder);
			_currentRoot = new SyncRootTree(sliceHash, _decoder);
			_nextHashes.put(sliceHash, _nextRoot);
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
		protected SyncRootTree getByHash(byte[] hash) {
			return _nextHashes.get(hash);
		}
		
		/**
		 * Must be synchronized by caller
		 */
		protected void addHash(byte[] hash, SyncRootTree srt) {
			_nextHashes.put(hash, srt);
		}
		
		protected SyncRootTree getNextHash() {
			return _nextHash;
		}
		
		protected void setCurrentHash(SyncRootTree srt) {
			_nextHash = srt;
		}
		
		protected void pushNext(SyncRootTree srt) {
			_nextStack.push(srt);
		}
		
		protected SyncRootTree popNext() {
			if (!_nextStack.isEmpty())
				return _nextStack.pop();
			return null;
		}
		
		protected SyncRootTree getNextHead() {
			if (! _nextStack.isEmpty())
				return _nextStack.lastElement();
			return null;
		}
		
		protected void nextRound() {
			synchronized (this) {
				_currentRoot = _nextRoot;
				_nextRoot = new SyncRootTree(_sliceHash, _decoder);
				_state = SyncCompareState.INIT;
			}
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
		_handle.expressInterest(interest, this);
		_handle.registerFilter(rootAdvise, this);
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
		if (Log.isLoggable(Log.FAC_SYNC, Level.FINE))
			Log.fine(Log.FAC_SYNC, "Saw new content from sync: {0}", name);
		ContentName topo = name.cut(Sync.SYNC_ROOT_ADVISE_MARKER.getBytes());
		byte[] hash = name.component(hashComponent + 1);
		SliceReferences sr = getReferencesBySliceHash(topo, hash);
		if (null != sr) {
			SyncRootTree srt = genericContentHandler(data, topo, sr, hash);
			if (null != srt) {
				sr.setCurrentHash(srt);
				sr.setState(SyncCompareState.INIT);
				kickCompare();
			}
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
	
	private SyncRootTree genericContentHandler(ContentObject data, ContentName topo, SliceReferences sr, byte[] hash) {
		SyncRootTree srt = null;
		srt = addHash(sr, hash);
		srt.setRawContent(data.content());
		srt.setPending(false);
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
	
	private SyncRootTree addHash(SliceReferences sr, byte[] hash) {
		synchronized (sr) {
			SyncRootTree entry = sr.getByHash(hash);
			if (null == entry) {
				entry = new SyncRootTree(hash, _decoder);
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
	private boolean doPreload(ContentName topo, SliceReferences sr, SyncRootTree srt) {
		SyncNodeComposite snc = srt.getNextNode();
		Boolean ret = false;
		if (null != snc) {
			for (SyncNodeElement sne : snc.getRefs()) {
				if (sne.getType() == SyncNodeType.HASH) {
					byte[] hash = sne.getData();
					Log.info("Preload requested something");
					ret = requestNode(topo, sr, hash);
				}
			}
		}
		return ret;
	}
	
	private boolean requestNode(ContentName topo, SliceReferences sr, byte[] hash) {
		boolean ret = false;
		SyncRootTree tsrt = addHash(sr, hash);
		if (tsrt.getNextNode() == null && !tsrt.getPending()) {
			tsrt.setPending(true);
			ret = getHash(topo, sr.getSliceHash(), hash);
			if (ret)
				sr.pushNext(tsrt);
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
		boolean sawAHash = false;
		SyncRootTree srt = sr.getNextHead();
		while (null != srt) {
			SyncNodeComposite snc = srt.getNextNode();
			if (null != snc) {
				for (SyncNodeComposite.SyncNodeElement sne : snc.getRefs()) {
					switch (sne.getType()) {
					case LEAF:
						ContentName name = sne.getName().parent();	// remove digest here
						HashMap<ConfigSlice, ArrayList<CCNSyncHandler>> tCallbacks = null;
						synchronized(callbacks) {
							// We can't call the callback with locks held so extract what we will call 
							// under the lock
							tCallbacks = new HashMap<ConfigSlice, ArrayList<CCNSyncHandler>>(callbacks.size());
							for (ConfigSlice cs : callbacks.keySet()) {
								tCallbacks.put(cs, callbacks.get(cs));
							}
						}
						for (ConfigSlice cs : tCallbacks.keySet()) {
							if (cs.prefix.isPrefixOf(name)) {
								for (CCNSyncHandler handler: callbacks.get(cs)) {
									handler.handleContentName(cs, name);
								}
							}
						}
						break;
					case HASH:
						sawAHash = true;
						byte[] hash = sne.getData();
						requestNode(topo, sr, hash);
						Log.info("We requested a node");
						sr.setState(SyncCompareState.PRELOAD);
						break;
					default:
						
						break;
					}
				}
			}
			sr.popNext();
			srt = sr.getNextHead();
		}
		if (sr.getNextHead()  == null && sr.getState()  == SyncCompareState.COMPARE || !sawAHash)
			sr.setState(SyncCompareState.DONE);
	}
	
	public void run() {
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
						SyncRootTree srt = sr.getNextHash();
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
						synchronized (_timerLock) {
							if (sr.getState() == SyncCompareState.DONE)
								_keepComparing = false;
							else
								break;
						}
						// Fall through
					case DONE:
						break;
					}			
				}
			}
			synchronized (_timerLock) {
				if (!_keepComparing)
					_needToCompare = true;
			}
		} while (_keepComparing);
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
			if (Log.isLoggable(Log.FAC_SYNC, Level.FINE))
				Log.fine(Log.FAC_SYNC, "Saw data from nodefind: hash: {0}", name);
			SliceReferences sr = getReferencesBySliceHash(topo, sliceHash);
			if (null != sr) {
				SyncRootTree srt = genericContentHandler(data, topo, sr, hash);
				if (null != srt) {
					sr.pushNext(srt);
					sr.setState(SyncCompareState.PRELOAD);
					kickCompare();
				}
			}
			return null;
		}
	}
}
