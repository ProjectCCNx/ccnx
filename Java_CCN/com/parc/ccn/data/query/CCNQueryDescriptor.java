package com.parc.ccn.data.query;

import java.util.Random;

import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;

/**
 * This base class just combines all of the information
 * that went into the query. Subclasses might add specific
 * identifiers to allow easier cancellation of queries.
 * 
 * @author smetters
 *
 */
public class CCNQueryDescriptor {
	
	// Interests embody all the legal specializations
	// of queries recognized at the CCN level.
	
	/**
	 * All queries are recursive. Leave these here
	 * for a bit so that we can strip accidentally-included ones.
	 * DKS TODO remove.
	 */
	public static final String RECURSIVE_POSTFIX = "*";
	public static final byte [] RECURSIVE_POSTFIX_BYTES = 
		ContentName.componentParse(RECURSIVE_POSTFIX);

	/*
	 * First pass at handling identifiers for queries.
	 */
	protected Object _queryIdentifier = null;
	protected Interest _interest = null;
	protected boolean _recursive = true;
	protected CCNQueryListener _listener = null;
	
	public CCNQueryDescriptor(Interest interest, CCNQueryListener listener) {
		this(interest, null, listener);
	}
	
	public CCNQueryDescriptor(Interest interest,
			  				  Object identifier,
			  				  CCNQueryListener listener) {
		_interest = interest;
		_listener = listener;
		if (null != identifier)
			_queryIdentifier = identifier;
		else 
			_queryIdentifier = new Integer(new Random().nextInt());
	}
	
	public CCNQueryDescriptor(CCNQueryDescriptor descriptor) {
		this(descriptor.interest(), descriptor.queryIdentifier(), descriptor.queryListener());
	}
	
	public void setQueryIdentifier(Object identifier) {
		_queryIdentifier = identifier;
	}
	
	public Object queryIdentifier() {
		return _queryIdentifier;
	}

    public boolean matchesQuery(CompleteName queryName) {
    	// All queries recursive now.
    	
    	// Handle prefix matching
    	// If the query is recursive, see if the remainder of the name matches
     	if (recursive()) {
    		if (!name().equals(queryName.name(), name().count()-1)) {
    			return false;
    		}
    	} else if (!name().equals(queryName.name())) {
    		return false;
    	}
     	
    	// Now take into account other specializations
    	// on the query, currently only publisherID.
    	if (null != interest().publisherID()) {
    		if (!interest().publisherID().equals(queryName.authenticator().publisherID())) {
     			return false;
     		}
    	}
    	return true;
    }
    
	public ContentName name() { return _interest.name(); }
	public Interest interest() { return _interest; }
	public boolean recursive() { return _recursive; }
	public CCNQueryListener queryListener() { return _listener; }

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_listener == null) ? 0 : _listener.hashCode());
		result = PRIME * result + ((_interest == null) ? 0 : _interest.hashCode());
		result = PRIME * result + ((_queryIdentifier == null) ? 0 : _queryIdentifier.hashCode());
		result = PRIME * result + (_recursive ? 1231 : 1237);
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
		final CCNQueryDescriptor other = (CCNQueryDescriptor) obj;
		if (_listener == null) {
			if (other._listener != null)
				return false;
		} else if (!_listener.equals(other._listener))
			return false;
		if (_interest == null) {
			if (other._interest != null)
				return false;
		} else if (!_interest.equals(other._interest))
			return false;
		if (_queryIdentifier == null) {
			if (other._queryIdentifier != null)
				return false;
		} else if (!_queryIdentifier.equals(other._queryIdentifier))
			return false;
		if (_recursive != other._recursive)
			return false;
		return true;
	}
}
