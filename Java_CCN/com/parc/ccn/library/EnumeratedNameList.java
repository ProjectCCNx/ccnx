package com.parc.ccn.library;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;

import org.bouncycastle.util.Arrays;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.query.BasicNameEnumeratorListener;
import com.parc.ccn.library.profiles.VersioningProfile;

public class EnumeratedNameList implements BasicNameEnumeratorListener {
	
	protected static final long CHILD_WAIT_INTERVAL = 30000;
	
	protected ContentName _namePrefix;
	protected CCNNameEnumerator _enumerator;
	protected BasicNameEnumeratorListener callback;
	// make these contain something other than content names when the enumerator has better data types
	protected SortedSet<ContentName> _children = new TreeSet<ContentName>();
	protected SortedSet<ContentName> _newChildren = new TreeSet<ContentName>();
	protected Object _childLock = new Object();
	
	/**
	 * Creates an EnumerateNameList object
	 * <p>
	 * The namePrefix argument is a content name object that refers to ??
	 * The library CCNLibrary argument is the current CCN environment where
	 * the names are being iterated. 
	 *<p>
	 * this constructor creates a new CCN Library if the one passed in is null
	 * Registers the namePrefix on the CCN network 
	 *  
	 * @param  CCNLibrary  an absolute URL giving the base location of the image
	 * @param  namePrefix the location of the image, relative to the url argument
	 * @return      
	 * @see
	 */

	public EnumeratedNameList(ContentName namePrefix, CCNLibrary library) throws IOException {
		if (null == namePrefix) {
			throw new IllegalArgumentException("namePrefix cannot be null!");
		}
		if (null == library) {
			try {
				library = CCNLibrary.open();
			} catch (ConfigurationException e) {
				throw new IOException("ConfigurationException attempting to open a library: " + e.getMessage());
			}
		}
		_namePrefix = namePrefix;
		_enumerator = new CCNNameEnumerator(namePrefix, library, this);
		_enumerator.registerPrefix(namePrefix);
	}
	
	public ContentName getName() { return _namePrefix; }
	
	/* StopEnumerating
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
	 * @return returns the array of single-component content name childrent that are new to us
	 */
	public SortedSet<ContentName> getNewData() {
		SortedSet<ContentName> childArray = null;
		synchronized(_childLock) {
			while (null == _newChildren) {
				try {
					_childLock.wait(CHILD_WAIT_INTERVAL);
				} catch (InterruptedException e) {
				}
				Library.logger().info("Waiting for new data on prefix: " + _namePrefix + " got " + ((null == _newChildren) ? 0 : _newChildren.size())
						+ ".");
			}
			if (null != _newChildren) {
				childArray = _newChildren;
				_newChildren = null;
			}
		}
		return childArray;
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
	
	public boolean hasNewData() {
		return ((null != _newChildren) && (_newChildren.size() > 0));
	}
	
	public boolean hasChildren() {
		return ((null != _children) && (_children.size() > 0));
	}
	
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
	 * Waits until there is any data at all.
	 * @return
	 */
	public void waitForData() {
		if ((null != _children) && (_children.size() > 0))
			return;
		synchronized(_childLock) {
			while ((null == _children) || (_children.size() == 0)) {
				try {
					_childLock.wait(CHILD_WAIT_INTERVAL);
				} catch (InterruptedException e) {
				}
				Library.logger().info("Waiting for data on prefix: " + _namePrefix + " got " + ((null == _newChildren) ? 0 : _newChildren.size())
						+ ".");
			}
		}
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
		
		Library.logger().info(names.size() + " new name enumeration results: our prefix: " + _namePrefix + " returned prefix: " + prefix);
		if (!prefix.equals(_namePrefix)) {
			Library.logger().warning("Returned data doesn't match requested prefix!");
		}
		Library.logger().info("Handling Name Iteration " + prefix +" ");
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
				Library.logger().info("New children found: " + thisRoundNew.size() + " total children " + _children.size());
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
	 * 
	 * 
	 * 
	 * 
	 * */
	public ContentName getLatestVersionChildName() {
		// of the available names in _children that are version components,
		// find the latest one (version-wise)
		// names are sorted, so the last one that is a version should be the latest version
		// ListIterator previous doesn't work unless you've somehow gotten it to point at the end...
		ContentName theName = null;
		ContentName latestName = null;
		Timestamp latestTimestamp = null;
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
					Timestamp thisTimestamp = VersioningProfile.getVersionComponentAsTimestamp(theName.component(0));
					if (thisTimestamp.after(latestTimestamp)) {
						latestName = theName;
						latestTimestamp = thisTimestamp;
					}
				}
			}
		}
		return latestName;
	}
	
	public Timestamp getLatestVersionChildTime() {
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
	 * @param library
	 * @return
	 * @throws IOException
	 */
	public static ContentName getLatestVersionName(ContentName name, CCNLibrary library) throws IOException {
		EnumeratedNameList enl = new EnumeratedNameList(name, library);
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
	public static EnumeratedNameList exists(ContentName childName, ContentName prefixKnownToExist, CCNLibrary library) throws IOException {
		if ((null == prefixKnownToExist) || (null == childName) || (!prefixKnownToExist.isPrefixOf(childName))) {
			Library.logger().info("Child " + childName + " must be prefixed by name " + prefixKnownToExist);
			throw new IllegalArgumentException("Child " + childName + " must be prefixed by name " + prefixKnownToExist);
		}
		if (childName.count() == prefixKnownToExist.count()) {
			// we're already there
			return new EnumeratedNameList(childName, library);
		}
		ContentName parentName = prefixKnownToExist;
		int childIndex = parentName.count();
		EnumeratedNameList parentEnumerator = null;
		while (childIndex < childName.count()) {
			parentEnumerator = new EnumeratedNameList(parentName, library);
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
