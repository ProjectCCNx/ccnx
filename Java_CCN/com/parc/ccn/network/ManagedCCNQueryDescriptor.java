package com.parc.ccn.network;

import java.util.ArrayList;
import java.util.Random;

import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener;

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
	
	public ManagedCCNQueryDescriptor(
			CompleteName name,
			long TTL,
			CCNQueryListener listener) {
		super(name, TTL, newIdentifier());
		_listener = listener;
	}

	public ManagedCCNQueryDescriptor(
			  CompleteName name,
			  long TTL, Object initialIdentifier,
			  CCNQueryListener listener) {
		super(name, TTL, newIdentifier());
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
	 * Add a new identifier that is a random # to each
	 * query so we can track it.
	 **/
	protected static Object newIdentifier() {
		// Don't need cryptographic random numbers.
		return new Long(new Random().nextLong());
	}

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_listener == null) ? 0 : _listener.hashCode());
		result = PRIME * result + ((_queryIdentifiers == null) ? 0 : _queryIdentifiers.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final ManagedCCNQueryDescriptor other = (ManagedCCNQueryDescriptor) obj;
		if (_listener == null) {
			if (other._listener != null)
				return false;
		} else if (!_listener.equals(other._listener))
			return false;
		if (_queryIdentifiers == null) {
			if (other._queryIdentifiers != null)
				return false;
		} else if (!_queryIdentifiers.equals(other._queryIdentifiers))
			return false;
		return true;
	}
}
