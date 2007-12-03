package com.parc.ccn.data.query;

import java.util.Arrays;

import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
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
	// RepositoryManager/InterestManager architecture
	
	/**
	 * Do we handle recursive queries this way or
	 * are all queries recursive?
	 */
	public static final String RECURSIVE_POSTFIX = "*";

	/*
	 * First pass at handling identifiers for queries.
	 */
	protected Object _queryIdentifier = null;
	protected CompleteName _name = null;
	protected boolean _recursive = false;
	
	public CCNQueryDescriptor(ContentName name, ContentAuthenticator authenticator, Object identifier) {
		this(new CompleteName(name, authenticator), identifier);
	}
	
	public CCNQueryDescriptor(CompleteName name) {
		_name = name;
		if (Arrays.equals(name.name().component(name.name().count()-1), 
				RECURSIVE_POSTFIX.getBytes())) {
			_recursive = true;
		}
	}
	
	public CCNQueryDescriptor(CompleteName name,
			  				  Object identifier) {
		this(name);
		_queryIdentifier = identifier;
	}
	
	public CCNQueryDescriptor(CCNQueryDescriptor descriptor) {
		this(descriptor.name(), descriptor.queryIdentifier());
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
    	
    	// Handle prefix matching
    	// If the query is recursive, see if the remainder of the name matches
     	if (recursive()) {
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
    			if (!name().authenticator().emptyContentDigest()) {
    				if (!Arrays.equals(name().authenticator().contentDigest(), object.authenticator().contentDigest()))
    					return false;
    			}
    			if (!name().authenticator().emptySignature()) {
    				if (!Arrays.equals(name().authenticator().signature(), object.authenticator().signature()))
    					return false;
    			}
    			if (!name().authenticator().emptyContentType()) {
    				if (!name().authenticator().type().equals(object.authenticator().type()))
    					return false;
    			}
    		}
    	}
    	return true;
    }
    
	public CompleteName name() { return _name; }
	public boolean recursive() { return _recursive; }
}
