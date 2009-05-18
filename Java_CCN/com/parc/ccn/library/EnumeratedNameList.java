package com.parc.ccn.library;

import java.io.IOException;
import java.util.ArrayList;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.query.BasicNameEnumeratorListener;

public class EnumeratedNameList implements BasicNameEnumeratorListener {
	
	private static final long CHILD_WAIT_INTERVAL = 30000;
	
	private ContentName _namePrefix;
	private CCNNameEnumerator _enumerator;
	private ArrayList<ContentName> _children = new ArrayList<ContentName>();
	private ArrayList<ContentName> _newChildren = new ArrayList<ContentName>();
	private Object _childLock = new Object();
	
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
	
	public void stopEnumerating() {
		_enumerator.cancelPrefix(_namePrefix);
	}
	
	/**
	 * Blocks and waits for data, but grabs the new data for processing
	 * (thus removing it from every other listener), in effect handing the
	 * new children to the first consumer to wake up and makes the other
	 * ones go around again.
	 * @return
	 */
	public ArrayList<byte []> getNewData() {
		ArrayList<byte []> childArray = null;
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
				childArray = buildComponentArray(_newChildren);
				_newChildren = null;
			}
		}
		return childArray;
	}
	
	public ArrayList<byte []> getChildren() {
		if (!hasChildren())
			return null;
		return buildComponentArray(_children);
	}
	
	public boolean hasNewData() {
		return ((null != _newChildren) && (_newChildren.size() > 0));
	}
	
	public boolean hasChildren() {
		return ((null != _children) && (_children.size() > 0));
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
	
	private ArrayList<byte []> buildComponentArray(ArrayList<ContentName> results) {
		if ((null == results) || (0 == results.size()))
			return null;
		
		ArrayList<byte []> components = new ArrayList<byte []>();
		for (ContentName cn : results) {
			if (cn.count() > 1) {
				Library.logger().info("Unexpected: name enumerator returned us a name with more than one component! : " + cn);
			}
			components.add(cn.component(0));
		}
		return components;
	}

	/**
	 * The name enumerator should hand back a list of single-component names.
	 */
	public int handleNameEnumerator(ContentName prefix,
								    ArrayList<ContentName> names) {
		Library.logger().info(names.size() + " new name enumeration results: our prefix: " + _namePrefix + " returned prefix: " + prefix);
		if (!prefix.equals(_namePrefix)) {
			Library.logger().warning("Returned data doesn't match requested prefix!");
		}
		// the name enumerator hands off names to us, we own it now
		synchronized (_childLock) {
			_children.addAll(names);
			_newChildren = names;
			_childLock.notifyAll();
		}
		return 0;
	}

	public ContentName getLatestVersionChildName(ContentName name) {
		// TODO Auto-generated method stub
		return null;
	}

	public static ContentName getLatestVersionName(ContentName name) {
		// TODO Auto-generated method stub
		return null;
	}

	public boolean exists(ContentName aclName) {
		// TODO Auto-generated method stub
		return false;
	}

}
