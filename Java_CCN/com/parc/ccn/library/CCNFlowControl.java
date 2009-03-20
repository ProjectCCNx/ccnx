package com.parc.ccn.library;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;
import java.util.TreeMap;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.Signature;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.util.InterestTable;
import com.parc.ccn.data.util.InterestTable.Entry;

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
		_filteredNames.add(name);
		_library.registerFilter(name, this);
	}
	
	public CCNFlowControl(String name, CCNLibrary library) 
				throws MalformedContentNameStringException {
		this(ContentName.fromNative(name), library);
	}
	
	public ContentObject put(ContentName name, 
			SignedInfo signedInfo,
			byte[] content,
			Signature signature) throws IOException {
		byte [] contentHold = new byte[content.length];
		System.arraycopy(content, 0, contentHold, 0, content.length);
		ContentObject co = new ContentObject(name, signedInfo, contentHold, signature);
		return put(co);
	}
	
	public ContentObject put(ContentObject co) throws IOException {
		if (_flowControlEnabled) {
			Entry<UnmatchedInterest> match = null;
			synchronized (this) {
				match = _unmatchedInterests.removeMatch(co);
				if (match == null) {
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
					_holdingArea.remove(co.name());
					try {
						_library.put(co);
						if (_shutdownWait && _holdingArea.size() == 0) {
							synchronized (_holdingArea) {
								_holdingArea.notify();
							}
						}
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					
				} else {
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
				if ((interest.orderPreference() & (Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME))
						== (Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME)) { //last
					if (interest.matches(result)) {
						if (bestMatch == null)
							bestMatch = result;
						else {
							if (name.compareTo(bestMatch.name()) < 0) {
								bestMatch = result;
							}
						}
					}
				} else if ((interest.orderPreference() & (Interest.ORDER_PREFERENCE_LEFT | Interest.ORDER_PREFERENCE_ORDER_NAME))
						== (Interest.ORDER_PREFERENCE_LEFT | Interest.ORDER_PREFERENCE_ORDER_NAME)) { //next
					if (interest.matches(result))
						return result;
				}
			} else
				if (interest.matches(result))
					return result;
		}
		return bestMatch;
	}
	
	public void waitForPutDrain() throws IOException {
		if (_holdingArea.size() > 0) {
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
			
			if (_holdingArea.size() > 0) {
				throw new IOException("Put(s) with no matching interests");
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
}
