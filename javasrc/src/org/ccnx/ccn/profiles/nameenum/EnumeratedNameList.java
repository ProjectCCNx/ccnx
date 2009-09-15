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

package org.ccnx.ccn.profiles.nameenum;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;

import org.bouncycastle.util.Arrays;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;

/**
 * Wrapping class for CCNNameEnumerator.  This class allows applications to wrap the methods of
 * CCNNameEnumerator and call blocking functions (with optional timeouts) to wait for name
 * enumeration responses for a given name prefix. 
 * 
 * 
 * @see CCNNameEnumerator
 * @see BasicNameEnumeratorListener
 */

public class EnumeratedNameList implements BasicNameEnumeratorListener {
	
	protected static final long CHILD_WAIT_INTERVAL = 1000;
	
	protected ContentName _namePrefix;
	protected CCNNameEnumerator _enumerator;
	protected BasicNameEnumeratorListener callback;
	// make these contain something other than content names when the enumerator has better data types
	protected SortedSet<ContentName> _children = new TreeSet<ContentName>();
	protected SortedSet<ContentName> _newChildren = null;
	protected Object _childLock = new Object();
	protected CCNTime _lastUpdate = null;
	
	/**
	 * Creates an EnumerateNameList object
	 * <p>
	 * The namePrefix argument is a content name object that refers to ??
	 * The handle CCNHandle argument is the current CCN environment where
	 * the names are being iterated. 
	 *<p>
	 * this constructor creates a new CCN Library if the one passed in is null
	 * Registers the namePrefix on the CCN network 
	 *  
	 * @param  CCNHandle  an absolute URL giving the base location of the image
	 * @param  namePrefix the location of the image, relative to the url argument
	 * @return      
	 * @see
	 */

	public EnumeratedNameList(ContentName namePrefix, CCNHandle handle) throws IOException {
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
		_enumerator = new CCNNameEnumerator(namePrefix, handle, this);
	}
	
	public ContentName getName() { return _namePrefix; }
	
	/** StopEnumerating
	 * <p>
	 * Sends a cancel interest on the namePrefix assigned in the 
	 * constructor. Cancels the enumeration on that prefix
	 * 
	 * @return
	 * */
	public void stopEnumerating() {
		_enumerator.cancelPrefix(_namePrefix);
	}
	
	/**
	 * Blocks and waits for data, but grabs the new data for processing
	 * (thus removing it from every other listener), in effect handing the
	 * new children to the first consumer to wake up and makes the other
	 * ones go around again.
	 * @timeout maximum amount of time to wait, 0 to wait forever.
	 * @return returns the array of single-component content name children that are new to us,
	 *    or null if we reached the timeout before new data arrived
	 */
	public SortedSet<ContentName> getNewData(long timeout) {
		SortedSet<ContentName> childArray = null;
		synchronized(_childLock) { // reentrant?
			while ((null == _children) || _children.size() == 0) {
				waitForNewData(timeout);
				if (timeout != SystemConfiguration.TIMEOUT_FOREVER)
					break;
			}
			Log.info("Waiting for new data on prefix: " + _namePrefix + " got " + ((null == _newChildren) ? 0 : _newChildren.size())
					+ ".");

			if (null != _newChildren) {
				childArray = _newChildren;
				_newChildren = null;
			}
		}
		return childArray;
	}
	
	/**
	 * Block and wait as long as it takes for new data to appear. See {@link #getNewData(long)}.
	 * @return
	 */
	public SortedSet<ContentName> getNewData() {
		return getNewData(SystemConfiguration.TIMEOUT_FOREVER);
	}
	
	/**
	 * Returns single-component ContentName objects containing the name components of the children.
	 * @return
	 */
	public SortedSet<ContentName> getChildren() {
		if (!hasChildren())
			return null;
		return _children;
	}
	
	/**
	 * Returns true if the prefix has new names that have not been handled by the calling application.
	 * 
	 * @return boolean
	 */
	
	public boolean hasNewData() {
		return ((null != _newChildren) && (_newChildren.size() > 0));
	}
	
	/**
	 * Returns true if the prefix has names stored from received enumeration responses.
	 * 
	 * @return boolean
	 */
	
	public boolean hasChildren() {
		return ((null != _children) && (_children.size() > 0));
	}
	
	/**
	 * Returns true if the prefix has a child matching the given name component.
	 * 
	 * @param childComponent Name component to check for in the stored child names.
	 * 
	 * @return boolean
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
	 * Returns whether a group has a child
	 * using the easy to read group name
	 * <p>
	 * 
	 * @param groupFriendlyName ??
	 * @return child
	 * */
	public boolean hasChild(String childName) {
		return hasChild(ContentName.componentParseNative(childName));
	}
	
	/**
	 * Wait for new name enumeration data to arrive, whether it actually signals children or not.
	 */
	public void waitForNewData(long timeout) {
		synchronized(_childLock) {
			long timeRemaining = timeout;
			CCNTime lastUpdate = _lastUpdate;
			while (((null == _lastUpdate) || ((null != lastUpdate) && !_lastUpdate.after(lastUpdate))) && 
				   ((timeout == SystemConfiguration.TIMEOUT_FOREVER) || (timeRemaining > 0))) {
				try {
					_childLock.wait((timeout != SystemConfiguration.TIMEOUT_FOREVER) ? Math.min(timeRemaining, CHILD_WAIT_INTERVAL) : CHILD_WAIT_INTERVAL);
					if (timeout != SystemConfiguration.TIMEOUT_FOREVER)
						timeRemaining -= CHILD_WAIT_INTERVAL;
				} catch (InterruptedException e) {
				}
				Log.info("Waiting for new data on prefix: {0}, updated {1}, our update {2}, now have " + 
						((null == _children) ? 0 : _children.size()), _namePrefix + " new " + 
						((null == _newChildren) ? 0 : _newChildren.size()) + ".", _lastUpdate, lastUpdate);
			}
		}
	}
	
	public void waitForNewData() {
		waitForNewData(SystemConfiguration.TIMEOUT_FOREVER);
	}

	/**
	 * Waits until there is any data at all. Right now, waits for actual
	 * children, not just a NE response. That means it could block
	 * forever if no children exist in a repo.
	 * @timeout maximum amount of time to wait, if 0, waits forever
	 * @return
	 */
	public void waitForData(long timeout) {
		while ((null == _children) || _children.size() == 0) {
			waitForNewData(timeout);
			if (timeout != SystemConfiguration.TIMEOUT_FOREVER)
				break;
		}
	}
	
	/**
	 * Wait (block) for data to arrive, possibly forever. See {@link #waitForData(long)}.
	 */
	public void waitForData() {
		waitForData(SystemConfiguration.TIMEOUT_FOREVER);
	}

	/**
	 * The name enumerator should hand back a list of 
	 * single-component names.
	 * 
	 * @param prefix Prefix where you are starting
	 * @param names
	 * 
	 * @return
	 */
	
	public int handleNameEnumerator(ContentName prefix,
								    ArrayList<ContentName> names) {
		
		Log.info(names.size() + " new name enumeration results: our prefix: " + _namePrefix + " returned prefix: " + prefix);
		if (!prefix.equals(_namePrefix)) {
			Log.warning("Returned data doesn't match requested prefix!");
		}
		Log.info("Handling Name Iteration " + prefix +" ");
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
				if (null != _newChildren) {
					_newChildren.addAll(thisRoundNew);
				} else {
					_newChildren = thisRoundNew;
				}
				_children.addAll(thisRoundNew);
				_lastUpdate = new CCNTime();
				Log.info("New children found: at {0} " + thisRoundNew.size() + " total children " + _children.size(), _lastUpdate);
				processNewChildren(thisRoundNew);
				_childLock.notifyAll();
			}
		}
		return 0;
	}
	
	/**
	 * Method to allow subclasses to do post-processing on incoming names
	 * before handing them to customers.
	 * DKS TODO -- make sure we only signal on new new children
	 * Note that the set handed in here is not the set that will be handed
	 * out; only the name objects are the same.
	 * 
	 * @param newChildren 
	 */
	protected void processNewChildren(SortedSet<ContentName> newChildren) {
		// default -- do nothing.
	}

	/**
	 * Returns the latest version name of the child version components that were returned through enumeration
	 * 
	 * @return ContentName The latest version component
	 * 
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
		// DKS TODO these are sorted -- we just need to iterate through them in reverse order. Having
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
	 * Returns the latest version as a CCNTime object.
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
	 * Returns the complete name of the 
	 * latest version of content with the prefix name.
	 * 
	 * @param name
	 * @param handle
	 * @return
	 * @throws IOException
	 */
	public static ContentName getLatestVersionName(ContentName name, CCNHandle handle) throws IOException {
		EnumeratedNameList enl = new EnumeratedNameList(name, handle);
		enl.waitForData();
		ContentName childLatestVersion = enl.getLatestVersionChildName();
		enl.stopEnumerating();
		if (null != childLatestVersion) {
			return new ContentName(name, childLatestVersion.component(0));
		}
		return null;
	}

	/**
	 * Iterates down namespace. If the name doesn't exist in a limited time iteration,
	 * return null; otherwise return the last enumerator that enumerates the parent of
	 * the desired.
	 * DKS -- may time out before it discovers child...
	 * We can modify the name enumeration protocol to return empty responses if query
	 * for unknown name, but that adds semantic complications.
	 * @param aclName
	 * @param prefixKnownToExist
	 * @return
	 * @throws IOException 
	 */
	public static EnumeratedNameList exists(ContentName childName, ContentName prefixKnownToExist, CCNHandle handle) throws IOException {
		if ((null == prefixKnownToExist) || (null == childName) || (!prefixKnownToExist.isPrefixOf(childName))) {
			Log.info("Child " + childName + " must be prefixed by name " + prefixKnownToExist);
			throw new IllegalArgumentException("Child " + childName + " must be prefixed by name " + prefixKnownToExist);
		}
		if (childName.count() == prefixKnownToExist.count()) {
			// we're already there
			return new EnumeratedNameList(childName, handle);
		}
		ContentName parentName = prefixKnownToExist;
		int childIndex = parentName.count();
		EnumeratedNameList parentEnumerator = null;
		while (childIndex < childName.count()) {
			parentEnumerator = new EnumeratedNameList(parentName, handle);
			parentEnumerator.waitForData(); // we're only getting the first round here... 
			// could wrap this bit in a loop if want to try harder
			if (parentEnumerator.hasChild(childName.component(childIndex))) {
				childIndex++;
				if (childIndex == childName.count()) {
					return parentEnumerator;
				}
				parentEnumerator.stopEnumerating();
				parentName = new ContentName(parentName, childName.component(childIndex));
				continue;
			} else {
				break;
			}
		}
		return null;
	}
}
