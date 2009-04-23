package com.parc.ccn.library;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeMap;

import com.parc.ccn.Library;
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
 * @author rasmusse
 *
 */

public class CCNFlowControl implements CCNFilterListener {
	
	protected CCNLibrary _library = null;
	
	protected static final int MAX_TIMEOUT = 2000;
	protected int _timeout = MAX_TIMEOUT;
	
	protected TreeMap<ContentName, ContentObject> _holdingArea = new TreeMap<ContentName, ContentObject>();
	protected InterestTable<UnmatchedInterest> _unmatchedInterests = new InterestTable<UnmatchedInterest>();
	protected ArrayList<ContentName> _filteredNames = new ArrayList<ContentName>();

	private class UnmatchedInterest {
		long timestamp = new Date().getTime();
	}
	
	private boolean _shutdownWait = false;
	private boolean _flowControlEnabled = true;
	
	/**
	 * Enabled flow control constructor
	 * @param name
	 * @param library
	 */
	public CCNFlowControl(ContentName name, CCNLibrary library) {
		_library = library;
		if (name != null) {
			Library.logger().finest("adding namespace: " + name);
			_filteredNames.add(name);
			_library.registerFilter(name, this);
		}
	}
	
	public CCNFlowControl(String name, CCNLibrary library) 
				throws MalformedContentNameStringException {
		this(ContentName.fromNative(name), library);
	}
	
	public CCNFlowControl(CCNLibrary library) {
		_library = library;
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
			if (filteredName.isPrefixOf(name))
				return;		// Already part of filter
			if (name.isPrefixOf(filteredName)) {
				_library.unregisterFilter(filteredName, this);
				it.remove();
			}
		}
		Library.logger().finest("adding namespace: " + name);
		_filteredNames.add(name);
		_library.registerFilter(name, this);
	}
	
	public void addNameSpace(String name) throws MalformedContentNameStringException {
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
			Entry<UnmatchedInterest> match = null;
			synchronized (this) {
				match = _unmatchedInterests.removeMatch(co);
				if (match == null) {
					Library.logger().finest("Holding " + co.name());
					_holdingArea.put(co.name(), co);
				} else {
					_library.put(co);
				}
			}
		} else
			_library.put(co);
		return co;
	}
	
	public int handleInterests(ArrayList<Interest> interests) {
		for (Interest interest : interests) {
			synchronized (this) {
				ContentObject co = getBestMatch(interest);
				if (co != null) {
					Library.logger().finest("Found content " + co.name() + " matching interest: " + interest.name());
					synchronized (_holdingArea) {
						_holdingArea.remove(co.name());
						try {
							_library.put(co);
							if (_shutdownWait && _holdingArea.size() == 0) {
								_holdingArea.notify();
							}
						} catch (IOException e) {
							Library.logger().warning("IOException in handleInterests: " + e.getClass().getName() + ": " + e.getMessage());
							Library.warningStackTrace(e);
						}
					}
					
				} else {
					Library.logger().finest("No content matching pending interest: " + interest.name() + ", holding.");
					_unmatchedInterests.add(interest, new UnmatchedInterest());
				}
			}
		}
		return interests.size();
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
	
	public void waitForPutDrain() throws IOException {
		int startSize = _holdingArea.size();
		while (_holdingArea.size() > 0) {
			_shutdownWait = true;
			boolean _interrupted;
			do {
				_interrupted = false;
				try {
					synchronized (_holdingArea) {
						_holdingArea.wait(_timeout);
					}
				} catch (InterruptedException ie) {
					_interrupted = true;
				}
			} while (_interrupted);
			
			synchronized (_holdingArea) {
				if (_holdingArea.size() == startSize) {
					throw new IOException("Put(s) with no matching interests");
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
		_unmatchedInterests.clear();
	}
	
	public void enable() {
		_flowControlEnabled = true;
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
