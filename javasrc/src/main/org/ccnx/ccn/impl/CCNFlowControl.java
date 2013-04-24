/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.TreeMap;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.InterestTable.Entry;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


/**
 * This class implements an input buffer for interests and an output buffer
 * for content objects.
 *
 * ccnd will not accept data (content objects) except in response to an
 * interest. This class allows data sources such as output streams to
 * generate content objects speculatively, and buffers them until interests
 * arrive from ccnd. Equally when interests come in from ccnd before the
 * content has been generated this class will buffer the interests until
 * the content is generated.
 *
 * Implements a capacity limit in the holding buffer. If the buffer consumption
 * reaches the specified capacity, any subsequent put will block until there is more
 * room in the buffer. Note that this means that the buffer may hold as many objects as the
 * capacity value, and the object held by any later blocked put is extra, meaning the total
 * number of objects waiting to be sent is not bounded by the capacity alone.
 * Currently this is only per "flow controller". There
 * is nothing to stop multiple streams writing to the repo for instance to
 * independently all fill their buffers and cause a lot of memory to be used.
 *
 * Also implements a limited capacity for held interests.
 *
 * The buffer emptying policy in "afterPutAction" can be overridden by
 * subclasses to implement a different way of draining the buffer.
 */
public class CCNFlowControl implements CCNInterestHandler {

	public enum Shape {
		STREAM("STREAM");

		Shape(String str) { this._str = str; }
		public String value() { return _str; }

		private final String _str;
	}

	public enum SaveType {
		RAW ("RAW"), REPOSITORY ("REPOSITORY"), LOCALREPOSITORY("LOCALREPOSITORY");

		SaveType(String str) { this._str = str; }
		public String value() { return _str; }

		private final String _str;
	}

	protected CCNHandle _handle = null;

	// Designed to allow a CCNOutputStream to flush its current output once without
	// causing the over-capacity blocking to be triggered
	protected static final int DEFAULT_CAPACITY = CCNSegmenter.HOLD_COUNT + 1;

	protected static final int DEFAULT_INTEREST_CAPACITY = 40;

	// Temporarily default to very high timeout so that puts have a good
	// chance of going through.  We actually may want to keep this.
	protected int _timeout = SystemConfiguration.FC_TIMEOUT;
	protected int _timeoutToUse = SystemConfiguration.FC_TIMEOUT;

	protected int _capacity = DEFAULT_CAPACITY;

	// Value used to determine whether the buffer is draining in waitForPutDrain
	protected long _nOut = 0;

	// Unmatched interests are purged from our table if they have remained there longer than this
	//TODO need to normalize this with refresh time in CCNNetworkManager and put in SystemConfiguration
	protected static final int PURGE = 4000;
	protected static long _lastPurgeTime = 0;

	protected TreeMap<ContentName, ContentObject> _holdingArea = new TreeMap<ContentName, ContentObject>();
	protected InterestTable<UnmatchedInterest> _unmatchedInterests = new InterestTable<UnmatchedInterest>();

	// The namespaces served by this flow controller
	protected HashSet<ContentName> _filteredNames = new HashSet<ContentName>();

	private static class UnmatchedInterest {
		long timestamp = System.currentTimeMillis();
	}

	private boolean _flowControlEnabled = true;

	/**
	 * @param name		automatically handles this namespace
	 * @param handle	CCNHandle - created if null
	 * @throws IOException if handle can't be created
	 */
	public CCNFlowControl(ContentName name, CCNHandle handle) throws IOException {
		this(handle);
		if (name != null) {
			if( Log.isLoggable(Log.FAC_IO, Level.INFO))
				Log.info(Log.FAC_IO, "adding namespace: {0}", name);
			// don't call full addNameSpace, in order to allow subclasses to
			// override. just do minimal part
			_filteredNames.add(name);
			_handle.registerFilter(name, this);
		}
	}

	/**
	 * @param name 		automatically handles this namespace
	 * @param handle	CCNHandle - created if null
	 * @throws MalformedContentNameStringException if namespace is malformed
	 * @throws IOException	if handle can't be created
	 */
	public CCNFlowControl(String name, CCNHandle handle)
				throws MalformedContentNameStringException, IOException {
		this(ContentName.fromNative(name), handle);
	}

	/**
	 * @param handle  CCNHandle - created if null
	 * @throws IOException	if handle can't be created
	 */
	public CCNFlowControl(CCNHandle handle) throws IOException {
		if (null == handle) {
			try {
				handle = CCNHandle.open();
			} catch (ConfigurationException e) {
				Log.info(Log.FAC_IO, "Got ConfigurationException attempting to create a handle. Rethrowing it as an IOException. Message: {0}", e.getMessage());
				throw new IOException("ConfigurationException creating a handle: " + e.getMessage());
			}
		}
		_handle = handle;
		_unmatchedInterests.setCapacity(DEFAULT_INTEREST_CAPACITY);
		if (_timeout != SystemConfiguration.NO_TIMEOUT)
			_timeoutToUse = _timeout;
	}

	/**
	 * Filter handler constructor -- an Interest has already come in, and
	 * we are writing a stream in response. So we can write out the first
	 * matching block that we get as soon as we get it (in response to this
	 * preexisting interest). Caller has the responsibilty to ensure that
	 * this Interest is only handed to one CCNFlowControl to emit a block.
	 * @param name an initial namespace to handle
	 * @param outstandingInterest an Interest we have already received; the
	 * 	flow controller will immediately emit the first matching block
	 * @param handle the handle to use. May need to be the same handle
	 * 	that the Interest was received on.
	 */
	public CCNFlowControl(ContentName name, Interest outstandingInterest, CCNHandle handle) throws IOException {
		this(name, handle);
		handleInterest(outstandingInterest);
	}

	/**
	 * Add a new namespace to the controller. The controller will register a filter with ccnd to receive
	 * interests in this namespace.
	 * @param name
	 * @throws IOException
	 */
	public void addNameSpace(ContentName name) throws IOException {
		if (!_flowControlEnabled)
			return;
		Iterator<ContentName> it = _filteredNames.iterator();
		while (it.hasNext()) {
			ContentName filteredName = it.next();
			if (filteredName.isPrefixOf(name)) {
				if( Log.isLoggable(Log.FAC_IO, Level.INFO))
					Log.info(Log.FAC_IO, "addNameSpace: not adding name: {0} already monitoring prefix: {1}", name, filteredName);
				return;		// Already part of filter
			}
			if (name.isPrefixOf(filteredName)) {
				_handle.unregisterFilter(filteredName, this);
				it.remove();
			}
		}
		_filteredNames.add(name);
		_handle.registerFilter(name, this);
		if( Log.isLoggable(Log.FAC_IO, Level.INFO))
			Log.info(Log.FAC_IO, "Flow controller addNameSpace: added namespace: {0}", name);
	}

	/**
	 * Convenience method.
	 * @see #addNameSpace(ContentName)
	 * @param name Namespace to be added in string form.
	 */
	public void addNameSpace(String name) throws MalformedContentNameStringException, IOException {
		addNameSpace(ContentName.fromNative(name));
	}

	/**
	 * Filter handler method, add a namespace and respond to an existing Interest.
	 * @throws IOException
	 */
	public void addNameSpace(ContentName name, Interest outstandingInterest) throws IOException {
		addNameSpace(name);
		handleInterest(outstandingInterest);
	}

	/**
	 * Convenience method.
	 * @see #startWrite(ContentName, Shape)
	 * @throws MalformedContentNameStringException if name is malformed
	 */
	public void startWrite(String name, Shape shape) throws MalformedContentNameStringException, IOException {
		startWrite(ContentName.fromNative(name), shape);
	}
	/**
	 * This is used to indicate that it should start a write for a stream with this
	 * name, and should do any stream-specific setup.
	 * @param name
	 * @param shape currently unused and may be deprecated in the future. Can only be Shape.STREAM
	 * @throws MalformedContentNameStringException if name is malformed
	 * @throws IOException used by subclasses
	 */
	public void startWrite(ContentName name, Shape shape) throws IOException {}

	/**
	 * Remove a namespace from those we are listening for interests within.
	 *
	 * For now we don't have any way to remove a part of a registered namespace from
	 * buffering so we only allow removal of a namespace if it actually matches something that was
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
				if( Log.isLoggable(Log.FAC_IO, Level.FINEST))
					Log.finest(Log.FAC_IO, "removing namespace: {0}", name);
				break;
			}
		}
	}

	/**
	 * Stop attending to all namespaces.
	 */
	public void removeAllNamespaces() {
		removeNameSpace(null, true);
	}

	/**
	 * Helper method to clean up and close.
	 */
	public void close() {
		removeAllNamespaces();
	}

	/**
	 * Someone needs to do the deregistration if nobody else did
	 */
	@Override
	protected void finalize() throws Throwable {
		try {
			close();        // Do the deregistration
		} finally {
			super.finalize();
		}
	}

	/**
	 * Test if this flow controller is currently serving a particular namespace.
	 *
	 * @param childName ContentName of test space
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
	 * Add multiple content objects to this flow controller.
	 * @see #put(ContentObject)
	 *
	 * @param cos 	ArrayList of ContentObjects to put
	 * @throws IOException if the put fails
	 */
	public void put(ArrayList<ContentObject> cos) throws IOException {
		for (ContentObject co : cos) {
			put(co);
		}
	}

	/**
	 * Add multiple content objects to this flow controller.
	 * @see #put(ContentObject)
	 *
	 * @param cos Array of ContentObjects
	 * @throws IOException if the put fails
	 */
	public void put(ContentObject [] cos) throws IOException {
		for (ContentObject co : cos) {
			put(co);
		}
	}

	/**
	 * Add namespace and multiple content at the same time.
	 * @see #addNameSpace(ContentName)
	 * @see #put(ArrayList)
	 *
	 * @param name 	ContentName of namespace
	 * @param cos	ArrayList of ContentObjects
	 * @throws IOException if the put fails
	 */
	public void put(ContentName name, ArrayList<ContentObject> cos) throws IOException {
		addNameSpace(name);
		put(cos);
	}

	/**
	 * Add namespace and content at the same time
	 * @see #addNameSpace(ContentName)
	 * @see #put(ContentObject)
	 *
	 * @param name 	ContentName of namespace
	 * @param co 	ContentObject
	 * @return 		the ContentObject put
	 * @throws IOException if the put fails
	 */
	public ContentObject put(ContentName name, ContentObject co) throws IOException {
		addNameSpace(name);
		return put(co);
	}

	/**
	 * Add a content object to this flow controller. It won't be sent to ccnd immediately unless
	 * a currently waiting interest matches it.
	 *
	 * @param co	ContentObject to put
	 * @return		the ContentObject put
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
	 * Hold a content object in buffer until a matching interest has been received.
	 * @param co
	 * @throws IOException
	 */
	private ContentObject waitForMatch(ContentObject co) throws IOException {
		if (_flowControlEnabled) {
			// Always place the object in the _holdingArea, even if it will be
			// transmitted immediately.  The reason for always holding objects
			// is that there may be different buffer draining policies implemented by
			// subclasses.  For example, a flow control may retain objects until it
			// has verified by separate communication that an intended recipient has
			// received them.
			if( Log.isLoggable(Log.FAC_IO, Level.FINEST))
				Log.finest(Log.FAC_IO, "Holding {0}", co.name());
			// Must verify space in _holdingArea or block waiting for space
			int size = 0;
			int capacity = 0;
			synchronized (_holdingArea) {
				size = _holdingArea.size();
				capacity = _capacity;
			}
			if (size >= capacity) {
				long ourTime = System.currentTimeMillis();

				// purge old unmatched interests
				// Don't do it too often as this is time consuming
				if ((ourTime - _lastPurgeTime) > PURGE) {
					synchronized (_unmatchedInterests) {
						removeUnmatchedInterests(ourTime);
						_lastPurgeTime = ourTime;
					}
				}

				// Now wait for space to be cleared or timeout
				// Must guard against "spurious wakeup" so must check elapsed time directly
				if( Log.isLoggable(Log.FAC_IO, Level.FINEST))
					Log.finest(Log.FAC_IO, "Waiting for drain size is {0}", size);
				long elapsed = 0;
				synchronized (_holdingArea) {
					do {
						try {
							_holdingArea.wait(_timeoutToUse-elapsed);
						} catch (InterruptedException e) {
							// intentional no-op
						}
						elapsed = System.currentTimeMillis() - ourTime;
						size = _holdingArea.size();
					} while (size >= capacity && (_timeout == SystemConfiguration.NO_TIMEOUT || elapsed < _timeoutToUse));
				}
				if (size >= capacity) {
					String names = "";
					for (ContentName name : _filteredNames) {
						names += name + ",";
					}
					Log.warning(Log.FAC_IO, "Flow control buffer full for: " + names);
					throw new IOException("Flow control buffer full and not draining");
				}
			}
			assert(size < capacity);
			// Space verified so now can hold object. See note above for reason to always hold.

			Entry<UnmatchedInterest> match = null;
			synchronized (_holdingArea) {
				_holdingArea.put(co.name(), co);

				// Check for pending interest match to allow immediate transmit
				match = _unmatchedInterests.removeMatch(co);
			}
			if (match != null) {
				if (Log.isLoggable(Log.FAC_IO, Level.FINEST))
					Log.finest(Log.FAC_IO, "Found pending matching interest for {0}, putting to network.", co.name());
				_handle.put(co);
				// afterPutAction may immediately remove the object from _holdingArea or retain it
				// depending upon the buffer drain policy being implemented.
				synchronized (_holdingArea) {
					afterPutAction(co);
				}
			} else {
				if (Log.isLoggable(Log.FAC_IO, Level.FINEST))
					Log.finest(Log.FAC_IO, "No match found for {0}", co.name());
			}
		} else // Flow control disabled entirely: put to network immediately
			_handle.put(co);
		return co;
	}

	/**
	 * Function to remove expired interests from the flow controller.  This is called when a content
	 * object is received and when an interest is added to the buffer.
	 *
	 * Must be called with _unmatchedInterests locked
	 *
	 * @param ourTime current time for checking if interests are expired
	 */
	private void removeUnmatchedInterests(long ourTime) {
		Entry<UnmatchedInterest> removeIt;
		do {
			removeIt = null;
			for (Entry<UnmatchedInterest> uie : _unmatchedInterests.values()) {
				if ((ourTime - uie.value().timestamp) > PURGE) {
					removeIt = uie;
					break;
				} else {
					//we add interests at the end...  so older interests are at the top
					break;
				}
			}
			if (removeIt != null) {
				if (Log.isLoggable(Log.FAC_IO, Level.INFO))
					Log.info(Log.FAC_IO, "Removing unmatched interest {0}", removeIt.interest().name());
				_unmatchedInterests.remove(removeIt.interest(), removeIt.value());
			}
		} while (removeIt != null);
	}


	/**
	 * Match incoming interests with data in the buffer. If the interest doesn't match it is
	 * buffered awaiting potential later incoming data which may match it.
	 *
	 * Note that this method is used for testing only, since the interest callback only takes one interest
	 *
	 */
	public void handleInterests(ArrayList<Interest> interests) {
		for (Interest interest : interests) {
			handleInterest(interest);
		}
	}

	/**
	 * Match an incoming interest with data in the buffer. If the interest doesn't match it is
	 * buffered awaiting potential later incoming data which may match it. This method returns 0
	 * if the interest was null.
	 *
	 */
	public boolean handleInterest(Interest i) {
		if (i == null)
			return false;
		if (Log.isLoggable(Log.FAC_IO, Level.FINE))
			Log.fine(Log.FAC_IO, "Flow controller {0}: got interest: {1}", this, i);
		ContentObject co;
		synchronized (_holdingArea) {

			co = getBestMatch(i);
			if (co == null) {
				//only check if we are adding the interest, and check before we add so we don't check the new interest
				if (_unmatchedInterests.size() > 0)
					removeUnmatchedInterests(System.currentTimeMillis());

				Log.finest(Log.FAC_IO, "No content matching pending interest: {0}, holding.", i);
				_unmatchedInterests.add(i, new UnmatchedInterest());
				return false;		// XXX is this the right thing to do?
			}
		}

		if( Log.isLoggable(Log.FAC_IO, Level.FINEST))
			Log.finest(Log.FAC_IO, "Found content {0} matching interest: {1}",co.name(), i);
		try {
			_handle.put(co);
			synchronized (_holdingArea) {
				afterPutAction(co);
			}
		} catch (IOException e) {
			Log.warning(Log.FAC_IO, "IOException in handleInterests: {0}: {1}", e.getClass().getName(), e.getMessage());
			Log.warningStackTrace(e);
		}

		return true;
	}


	/**
	 * Allow override of action after a ContentObject is sent to ccnd
	 *
	 * NOTE: Don't need to sync on holding area because this is only called within
	 * holding area sync
	 * NOTE: Any subclass overriding this method must either make sure to call it eventually (in a _holdingArea sync)
	 * or understand the use of _nOut and update it appropriately.
	 *
	 * @param co ContentObject to remove from flow controller.
	 * @throws IOException may be thrown by overriding subclasses
	 */
	public void afterPutAction(ContentObject co) throws IOException {
		remove(co);
	}

	/**
	 * Must be called with _holdingArea locked
	 * @param interest
	 * @param set
	 * @return
	 */
	private ContentObject getBestMatch(Interest interest) {
		ContentObject bestMatch = null;
		if( Log.isLoggable(Log.FAC_IO, Level.FINEST))
			Log.finest(Log.FAC_IO, "Looking for best match to {0} among {1} options.", interest, _holdingArea.size());
		for ( java.util.Map.Entry<ContentName, ContentObject> entry :  _holdingArea.entrySet() ) {
			ContentName name = entry.getKey();
			ContentObject result = entry.getValue();

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
	 * flow controller. This is required on close to ensure that all data is actually
	 * sent to ccnd.
	 *
	 * @throws IOException if the data has not been drained after a reasonable period
	 */
	protected void waitForPutDrain() throws IOException {
		synchronized (_holdingArea) {
			long startSize = _nOut;
			while (_holdingArea.size() > 0) {
				long startTime = System.currentTimeMillis();
				boolean keepTrying = true;
				do {
					try {
						long waitTime = _timeoutToUse - (System.currentTimeMillis() - startTime);
						if (waitTime > 0)
							_holdingArea.wait(waitTime);
					} catch (InterruptedException ie) {}
					if (_nOut != startSize || (System.currentTimeMillis() - startTime) >= _timeoutToUse)
						keepTrying = false;
				} while (keepTrying);

				if (_nOut == startSize) {
					for(ContentName co : _holdingArea.keySet()) {
						Log.warning(Log.FAC_IO, "FlowController: still holding: {0}", co.toString());
					}
					// For now - dump the handlers stack if its active in case that may give a clue about what's wrong.
					// We may want to leave this in permanently.
					CCNNetworkManager cnm = _handle.getNetworkManager();
					if (null != cnm)
						cnm.dumpHandlerStackTrace("waitForPutDrain");
					throw new IOException("Put(s) with no matching interests - size is " + _holdingArea.size());
				}
				startSize = _nOut;
			}
		}
	}

	/**
	 * Set the time to wait for buffer to drain on close
	 * @param timeout timeout in milliseconds
	 */
	public void setTimeout(int timeout) {
		_timeout = timeout;
		if (timeout != SystemConfiguration.NO_TIMEOUT)
			_timeoutToUse = timeout;
	}

	/**
	 * Get the current waiting time for the buffer to drain
	 * @return 	timeout in milliseconds
	 */
	public int getTimeout() {
		return _timeout;
	}

	/**
	 * Shutdown operation of this flow controller -- wait for all current
	 * data to clear, and unregister all outstanding interests. Do *not*
	 * shut down the handle; we might not own it.
	 * @throws IOException if buffer doesn't drain within timeout
	 */
	public void shutdown() throws IOException {
		waitForPutDrain();
		removeAllNamespaces();
	}

	/**
	 * Gets the CCNHandle used by this controller
	 * @return	a CCNHandle
	 */
	public CCNHandle getHandle() {
		return _handle;
	}

	/**
	 * Remove any currently buffered unmatched interests
	 */
	public void clearUnmatchedInterests() {
		if( Log.isLoggable(Log.FAC_IO, Level.INFO))
			Log.info(Log.FAC_IO, "Clearing {0} unmatched interests.", _unmatchedInterests.size());
		_unmatchedInterests.clear();
	}

	/**
	 * Debugging function to log unmatched interests.
	 */
	public void logUnmatchedInterests(String logMessage) {
		if( Log.isLoggable(Log.FAC_IO, Level.INFO))
			Log.info(Log.FAC_IO, "{0}: {1} unmatched interest entries.", logMessage, _unmatchedInterests.size());
		for (Entry<UnmatchedInterest> interestEntry : _unmatchedInterests.values()) {
			if (null != interestEntry.interest())
				if( Log.isLoggable(Log.FAC_IO, Level.INFO))
					Log.info(Log.FAC_IO, "   Unmatched interest: {0}", interestEntry.interest());
		}
	}

	/**
	 * Re-enable disabled buffering.  Buffering is enabled by default.
	 */
	public void enable() {
		_flowControlEnabled = true;
	}

	/**
	 * Change the capacity for the maximum amount of data to buffer before
	 * causing putters to block. The capacity value is the number of content objects
	 * that will be buffered.
	 *
	 * @param value number of content objects.
	 */
	public void setCapacity(int value) {
		synchronized (_holdingArea) {
			_capacity = value;
		}
	}

	/**
	 * Set the capacity to the maximum possible value, Integer.MAX_VALUE.
	 */
	public void setMaximumCapacity() {
		synchronized (_holdingArea) {
			_capacity = Integer.MAX_VALUE;
		}
	}

	/**
	 * Change the maximum number of unmatched interests to buffer.
	 * @param value	number of interests
	 */
	public void setInterestCapacity(int value) {
		_unmatchedInterests.setCapacity(value);
	}

	/**
	 * What is the total capacity of this flow controller?
	 * @return the total capacity of this flow controller; in other words the
	 *   number of segments that can be written to it before writes will block
	 */
	public int getCapacity() {
		synchronized (_holdingArea) {
			return _capacity;
		}
	}

	/**
	 * Get the number of objects this flow controller is currently holding.
	 * @return the number of objects (segments) in the buffer
	 */
	public int size() {
		synchronized (_holdingArea) {
			return _holdingArea.size();
		}
	}

	/**
	 * Get the amount of remaining space available in this flow controller's buffer.
	 * @return the number of additional objects that can currently be written to this controller
	 */
	public int availableCapacity() {
		synchronized (_holdingArea) {
			return _capacity - _holdingArea.size(); // off by 1?
		}
	}

	/**
	 * Remove a ContentObject from the set buffered by this flow controller, either
	 * because we're done with it, or because we don't want to buffer it anymore.
	 * Need a way to get the CO to remove; might want a remove(ContentName) or
	 * something like it.
	 */
	public void remove(ContentObject co) {
		// do synchronize on _holdingArea as we may be called directly; if called
		// with lock on _holdingArea will be fine (reentrant locks), though
		// should evaluate performance cost
		synchronized(_holdingArea) {
			_nOut++; // do we need to do this, or only in afterPutAction?
			_holdingArea.remove(co.name());
			_holdingArea.notify();
		}
	}

	/**
	 * Remove all the held objects from this buffer.
	 */
	public void clear() {
		synchronized(_holdingArea) {
			_nOut += size();
			_holdingArea.clear();
			_holdingArea.notify();
		}
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

	/**
	 * Help users determine what type of flow controller this is.
	 */
	public SaveType saveType() { return SaveType.RAW; }
}
