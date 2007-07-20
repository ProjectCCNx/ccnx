package com.parc.ccn.network;

import java.util.ArrayList;
import java.util.Random;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.data.security.ContentAuthenticator;

/**
 * Amalgamates a lot of query descriptors describing
 * the same query into one
 * by keeping a list of identifiers together with only
 * one copy of the query description data.
 * Make a new unique uber-identifier to help track
 * what set this is...
 * @author smetters
 *
 */
public class ManagedCCNQueryDescriptor extends CCNQueryDescriptor {

	/**
	 * Use ArrayList instead of Set as Set is unhappy if it
	 * contains mutable objects, and we don't know what kinds
	 * of things subclasses will use as query identifiers.
	 * 
	 * Remember the listener so if we add additional gets later
	 * we can forward them.
	 */
	protected ArrayList<Object> _queryIdentifiers = new ArrayList<Object>();
	protected CCNQueryListener _listener = null;
	
	public ManagedCCNQueryDescriptor(ContentName name,
			  ContentAuthenticator authenticator,
			  CCNQueryListener.CCNQueryType type,
			  long TTL,
			  CCNQueryListener listener) {
		super(name, authenticator, type, TTL, newIdentifier());
		_listener = listener;
	}

	public ManagedCCNQueryDescriptor(ContentName name,
			  ContentAuthenticator authenticator,
			  CCNQueryListener.CCNQueryType type,
			  long TTL, Object initialIdentifier,
			  CCNQueryListener listener) {
		super(name, authenticator, type, TTL, newIdentifier());
		if (null != initialIdentifier)
			_queryIdentifiers.add(initialIdentifier);
		_listener = listener;
	}
	
	public ManagedCCNQueryDescriptor(CCNQueryDescriptor descriptor, CCNQueryListener listener) {
		super(descriptor);
		this.setQueryIdentifier(newIdentifier());
		if (null != descriptor.queryIdentifier())
			_queryIdentifiers.add(descriptor.queryIdentifier());
		_listener = listener;
	}
	
	public ArrayList<Object> getIdentifiers() { return _queryIdentifiers; }

	public void addIdentifier(Object identifier) {
		if (!_queryIdentifiers.contains(identifier)) 
			_queryIdentifiers.add(identifier);
	}
	
	/**
	 * How many queries have we amalgamated?
	 * @return
	 */
	public int queryCount() { return _queryIdentifiers.size(); }

	
	public CCNQueryListener listener() {
		return _listener;
	}
	
	public Object identifier(int i) {
		return _queryIdentifiers.get(i);
	}
	
	public CCNQueryDescriptor descriptor(int i) {
		CCNQueryDescriptor descriptor = new CCNQueryDescriptor(this);
		descriptor.setQueryIdentifier(_queryIdentifiers.get(i));
		return descriptor;
	}
	
	/**
	 * DKS: TODO make equals
	 */
	
	/**
	 * Add a new identifier that is a random # to each
	 * query so we can track it.
	 **/
	protected static Object newIdentifier() {
		// Don't need cryptographic random numbers.
		return new Long(new Random().nextLong());
	}
}
