/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeMap;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.InterestTable.Entry;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


/**
 * Implements rudimentary data buffering by matching content objects
 * with interests before actually putting them out to ccnd.
 * 
 * Holds content objects until a matching interest is seen and holds
 * interests and matches immediately if a content object matching a
 * held interest is put.
 * 
 * Implements a highwater mark in the holding buffer. If the buffer size
 * reaches the highwater mark, a put will block until there is more
 * room in the buffer. Currently this is only per "flow controller". There
 * is nothing to stop multiple streams writing to the repo for instance to
 * independently all fill their buffers and cause a lot of memory to be used.
 * 
 * Also implements a highwater mark for held interests.
 * 
 * The buffer emptying policy in "afterPutAction" can be overridden by 
 * subclasses to implement a different way of draining the buffer. This is used 
 * by the repo client to allow objects to remain in the buffer until they are 
 * acked.
 * 
 * @author rasmusse
 *
 */

public class CCNFlowControl implements CCNFilterListener {
	
	public enum Shape {STREAM};
	
	protected CCNHandle _handle = null;
	
	// Temporarily default to very high timeout so that puts have a good
	// chance of going through.  We actually may want to keep this.
	protected static final int MAX_TIMEOUT = 10000;
	
	// Designed to allow a CCNOutputStream to flush its current output once without
	// causing the highwater blocking to be triggered
	protected static final int HIGHWATER_DEFAULT = 128 + 1;
	
	protected static final int INTEREST_HIGHWATER_DEFAULT = 40;
	protected int _timeout = MAX_TIMEOUT;
	protected int _highwater = HIGHWATER_DEFAULT;
	
	// Value used to determine whether the buffer is draining in waitForPutDrain
	protected long _nOut = 0;
	
	// Unmatched interests are purged from our table if they have remained there longer than this
	protected static final int PURGE = 2000;
	
	protected TreeMap<ContentName, ContentObject> _holdingArea = new TreeMap<ContentName, ContentObject>();
	protected InterestTable<UnmatchedInterest> _unmatchedInterests = new InterestTable<UnmatchedInterest>();
	
	// The namespaces served by this flow controller
	protected HashSet<ContentName> _filteredNames = new HashSet<ContentName>();

	private class UnmatchedInterest {
		long timestamp = new Date().getTime();
	}
	
	private boolean _flowControlEnabled = true;
	
	/**
	 * Enabled flow control constructor
<<<<<<< HEAD
	 * @param name an intial namespace to serve
	 * @param library may be null in which case a new CCNHandle will be created
=======
	 * @param name
	 * @param handle
<<<<<<< HEAD
>>>>>>> Change variables named "library" to "handle"; otherwise very confusing.
=======
>>>>>>> 8e225fb1a02d205dfb6059197744c38f33800b4a
	 */
	public CCNFlowControl(ContentName name, CCNHandle handle) throws IOException {
		this(handle);
		if (name != null) {
			Log.finest("adding namespace: " + name);
			// don't call full addNameSpace, in order to allow subclasses to 
			// override. just do minimal part
			_filteredNames.add(name);
			_handle.registerFilter(name, this);
		}
		_unmatchedInterests.setHighWater(INTEREST_HIGHWATER_DEFAULT);
	}
	
	public CCNFlowControl(String name, CCNHandle handle) 
				throws MalformedContentNameStringException, IOException {
		this(ContentName.fromNative(name), handle);
	}
	
	public CCNFlowControl(CCNHandle handle) throws IOException {
		if (null == handle) {
			// Could make this create a handle.
			try {
				handle = CCNHandle.open();
			} catch (ConfigurationException e) {
				Log.info("Got ConfigurationException attempting to create a handle. Rethrowing it as an IOException. Message: " + e.getMessage());
				throw new IOException("ConfigurationException creating a handle: " + e.getMessage());
			}
		}
		_handle = handle;
		_unmatchedInterests.setHighWater(INTEREST_HIGHWATER_DEFAULT);
	}
	
	/**
	 * Add a new namespace to the controller
	 * @param name
	 */
	public void addNameSpace(ContentName name) {
		if (!_flowControlEnabled)
			return;
		Iterator<ContentName> it = _filteredNames.iterator();
		while (it.hasNext()) {
			ContentName filteredName = it.next();
			if (filteredName.isPrefixOf(name)) {
				Log.info("addNameSpace: not adding name: " + name + " already monitoring prefix: " + filteredName);
				return;		// Already part of filter
			}
			if (name.isPrefixOf(filteredName)) {
				_handle.unregisterFilter(filteredName, this);
				it.remove();
			}
		}
		_filteredNames.add(name);
		_handle.registerFilter(name, this);
		Log.info("Flow controller addNameSpace: added namespace: " + name);
	}
	
	public void addNameSpace(String name) throws MalformedContentNameStringException, IOException {
		addNameSpace(ContentName.fromNative(name));
	}
	
	/**
	 * This is used by the RepoFlowController to indicate that it should start a write for this name
	 * @param name
	 * @param shape currently unused and may be deprecated in the future. Can only be Shape.STREAM
	 * @throws MalformedContentNameStringException if name is malformed
	 * @throws IOException used by subclasses
	 */
	public void startWrite(String name, Shape shape) throws MalformedContentNameStringException, IOException {
		startWrite(ContentName.fromNative(name), shape);
	}
	public void startWrite(ContentName name, Shape shape) throws IOException {}
	
	/**
	 * For now we don't have any way to remove a partial namespace from
	 * buffering (would we want to do that?) so we only allow
	 * removal of a namespace if it actually matches something that was
	 * registered
	 * 
	 * @param name
	 */
	public void removeNameSpace(ContentName name) {
		removeNameSpace(name, false);
	}
	
	private void removeNameSpace(ContentName name, boolean all) {
		Iterator<ContentName> it = _filteredNames.iterator();
		while (it.hasNext()) {
			ContentName filteredName = it.next();
			if (all || filteredName.equals(name)) {
				_handle.unregisterFilter(filteredName, this);
				it.remove();
				Log.finest("removing namespace: " + name);
				break;
			}
		}
	}
	
	/**
	 * Decide if this flow controller serves the child namespace
	 * @param childName
	 * @return The actual namespace the flow controller is using if it does serve the child
	 * 		   namespace.  null otherwise.
	 */
	public ContentName getNameSpace(ContentName childName) {
		ContentName prefix = null;
		for (ContentName nameSpace : _filteredNames) {
			if (nameSpace.isPrefixOf(childName)) {
				// is this the only one?
				if (null == prefix) {
					prefix = nameSpace;
				} else if (nameSpace.count() > prefix.count()) {
					prefix = nameSpace;
				}
			}
		}
		return prefix;
	}
	
	/**
	 * Add content objects to this flow controller. They won't be put to ccnd now unless
	 * a currently waiting interest matches them.
	 * @param cos
	 * @throws IOException if the put fails
	 */
	public void put(ArrayList<ContentObject> cos) throws IOException {
		for (ContentObject co : cos) {
			put(co);
		}
	}
	
	/**
	 * Add content objects to this flow controller. They won't be put to ccnd now unless
	 * a currently waiting interest matches them.
	 * @param cos
	 * @throws IOException if the put fails
	 */
	public void put(ContentObject [] cos) throws IOException {
		for (ContentObject co : cos) {
			put(co);
		}
	}

	/**
	 * Add namespace and content at the same time
	 * @param co
	 * @throws IOException if the put fails
	 */
	public void put(ContentName name, ArrayList<ContentObject> cos) throws IOException {
		addNameSpace(name);
		put(cos);
	}
	
	public ContentObject put(ContentName name, ContentObject co) throws IOException {
		addNameSpace(name);
		return put(co);
	}
	
	/**
	 * Add content object to this flow controller. They won't be put to ccnd now unless
	 * a currently waiting interest matches them.
	 * @param cos
	 * @throws IOException if the put fails
	 */
	public ContentObject put(ContentObject co) throws IOException {
		if (_flowControlEnabled) {
			boolean found = false;
			for (ContentName name : _filteredNames) {
				if (name.isPrefixOf(co.name())) {
					found = true;
					break;
				}
			}
			if (!found)
				throw new IOException("Flow control: co name \"" + co.name() 
					+ "\" is not in the flow control namespace");
		}
		return waitForMatch(co);
	}
	
	/**
	 * Hold content object in buffer until a matching interest has been received.
	 * @param co
	 * @return
	 * @throws IOException
	 */
	private ContentObject waitForMatch(ContentObject co) throws IOException {
		if (_flowControlEnabled) {
			synchronized (_holdingArea) {
				Entry<UnmatchedInterest> match = null;
				Log.finest("Holding " + co.name());
				_holdingArea.put(co.name(), co);
				match = _unmatchedInterests.removeMatch(co);
				if (match != null) {
					Log.finest("Found pending matching interest for " + co.name() + ", putting to network.");
					_handle.put(co);
					afterPutAction(co);
				}
				if (_holdingArea.size() >= _highwater) {
					boolean interrupted;
					long ourTime = new Date().getTime();
					Entry<UnmatchedInterest> removeIt;
					do {
						removeIt = null;
						for (Entry<UnmatchedInterest> uie : _unmatchedInterests.values()) {
							if ((ourTime - uie.value().timestamp) > PURGE) {
								removeIt = uie;
								break;
							}
						}
						if (removeIt != null)
							_unmatchedInterests.remove(removeIt.interest(), removeIt.value());
					} while (removeIt != null);
					do {
						interrupted = false;
						try {
							Log.finest("Waiting for drain");
							_holdingArea.wait(_timeout);
						if (_holdingArea.size() >= _highwater)
							throw new IOException("Flow control buffer full and not draining");
						} catch (InterruptedException e) {
							interrupted = true;
						}
					} while (interrupted);
				}
			}
		} else
			_handle.put(co);
		return co;
	}
	
	/**
	 * Match incoming interests with data in the buffer. If the interest doesn't match it is
	 * buffered awaiting potential later incoming data which may match it.
	 */
	public int handleInterests(ArrayList<Interest> interests) {
		synchronized (_holdingArea) {
			for (Interest interest : interests) {
				Log.fine("Flow controller: got interest: " + interest);
				ContentObject co = getBestMatch(interest);
				if (co != null) {
					Log.finest("Found content " + co.name() + " matching interest: " + interest);
					try {
						_handle.put(co);
						afterPutAction(co);
					} catch (IOException e) {
						Log.warning("IOException in handleInterests: " + e.getClass().getName() + ": " + e.getMessage());
						Log.warningStackTrace(e);
					}
					
				} else {
					Log.finest("No content matching pending interest: " + interest + ", holding.");
					_unmatchedInterests.add(interest, new UnmatchedInterest());
				}
			}
		}
		return interests.size();
	}
	
	/**
	 * Allow override of action after co is put to ccnd
	 * Don't need to sync on holding area because this is only called within
	 * holding area sync
	 * @param co
	 */
	public void afterPutAction(ContentObject co) throws IOException {
		_nOut++;
		_holdingArea.remove(co.name());	
		_holdingArea.notify();
	}
	
	/**
	 * Try to optimize this by giving preference to "getNext" which is
	 * presumably going to be the most common kind of get. So we first try
	 * on a tailmap following the interest, and if that doesn't get us 
	 * anything, we try all the data.
	 * XXX there are probably better ways to optimize this that I haven't
	 * thought of yet also...
	 * 
	 * @param interest
	 * @param set
	 * @return
	 */
	public ContentObject getBestMatch(Interest interest) {
		// paul r - following seems broken for some reason - I'll try
		// to sort it out later
		//SortedMap<ContentName, ContentObject> matchMap = _holdingArea.tailMap(interest.name());
		//ContentObject result = getBestMatch(interest, matchMap.keySet());
		//if (result != null)
		//	return result;
		return getBestMatch(interest, _holdingArea.keySet());
	}
	
	private ContentObject getBestMatch(Interest interest, Set<ContentName> set) {
		ContentObject bestMatch = null;
		Log.finest("Looking for best match to " + interest + " among " + set.size() + " options.");
		for (ContentName name : set) {
			ContentObject result = _holdingArea.get(name);
			
			// We only have to do something unusual here if the caller is looking for CHILD_SELECTOR_RIGHT
			if (null != interest.childSelector() && interest.childSelector() == Interest.CHILD_SELECTOR_RIGHT) {
				if (interest.matches(result)) {
					if (bestMatch == null)
						bestMatch = result;
					if (name.compareTo(bestMatch.name()) > 0) {
						bestMatch = result;
					}
				}
			} else
				if (interest.matches(result))
					return result;
		}
		return bestMatch;
	}
	
	/**
	 * Allow subclasses to override behavior before a flush
	 * @throws IOException
	 */
	public void beforeClose() throws IOException {
		// default -- do nothing.
	}

	/**
	 * Allow subclasses to override behavior after a flush
	 * @throws IOException
	 */
	public void afterClose() throws IOException {
		waitForPutDrain();
	}
	
	/**
	 * Implements a wait until all outstanding data has been drained from the
	 * flow controller. This is required on close to insure that all data is actually
	 * written.
	 * 
	 * @throws IOException if the data has not been drained after a reasonable period
	 */
	public void waitForPutDrain() throws IOException {
		synchronized (_holdingArea) {
			long startSize = _nOut;
			while (_holdingArea.size() > 0) {
				long startTime = System.currentTimeMillis();
				boolean keepTrying = true;
				do {
					try {
						long waitTime = _timeout - (System.currentTimeMillis() - startTime);
						if (waitTime > 0)
							_holdingArea.wait(waitTime);
					} catch (InterruptedException ie) {}
					if (_nOut != startSize || (System.currentTimeMillis() - startTime) >= _timeout)
						keepTrying = false;
				} while (keepTrying);
				
				if (_nOut == startSize) {
					for(ContentName co : _holdingArea.keySet()) {
						Log.warning("FlowController: still holding: " + co.toString());
					}
					throw new IOException("Put(s) with no matching interests - size is " + _holdingArea.size());
				}
				startSize = _holdingArea.size();
			}
		}
	}
	
	/**
	 * Set the time to wait for buffer to drain on close
	 * @param timeout
	 */
	public void setTimeout(int timeout) {
		_timeout = timeout;
	}
	
	/**
	 * Get the current waiting time for the buffer to drain
	 * @return
	 */
	public int getTimeout() {
		return _timeout;
	}
	
	/**
	 * Shutdown but wait for puts to drain first
	 * @throws IOException 
	 */
	public void shutdown() throws IOException {
		waitForPutDrain();
		_handle.getNetworkManager().shutdown();
	}
	
	public CCNHandle getHandle() {
		return _handle;
	}
	
	/**
	 * Remove any currently buffered unmatched interests
	 */
	public void clearUnmatchedInterests() {
		Log.info("Clearing " + _unmatchedInterests.size() + " unmatched interests.");
		_unmatchedInterests.clear();
	}
	
	/**
	 * Reenable disabled buffering.  Buffering is enabled by default.
	 */
	public void enable() {
		_flowControlEnabled = true;
	}
	
	/**
	 * Change the highwater mark for the maximum amount of data to buffer before
	 * causing putters to wait.
	 * 
	 * @param value
	 */
	public void setHighwater(int value) {
		_highwater = value;
	}
	
	/**
	 * Change the maximum amount of unmatched interests to buffer
	 * @param value
	 */
	public void setInterestHighwater(int value) {
		_unmatchedInterests.setHighWater(value);
	}
	
	/**
	 * Disable buffering
	 * 
	 * Warning - calling this risks packet drops. It should only
	 * be used for tests or other special circumstances in which
	 * you "know what you are doing".
	 */
	public void disable() {
		removeNameSpace(null, true);
		_flowControlEnabled = false;
	}
}
