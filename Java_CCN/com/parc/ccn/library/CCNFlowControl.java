package com.parc.ccn.library;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeMap;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.util.InterestTable;
import com.parc.ccn.data.util.InterestTable.Entry;

/**
 * Implements rudimentary flow control by matching content objects
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
 * The buffer emptying policy in "afterPutAction can be overridden by 
 * subclasses to implement a different way of draining the buffer. This is used 
 * by the repo client to allow objects to remain in the buffer until they are 
 * acked.
 * 
 * @author rasmusse
 *
 */

public class CCNFlowControl implements CCNFilterListener {
	
	protected CCNLibrary _library = null;
	
	// Temporarily default to very high timeout so that puts have a good
	// chance of going through.  We actually may want to keep this.
	protected static final int MAX_TIMEOUT = 10000;
	protected static final int HIGHWATER_DEFAULT = 128 + 1;
	protected static final int INTEREST_HIGHWATER_DEFAULT = 40;
	protected int _timeout = MAX_TIMEOUT;
	protected int _highwater = HIGHWATER_DEFAULT;
	
	protected static final int PURGE = 2000;
	
	protected TreeMap<ContentName, ContentObject> _holdingArea = new TreeMap<ContentName, ContentObject>();
	protected InterestTable<UnmatchedInterest> _unmatchedInterests = new InterestTable<UnmatchedInterest>();
	protected HashSet<ContentName> _filteredNames = new HashSet<ContentName>();

	private class UnmatchedInterest {
		long timestamp = new Date().getTime();
	}
	
	private boolean _flowControlEnabled = true;
	
	/**
	 * Enabled flow control constructor
	 * @param name
	 * @param library
	 */
	public CCNFlowControl(ContentName name, CCNLibrary library) throws IOException {
		this(library);
		if (name != null) {
			Library.logger().finest("adding namespace: " + name);
			// don't call full addNameSpace, in order to allow subclasses to 
			// override. just do minimal part
			_filteredNames.add(name);
			_library.registerFilter(name, this);
		}
		_unmatchedInterests.setHighWater(INTEREST_HIGHWATER_DEFAULT);
	}
	
	public CCNFlowControl(String name, CCNLibrary library) 
				throws MalformedContentNameStringException, IOException {
		this(ContentName.fromNative(name), library);
	}
	
	public CCNFlowControl(CCNLibrary library) throws IOException {
		if (null == library) {
			// Could make this create a library.
			try {
				library = CCNLibrary.open();
			} catch (ConfigurationException e) {
				Library.logger().info("Got ConfigurationException attempting to create a library. Rethrowing it as an IOException. Message: " + e.getMessage());
				throw new IOException("ConfigurationException creating a library: " + e.getMessage());
			}
		}
		_library = library;
		_unmatchedInterests.setHighWater(INTEREST_HIGHWATER_DEFAULT);
	}
	
	/**
	 * Add a new namespace to the controller
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
				Library.logger().info("addNameSpace: not adding name: " + name + " already monitoring prefix: " + filteredName);
				return;		// Already part of filter
			}
			if (name.isPrefixOf(filteredName)) {
				_library.unregisterFilter(filteredName, this);
				it.remove();
			}
		}
		Library.logger().info("addNameSpace: adding namespace: " + name);
		_filteredNames.add(name);
		_library.registerFilter(name, this);
	}
	
	public void addNameSpace(String name) throws MalformedContentNameStringException, IOException {
		addNameSpace(ContentName.fromNative(name));
	}
	
	/**
	 * For now we don't have anyway to remove a partial namespace from
	 * flow control (would we want to do that?) so for now we only allow
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
				_library.unregisterFilter(filteredName, this);
				it.remove();
				Library.logger().finest("removing namespace: " + name);
				break;
			}
		}
	}
	
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
	 * Add content objects to this flow controller
	 * @param cos
	 * @throws IOException
	 */
	public void put(ArrayList<ContentObject> cos) throws IOException {
		for (ContentObject co : cos) {
			put(co);
		}
	}
	
	/**
	 * Add content objects to this flow controller
	 * @param cos
	 * @throws IOException
	 */
	public void put(ContentObject [] cos) throws IOException {
		for (ContentObject co : cos) {
			put(co);
		}
	}

	/**
	 * Add namespace and content at the same time
	 * @param co
	 * @throws IOException 
	 * @throws IOException
	 */
	public void put(ContentName name, ArrayList<ContentObject> cos) throws IOException {
		addNameSpace(name);
		put(cos);
	}
	
	public ContentObject put(ContentName name, ContentObject co) throws IOException {
		addNameSpace(name);
		return put(co);
	}
	
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
	
	private ContentObject waitForMatch(ContentObject co) throws IOException {
		if (_flowControlEnabled) {
			synchronized (_holdingArea) {
				Entry<UnmatchedInterest> match = null;
				Library.logger().finest("Holding " + co.name());
				_holdingArea.put(co.name(), co);
				match = _unmatchedInterests.removeMatch(co);
				if (match != null) {
					Library.logger().finest("Found pending matching interest for " + co.name() + ", putting to network.");
					_library.put(co);
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
							Library.logger().finest("Waiting for drain");
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
			_library.put(co);
		return co;
	}
	
	public int handleInterests(ArrayList<Interest> interests) {
		synchronized (_holdingArea) {
			for (Interest interest : interests) {
				Library.logger().info("Flow controller: got interest: " + interest);
				ContentObject co = getBestMatch(interest);
				if (co != null) {
					Library.logger().finest("Found content " + co.name() + " matching interest: " + interest);
					try {
						_library.put(co);
						afterPutAction(co);
					} catch (IOException e) {
						Library.logger().warning("IOException in handleInterests: " + e.getClass().getName() + ": " + e.getMessage());
						Library.warningStackTrace(e);
					}
					
				} else {
					Library.logger().finest("No content matching pending interest: " + interest + ", holding.");
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
		Library.logger().finest("Looking for best match to " + interest + " among " + set.size() + " options.");
		for (ContentName name : set) {
			ContentObject result = _holdingArea.get(name);
			/*
			 * Since "ORDER_PREFERENCE_LEFT" is actually 0, we have to do the test in this order so that left and
			 * right don't look the same
			 */
			if (interest.orderPreference()  != null) {
				if (interest.matches(result)) {
					if (bestMatch == null)
						bestMatch = result;
					if ((interest.orderPreference() & (Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME))
							== (Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME)) { //last
						if (name.compareTo(bestMatch.name()) > 0) {
							bestMatch = result;
						}
					} else if ((interest.orderPreference() & (Interest.ORDER_PREFERENCE_LEFT | Interest.ORDER_PREFERENCE_ORDER_NAME))
							== (Interest.ORDER_PREFERENCE_LEFT | Interest.ORDER_PREFERENCE_ORDER_NAME)) { //next
						if (name.compareTo(bestMatch.name()) < 0) {
							bestMatch = result;
						}				
					}
				}
			} else
				if (interest.matches(result))
					return result;
		}
		return bestMatch;
	}
	
	public void beforeClose() throws IOException {
		// default -- do nothing.
	}

	public void afterClose() throws IOException {
		waitForPutDrain();
	}
	
	public void waitForPutDrain() throws IOException {
		synchronized (_holdingArea) {
			int startSize = _holdingArea.size();
			while (_holdingArea.size() > 0) {
				boolean _interrupted;
				do {
					_interrupted = false;
					try {
						_holdingArea.wait(_timeout);
					} catch (InterruptedException ie) {
						_interrupted = true;
					}
				} while (_interrupted);
				
				if (_holdingArea.size() == startSize) {
					for(ContentName co : _holdingArea.keySet()) {
						Library.logger().warning("FlowController: still holding: " + co.toString());
					}
					throw new IOException("Put(s) with no matching interests - size is " + _holdingArea.size());
				}
				startSize = _holdingArea.size();
			}
		}
	}
	
	public void setTimeout(int timeout) {
		_timeout = timeout;
	}
	
	public int getTimeout() {
		return _timeout;
	}
	
	/**
	 * Shutdown but wait for puts to drain first
	 * @throws IOException 
	 */
	public void shutdown() throws IOException {
		waitForPutDrain();
		_library.getNetworkManager().shutdown();
	}
	
	public CCNLibrary getLibrary() {
		return _library;
	}
	
	public void clearUnmatchedInterests() {
		Library.logger().info("Clearing " + _unmatchedInterests.size() + " unmatched interests.");
		_unmatchedInterests.clear();
	}
	
	public void enable() {
		_flowControlEnabled = true;
	}
	
	public void setHighwater(int value) {
		_highwater = value;
	}
	
	public void setInterestHighwater(int value) {
		_unmatchedInterests.setHighWater(value);
	}
	
	/**
	 * Warning - calling this risks packet drops. It should only
	 * be used for tests or other special circumstances in which
	 * you "know what you are doing".
	 */
	public void disable() {
		removeNameSpace(null, true);
		_flowControlEnabled = false;
	}
}
