/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2010, 2012, 2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles.nameenum;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;

import org.bouncycastle.util.Arrays;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentNameProvider;

/**
 * Blocking and background interface to name enumeration. This allows a caller to specify a prefix
 * under which to enumerate available children, and name enumeration to proceed in the background
 * for as long as desired, providing updates whenever new data is published.
 * Currently implemented as a wrapper around CCNNameEnumerator, will likely directly aggregate
 * name enumeration responses in the future.
 *
 * @see CCNNameEnumerator
 * @see BasicNameEnumeratorListener
 */
public class EnumeratedNameList implements BasicNameEnumeratorListener, ContentNameProvider {
	
	protected ContentName _namePrefix;
	protected CCNNameEnumerator _enumerator;
	protected BasicNameEnumeratorListener callback;
	// make these contain something other than content names when the enumerator has better data types
	protected SortedSet<ContentName> _children = new TreeSet<ContentName>();
	//protected SortedSet<ContentName> _newChildren = null;
	protected Map<Long, NewChildrenByThread> _newChildrenByThread = new TreeMap<Long, NewChildrenByThread>();
	protected Object _childLock = new Object();
	protected CCNTime _lastUpdate = null;
	protected boolean _enumerating = false;
	protected boolean _shutdown = false;

	private class NewChildrenByThread implements Comparable<NewChildrenByThread> {
		private final Long _id;		// If 0 this is in thread pool mode
		private SortedSet<ContentName> _newChildren = null;

		private NewChildrenByThread(Long id) {
			this._id = id;
		}

		public int compareTo(NewChildrenByThread o) {
			return _id.compareTo(o._id);
		}
	}

	/**
	 * Keep track of whether we've ever done enumeration, so we can start it automatically
	 * if someone asks us for something not in our cache. This lets us relax the requirement
	 * that callers pre-enumerate just in case. Can't use hasChildren; there might not
	 * be any children but we might have tried enumeration already.
	 */
	protected boolean _hasEnumerated = false;

	/**
	 * Creates an EnumeratedNameList object
	 *
	 * This constructor creates a new EnumeratedNameList object that will begin enumerating
	 * the children of the specified prefix.  The new EnumeratedNameList will use the CCNHandle passed
	 * in to the constructor, or create a new one using CCNHandle#open() if it is null.
	 *
	 * @param  namePrefix the ContentName whose children we wish to list.
	 * @param  handle the CCNHandle object for sending interests and receiving content object responses.
	 */
	public EnumeratedNameList(ContentName namePrefix, CCNHandle handle) throws IOException {
		this(namePrefix, true, handle);
	}

	public EnumeratedNameList(ContentName namePrefix, boolean startEnumerating, CCNHandle handle) throws IOException {
		if (null == namePrefix) {
			throw new IllegalArgumentException("namePrefix cannot be null!");
		}
		if (null == handle) {
			try {
				handle = CCNHandle.open();
			} catch (ConfigurationException e) {
				throw new IOException("ConfigurationException attempting to open a handle: " + e.getMessage());
			}
		}
		_namePrefix = namePrefix;
		if (startEnumerating) {
			_enumerating = true;
			_hasEnumerated = true;
			_enumerator = new CCNNameEnumerator(namePrefix, handle, this);
		} else {
			_enumerator = new CCNNameEnumerator(handle, this);
		}
	}

	/**
	 * Method to return the ContentName used for enumeration.
	 *
	 * @return ContentName returns the prefix under which we are enumerating children.
	 */
	public ContentName getName() { return _namePrefix; }

	/**
	 * Cancels ongoing name enumeration. Previously-accumulated information about
	 * children of this name are still stored and available for use.
	 *
	 * @return void
	 * */
	public synchronized void stopEnumerating() {
		if (!_enumerating) {
			if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO)) {
				Log.info(Log.FAC_SEARCH, "Enumerated name list: Not enumerating, so not canceling prefix.");
			}
			return;
		}
		_enumerator.cancelPrefix(_namePrefix);
		_enumerating = false;
	}

	/**
	 * Starts enumeration, if we're not enumerating already.
	 * @throws IOException
	 */
	public synchronized void startEnumerating() throws IOException {
		_enumerating = true;
		_enumerator.registerPrefix(_namePrefix);
		_hasEnumerated = true;
	}

	public boolean isEnumerating() { return _enumerating; }

	public boolean hasEnumerated() { return _hasEnumerated; }

	/**
	 * Shutdown anybody waiting for children on this list
	 */
	public void shutdown() {
		_shutdown = true;
		synchronized (_childLock) {
			_childLock.notifyAll();
		}
	}

	/**
	 * Interface to retrieve only new data from enumeration responses as it arrives.
	 * This method blocks and waits for data, but grabs the new data for processing.
	 * In threadPoolContext it will in effect remove the data from every other
	 * listener who is listening in threadPoolContext, in effect handing the new
	 * children to the first consumer to wake up and make the other ones go around again.
	 * There is currently no support for more than one simultaneous thread pool.
	 *
	 * @param threadPoolContext Are we getting data in threadPoolContext? (described above).
	 * @param timeout maximum amount of time to wait, 0 to wait forever.
	 * @return SortedSet<ContentName> Returns the array of single-component
	 * 	content name children that are new to us, or null if we reached the
	 *  timeout before new data arrived
	 */
	public SortedSet<ContentName> getNewData(boolean threadPoolContext, long timeout) {
		SortedSet<ContentName> childArray = null;
		synchronized(_childLock) { // reentrant
			Long id = threadPoolContext ? 0 : Thread.currentThread().getId();
			NewChildrenByThread ncbt = _newChildrenByThread.get(id);
			SortedSet<ContentName> newChildren = ncbt == null ? null : ncbt._newChildren;
			while ((null == newChildren) || newChildren.size() == 0) {
				waitForNewChildren(threadPoolContext, timeout);
				ncbt = _newChildrenByThread.get(id);
				newChildren = ncbt._newChildren;
				if (timeout != SystemConfiguration.NO_TIMEOUT)
					break;
			}

			if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO)) {
				Log.info(Log.FAC_SEARCH, "Waiting for new data on prefix: {0} got {1}.",
							_namePrefix, ((null == newChildren) ? 0 : newChildren.size()));
			}

			if (null != newChildren) {
				childArray = newChildren;
				ncbt._newChildren = null;
			}
		}
		return childArray;
	}

	/**
	 * Block and wait as long as it takes for new data to appear. See #getNewData(boolean, long).
	 * @return SortedSet<ContentName> Returns the array of single-component
	 * 	content name children that are new to us. Waits forever if no new data appears
	 */
	public SortedSet<ContentName> getNewData() {
		return getNewData(false, SystemConfiguration.NO_TIMEOUT);
	}

	/**
	 * Block and wait for timeout or until new data appears. See #getNewData(boolean, long).
	 * @param timeout in ms
	 * @return SortedSet<ContentName> Returns the array of single-component
	 * 	content name children that are new to us, or null if we reached the
	 *  timeout before new data arrived
	 */
	public SortedSet<ContentName> getNewData(long timeout) {
		return getNewData(false, timeout);
	}

	/**
	 * Block and wait for timeout or until new data appears. See #getNewData(boolean, long).
	 * Different from getNewData in that new data is shared among all threads accessing this
	 * instance of EnumeratedNameList. So if another thread gets the data first, we won't get it.
	 * @param timeout in ms
	 * @return SortedSet<ContentName> Returns the array of single-component
	 * 	content name children that are new to the list instance if we got it first, or null if we
	 *  reached the timeout before new data arrived
	 */
	public SortedSet<ContentName> getNewDataThreadPool(long timeout) {
		return getNewData(true, timeout);
	}

	/**
	 * Returns single-component ContentName objects containing the name components of the children.
	 * @return SortedSet<ContentName> Returns the array of single-component
	 * 	content name children that have been retrieved so far, or null if no responses
	 *  have yet been received. The latter may indicate either that no children of this prefix
	 *  are known to any responders, or that they have not had time to respond.
	 */
	public SortedSet<ContentName> getChildren() {
		if (!hasChildren())
			return null;
		return _children;
	}

	/**
	 * Returns true if the prefix has new names that have not been handled by the calling application.
	 * @return true if there are new children available to process
	 */
	public boolean hasNewData() {
		NewChildrenByThread ncbt = getNcbt();
		if (null == ncbt)
			return false;	// Never set up
		return ((null != ncbt._newChildren) && (ncbt._newChildren.size() > 0));
	}

	/**
	 * Returns true if we have received any responses listing available child names.
	 * If no names have yet been received, this may mean either that responses
	 * have not had time to arrive, or there are know children known to available
	 * responders.
	 *
	 * @return true if we have child names received from enumeration responses
	 */
	public boolean hasChildren() {
		return ((null != _children) && (_children.size() > 0));
	}

	/**
	 * Returns the number of children we have, or 0 if we have none.
	 */
	public int childCount() {
		if (null == _children)
			return 0;
		return _children.size();
	}

	/**
	 * Returns true if we know the prefix has a child matching the given name component.
	 *
	 * @param childComponent name component to check for in the stored child names.
	 * @return true if that child is in our list of known children
	 */
	public boolean hasChild(byte [] childComponent) {
		for (ContentName child : _children) {
			if (Arrays.areEqual(childComponent, child.component(0))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns whether a child is present in the list of known children.
	 * <p>
	 *
	 * @param childName String version of a child name to look for
	 * @return boolean Returns true if the name is present in the list of known children.
	 * */
	public boolean hasChild(String childName) {
		return hasChild(Component.parseNative(childName));
	}

	/**
	 * Wait for new children to arrive.
	 *
	 * @param timeout Maximum time to wait for new data.
	 * @param threadPoolContext Are we waiting in threadPoolContext (i.e. other threads can grab children first)
	 *        See #getNewData(boolean, long).
	 * @return a boolean value that indicates whether new data was found.
	 */
	public boolean waitForNewChildren(boolean threadPoolContext, long timeout) {
		boolean foundNewData = false;

		Long id = threadPoolContext ? 0 : Thread.currentThread().getId();
		_newChildrenByThread.put(id, new NewChildrenByThread(id));
		synchronized(_childLock) {
			CCNTime lastUpdate = _lastUpdate;
			long timeRemaining = timeout;
			long startTime = System.currentTimeMillis();
			while (((null == _lastUpdate) || ((null != lastUpdate) && !_lastUpdate.after(lastUpdate))) &&
				   ((timeout == SystemConfiguration.NO_TIMEOUT) || (timeRemaining > 0))) {
				if (_shutdown)
					break;
				try {
					_childLock.wait((timeout != SystemConfiguration.NO_TIMEOUT) ? Math.min(timeRemaining, SystemConfiguration.CHILD_WAIT_INTERVAL) : SystemConfiguration.CHILD_WAIT_INTERVAL);
					if (timeout != SystemConfiguration.NO_TIMEOUT) {
						timeRemaining = timeout - (System.currentTimeMillis() - startTime);
					}
				} catch (InterruptedException e) {
				}
				if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO)) {
					SortedSet<ContentName> newChildren = _newChildrenByThread.get(id)._newChildren;
					Log.info(Log.FAC_SEARCH, "Waiting for new data on prefix: {0}, updated {1}, our update {2}, have {3} children {4} new.",
							_namePrefix, _lastUpdate, lastUpdate,
							((null == _children) ? 0 : _children.size()),
							((null == newChildren) ? 0 : newChildren.size()));
				}
			}
			if ((null != _lastUpdate) && ((null == lastUpdate) || (_lastUpdate.after(lastUpdate)))) foundNewData = true;
		}
		return foundNewData;
	}

	/**
	 * Wait for new children to arrive.
	 * This method does not have a timeout and will wait forever.
	 *
	 * @return void
	 */
	public void waitForNewChildren() {
		waitForNewChildren(false, SystemConfiguration.NO_TIMEOUT);
	}

	/**
	 * Wait for new children to arrive.
	 *
	 * @param timeout Maximum amount of time to wait, if 0, waits forever.
	 * @return a boolean value that indicates whether new data was found.
	 */
	public boolean waitForNewChildren(long timeout) {
		return waitForNewChildren(false, timeout);
	}

	/**
	 * Wait for new children to arrive in thread pool context.  See notes about this above.
	 * @param timeout Maximum amount of time to wait, if 0, waits forever.
	 * @return a boolean value that indicates whether new data was found.
	 */
	public boolean waitForNewChildrenThreadPool(long timeout) {
		return waitForNewChildren(true, timeout);
	}

	/**
	 * Waits until there is any data at all. Right now, waits for the first response containing actual
	 * children, not just a name enumeration response. That means it could block
	 * forever if no children exist in a repository or there are not any applications responding to
	 * name enumeration requests. Once we have an initial set of children, this method
	 * returns immediately.
	 *
	 * @param timeout Maximum amount of time to wait, if 0, waits forever.
	 * @return void
	 */
	public void waitForChildren(long timeout) {
		while ((null == _children) || _children.size() == 0) {
			waitForNewChildren(false, timeout);
			if (timeout != SystemConfiguration.NO_TIMEOUT)
				break;
		}
	}

	/**
	 * Wait (block) for initial data to arrive, possibly forever. See #waitForData(long).
	 *
	 * @return void
	 *
	 */
	public void waitForChildren() {
		waitForChildren(SystemConfiguration.NO_TIMEOUT);
	}

	/**
	 * Wait for new children to arrive until there is a period of length timeout during which
	 * no new child arrives.
	 * @param timeout The maximum amount of time to wait between consecutive children arrivals.
	 */
	public void waitForNoUpdates(long timeout) {
		if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
			Log.info(Log.FAC_SEARCH, "Waiting for updates on prefix {0} with max timeout of {1} ms between consecutive children arrivals.",
				_namePrefix, timeout);
		long startTime = System.currentTimeMillis();
		while (waitForNewChildren(false, timeout)) {
			if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
				Log.info(Log.FAC_SEARCH, "Child or children found on prefix {0}", _namePrefix);
		}
		if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
			Log.info(Log.FAC_SEARCH, "Quit waiting for updates on prefix {0} after waiting in total {1} ms.",
					_namePrefix, (System.currentTimeMillis() - startTime));
	}

	/**
	 * Wait for new children to arrive until there is a period of length timeout during which
	 * no new child arrives, or the method hasResult() returns true. The expectation
	 * is that a subclass will monitor incoming updates in its processNewChildren() override
	 * method, and in that method, set some sort of flag that will be tested by hasResult().
	 * Note that this method does not currently stop enumeration -- enumeration results will
	 * continue to accumulate in the background (and request interests will continue to be sent);
	 * callers must call stopEnumerating() to actually terminate enumeration.
	 */
	public void waitForNoUpdatesOrResult(long timeout) {

		if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
			Log.info(Log.FAC_SEARCH, "Waiting for updates on prefix {0} with max timeout of {1} ms between consecutive children arrivals.",
				_namePrefix, timeout);
		long startTime = System.currentTimeMillis();
		if (hasResult()) {
			return;
		}
		while (waitForNewChildren(false, timeout)) {
			if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
				Log.info(Log.FAC_SEARCH, "Child or children found on prefix {0}. Have result? {1}", _namePrefix, hasResult());
			if (hasResult()) break;
		}
		if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
			Log.info(Log.FAC_SEARCH, "Quit waiting for updates on prefix {0} after waiting in total {1} ms. Have desired result? {2}",
				_namePrefix, (System.currentTimeMillis() - startTime), hasResult());
	}

	/**
	 * Subclasses should override this test to answer true if waiters should break out of a
	 * waitForNoUpdatesOrResult loop. Note that results must be cleared manually using clearResult.
	 * Default behavior always returns false. Subclasses probably want to set a variable in processNewChildren
	 * that will be read here.
	 */
	public boolean hasResult() {
		return false;
	}

	/**
	 * Reset whatever state hasResult tests. Overridden by subclasses, default does nothing.
	 */
	public void clearResult() {
		return;
	}

	/**
	 * Method to allow subclasses to do post-processing on incoming names
	 * before handing them to customers.
	 * Note that the set handed in here is not the set that will be handed
	 * out; only the name objects are the same.
	 *
	 * @param newChildren SortedSet of children available for processing
	 *
	 * @return void
	 */
	protected void processNewChildren(SortedSet<ContentName> newChildren) {
		// default -- do nothing
	}

	/**
	 * If some or all of the children of this name are versions, returns the latest version
	 * among them.
	 *
	 * @return ContentName The latest version component
	 * */
	public ContentName getLatestVersionChildName() {
		// of the available names in _children that are version components,
		// find the latest one (version-wise)
		// names are sorted, so the last one that is a version should be the latest version
		// ListIterator previous doesn't work unless you've somehow gotten it to point at the end...
		ContentName theName = null;
		ContentName latestName = null;
		CCNTime latestTimestamp = null;
		Iterator<ContentName> it = _children.iterator();
		// TODO these are sorted -- we just need to iterate through them in reverse order. Having
		// trouble finding something like C++'s reverse iterators to do that (linked list iterators
		// can go backwards -- but you have to run them to the end first).
		while (it.hasNext()) {
			theName = it.next();
			if (VersioningProfile.isVersionComponent(theName.component(0))) {
				if (null == latestName) {
					latestName = theName;
					latestTimestamp = VersioningProfile.getVersionComponentAsTimestamp(theName.component(0));
				} else {
					CCNTime thisTimestamp = VersioningProfile.getVersionComponentAsTimestamp(theName.component(0));
					if (thisTimestamp.after(latestTimestamp)) {
						latestName = theName;
						latestTimestamp = thisTimestamp;
					}
				}
			}
		}
		return latestName;
	}

	/**
	 * Handle responses from CCNNameEnumerator that give us a list of single-component child
	 * names. Filter out the names new to us, add them to our list of known children, postprocess
	 * them with processNewChildren(SortedSet<ContentName>), and signal waiters if we
	 * have new data.
	 *
	 * @param prefix Prefix used for name enumeration.
	 * @param names The list of names returned in this name enumeration response.
	 *
	 * @return int
	 */
	@SuppressWarnings("unchecked")
	public int handleNameEnumerator(ContentName prefix,
								    ArrayList<ContentName> names) {

		if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO)) {
			if (!_enumerating) {
				// Right now, just log if we get data out of enumeration, don't drop it on the floor;
				// don't want to miss results in case we are started again.
				Log.info(Log.FAC_SEARCH, "ENUMERATION STOPPED: but {0} new name enumeration results: our prefix: {1} returned prefix: {2}", names.size(), _namePrefix, prefix);
			} else {
				Log.info(Log.FAC_SEARCH, "{0} new name enumeration results: our prefix: {1} returned prefix: {2}", names.size(), _namePrefix, prefix);
			}
		}
		if (!prefix.equals(_namePrefix)) {
			Log.warning(Log.FAC_SEARCH, "Returned data doesn't match requested prefix!");
		}

		if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
			Log.info(Log.FAC_SEARCH, "Handling Name Iteration {0}, size is {1}", prefix, names.size());
		// the name enumerator hands off names to us, we own it now
		// DKS -- want to keep listed as new children we previously had
		synchronized (_childLock) {
			TreeSet<ContentName> thisRoundNew = new TreeSet<ContentName>();
			thisRoundNew.addAll(names);
			Iterator<ContentName> it = thisRoundNew.iterator();
			while (it.hasNext()) {
				ContentName name = it.next();
				if (_children.contains(name)) {
					it.remove();
				}
			}
			if (!thisRoundNew.isEmpty()) {
				for (NewChildrenByThread ncbt : _newChildrenByThread.values()) {
					if (null != ncbt._newChildren) {
						ncbt._newChildren.addAll(thisRoundNew);
					} else {
						ncbt._newChildren = (TreeSet<ContentName>)thisRoundNew.clone();
					}
				}
				_children.addAll(thisRoundNew);
				_lastUpdate = new CCNTime();
				if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO)) {
					Log.info(Log.FAC_SEARCH, "New children found: at {0} {1} total children {2}", _lastUpdate, + thisRoundNew.size(), _children.size());
				}
				processNewChildren(thisRoundNew);
				_childLock.notifyAll();
			}
		}
		return 0;
	}

	/**
	 * Returns the latest version available under this prefix as a byte array.
	 *
	 * @return byte[] Latest child version as byte array
	 */
	public byte [] getLatestVersionChildNameComponent() {
		ContentName latestVersionName = getLatestVersionChildName();
		if (null == latestVersionName)
			return null;
		return latestVersionName.component(0);
	}

	/**
	 * Returns the latest version available under this prefix as a CCNTime object.
	 *
	 * @return CCNTime Latest child version as CCNTime
	 */
	public CCNTime getLatestVersionChildTime() {
		ContentName latestVersion = getLatestVersionChildName();
		if (null != latestVersion) {
			return VersioningProfile.getVersionComponentAsTimestamp(latestVersion.component(0));
		}
		return null;
	}

	/**
	 * A static method that performs a one-shot call that returns the complete name of the latest
	 * version of content with the given prefix. An alternative route to finding the name of the
	 * latest version of a piece of content, rather than using methods in the VersioningProfile
	 * to retrieve an arbitrary block of content under that version. Useful when the data under
	 * a version is complex in structure.
	 *
	 * @param name ContentName to find the latest version of
	 * @param handle CCNHandle to use for enumeration
	 * @return ContentName The name supplied to the call with the latest version added.
	 * @throws IOException
	 */
	public static ContentName getLatestVersionName(ContentName name, CCNHandle handle) throws IOException {
		EnumeratedNameList enl = new EnumeratedNameList(name, handle);
		enl.waitForNoUpdates(SystemConfiguration.MAX_TIMEOUT);
		ContentName childLatestVersion = enl.getLatestVersionChildName();
		enl.stopEnumerating();
		if (null != childLatestVersion) {
			return new ContentName(name, childLatestVersion.component(0));
		}
		return null;
	}

	/**
	 * Static method that iterates down the namespace starting with the supplied prefix
	 * as a ContentName (prefixKnownToExist) to a specific child (childName). The method
	 * returns null if the name does not exist in a limited time iteration.  If the child
	 * is found, this method returns the EnumeratedNameList object for the parent of the
	 * desired child in the namespace.  The current implementation may time out before the
	 * desired name is found.  Additionally, the current implementation does not loop on
	 * an enumeration attempt, so a child may be missed if it is not included in the first
	 * enumeration response.
	 *
	 * TODO Add loop to enumerate under a name multiple times to avoid missing a child name
	 * TODO Handle timeouts better to avoid missing children.  (Note: We could modify the
	 * name enumeration protocol to return empty responses if we query for an unknown name,
	 *  but that adds semantic complications.)
	 *
	 * @param childName ContentName for the child we are looking for under (does not have
	 * to be directly under) a given prefix.
	 * @param prefixKnownToExist ContentName prefix to enumerate to look for a given child.
	 * @param handle CCNHandle for sending and receiving interests and content objects.
	 *
	 * @return EnumeratedNameList Returns the parent EnumeratedNameList for the desired child,
	 * if one is found.  Returns null if the child is not found.
	 *
	 * @throws IOException
	 */
	public static EnumeratedNameList exists(ContentName childName, ContentName prefixKnownToExist, CCNHandle handle) throws IOException {
		if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
			Log.info(Log.FAC_SEARCH, "EnumeratedNameList.exists: the prefix known to exist is {0} and we are looking for childName {1}", prefixKnownToExist, childName);
		if ((null == prefixKnownToExist) || (null == childName) || (!prefixKnownToExist.isPrefixOf(childName))) {
			if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
				Log.info(Log.FAC_SEARCH, "EnumeratedNameList.exists: Child {0} must be prefixed by name {1}", childName, prefixKnownToExist);
			throw new IllegalArgumentException("Child " + childName + " must be prefixed by name " + prefixKnownToExist);
		}
		if (childName.count() == prefixKnownToExist.count()) {
			// we're already there
			if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
				Log.info(Log.FAC_SEARCH, "EnumeratedNameList.exists: we're already there.");
			return new EnumeratedNameList(childName, handle);
		}
		ContentName parentName = prefixKnownToExist;
		int childIndex = parentName.count();
		EnumeratedNameList parentEnumerator = null;
		while (childIndex < childName.count()) {
			byte[] childNameComponent = childName.component(childIndex);
			parentEnumerator = new EnumeratedNameList(parentName, handle);
			if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
				Log.info(Log.FAC_SEARCH, "EnumeratedNameList.exists: enumerating the parent name {0}", parentName);
			parentEnumerator.waitForChildren(SystemConfiguration.MAX_TIMEOUT);
			while (! parentEnumerator.hasChild(childNameComponent)) {
				if (! parentEnumerator.waitForNewChildren(false, SystemConfiguration.MAX_TIMEOUT)) break;
			}
			if (parentEnumerator.hasChild(childNameComponent)) {
				if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO)) {
					Log.info(Log.FAC_SEARCH, "EnumeratedNameList.exists: we have a matching child to {0} and the parent enumerator {1} has {2} children.", 
							Component.printURI(childNameComponent), parentName, parentEnumerator.childCount());
				}
				childIndex++;
				if (childIndex == childName.count()) {
					if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
						Log.info(Log.FAC_SEARCH, "EnumeratedNameList.exists: we found the childName we were looking for: {0}", childName);
					return parentEnumerator;
				}
				parentEnumerator.stopEnumerating();
				parentName = new ContentName(parentName, childNameComponent);
				continue;
			} else {
				if (Log.isLoggable(Level.INFO)) {
					Log.info("EnumeratedNameList.exists: the parent enumerator {0} has {1} children but none of them are {2}.", 
							parentName, parentEnumerator.childCount(), Component.printURI(childNameComponent));
				}
				break;
			}
		}
		if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
			Log.info(Log.FAC_SEARCH, "EnumeratedNameList.exists: returning null for search of {0}", childName);
		return null;
	}

	private NewChildrenByThread getNcbt() {
		Long id = Thread.currentThread().getId();
		NewChildrenByThread ncbt = _newChildrenByThread.get(id);
		if (null == ncbt)
			ncbt = _newChildrenByThread.get(0L);	// Thread pool
		return ncbt;
	}

	/**
	 * Enables an EnumeratedNameList to be used directly in a ContentName builder.
	 * @return Gets the ContentName of the prefix being enumerated
	 * @see ContentNameProvider
	 * @see ContentName#builder(org.ccnx.ccn.protocol.ContentName.StringParser, Object[])
	 */
	public ContentName getContentName() {
		return _namePrefix;
	}
}
