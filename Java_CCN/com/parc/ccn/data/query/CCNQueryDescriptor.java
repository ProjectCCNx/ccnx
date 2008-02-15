package com.parc.ccn.data.query;

import java.util.Arrays;
import java.util.Random;

import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.ContentAuthenticator;

/**
 * This base class just combines all of the information
 * that went into the query. Subclasses might add specific
 * identifiers to allow easier cancellation of queries.
 * 
 * @author smetters
 *
 */
public class CCNQueryDescriptor {
	
	// TODO: DKS: reevaluate query descriptors with new
	// version 2.0 architecture
	
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
	protected CompleteName _name = null;
	protected boolean _recursive = true;
	protected CCNQueryListener _listener = null;
	
	public CCNQueryDescriptor(ContentName name, ContentAuthenticator authenticator, 
							  Object identifier, CCNQueryListener listener) {
		this(new CompleteName(name, authenticator), identifier, listener);
	}
	
	public CCNQueryDescriptor(CompleteName name, CCNQueryListener listener) {
		this(name, null, listener);
	}
	
	public CCNQueryDescriptor(CompleteName name,
			  				  Object identifier,
			  				  CCNQueryListener listener) {
		_name = name;
		_listener = listener;
		if (null != identifier)
			_queryIdentifier = identifier;
		else 
			_queryIdentifier = new Integer(new Random().nextInt());
	}
	
	public CCNQueryDescriptor(CCNQueryDescriptor descriptor) {
		this(descriptor.name(), descriptor.queryIdentifier(), descriptor.queryListener());
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
    		if (!name().name().equals(queryName.name(), name().name().count()-1)) {
    			return false;
    		}
    	} else if (!name().name().equals(queryName.name())) {
    		return false;
    	}
     	
    	// Now take into account other specializations
    	// on the query, most notably publisherID.
    	if ((null != name().authenticator()) && (!name().authenticator().empty())) {
    		if (!name().authenticator().emptyPublisher()) {
    			if (!Arrays.equals(name().authenticator().publisher(), queryName.authenticator().publisher())) {
    				return false;
    			}
    			if (!name().authenticator().emptyContentDigest()) {
    				if (!Arrays.equals(name().authenticator().contentDigest(), queryName.authenticator().contentDigest()))
    					return false;
    			}
    			if (!name().authenticator().emptySignature()) {
    				if (!Arrays.equals(name().authenticator().signature(), queryName.authenticator().signature()))
    					return false;
    			}
    			if (!name().authenticator().emptyContentType()) {
    				if (!name().authenticator().type().equals(queryName.authenticator().type()))
    					return false;
    			}
    		}
    	}
    	return true;
    }
    
	public CompleteName name() { return _name; }
	public boolean recursive() { return _recursive; }
	public CCNQueryListener queryListener() { return _listener; }

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_listener == null) ? 0 : _listener.hashCode());
		result = PRIME * result + ((_name == null) ? 0 : _name.hashCode());
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
		if (_name == null) {
			if (other._name != null)
				return false;
		} else if (!_name.equals(other._name))
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
