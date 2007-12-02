package com.parc.ccn.data.query;

import java.util.Arrays;

import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentObject;

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
	// RepositoryManager/InterestManager architecture
	
	/**
	 * Do we handle recursive queries this way or
	 * are all queries recursive?
	 */
	public static final String RECURSIVE_POSTFIX = "*";

	protected long _TTL;
	/*
	 * First pass at handling identifiers for queries.
	 */
	protected Object _queryIdentifier = null;
	protected CompleteName _name = null;
	
	public CCNQueryDescriptor(CompleteName name,
							  long TTL) {
		_name = name;
		_TTL = TTL;
	}
	
	public CCNQueryDescriptor(CompleteName name,
			  long TTL, 
			  Object identifier) {
		this(name, TTL);
		_queryIdentifier = identifier;
	}
	
	public CCNQueryDescriptor(CCNQueryDescriptor descriptor) {
		this(descriptor.name(), descriptor.TTL(), descriptor.queryIdentifier());
	}
	
	public void setQueryIdentifier(Object identifier) {
		_queryIdentifier = identifier;
	}
	
	public Object queryIdentifier() {
		return _queryIdentifier;
	}

    public boolean matchesQuery(ContentObject object) {
    	// Need to cope with recursive queries.
    	// Switch on enums still weird in Java
    	
    	// TODO -- fix
    	jkl;
    	// Handle prefix matching
    	if (name().name().component(name().name().count()-1).equals(RECURSIVE_POSTFIX.getBytes())) {
    		if (!name().name().equals(object.name(), name().name().count()-1)) {
    			return false;
    		}
    	} else if (!name().name().equals(object.name())) {
    		return false;
    	}
    	// Now take into account other specializations
    	// on the query, most notably publisherID.
    	if ((null != name().authenticator()) && (!name().authenticator().empty())) {
    		if (!name().authenticator().emptyPublisher()) {
    			if (!Arrays.equals(name().authenticator().publisher(), object.authenticator().publisher())) {
    				return false;
    			}
    			if (!name().authenticator().emptyContentHash()) {
    				if (!Arrays.equals(name().authenticator().contentDigest(), object.authenticator().contentDigest()))
    					return false;
    			}
    			if (!name().authenticator().emptySignature()) {
    				if (!Arrays.equals(name().authenticator().signature(), object.authenticator().signature()))
    					return false;
    			}
    		}
    	}
    	return true;
    }
    
	public CompleteName name() { return _name; }
	public long TTL() { return _TTL; }
}
