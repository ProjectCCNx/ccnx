package com.parc.ccn.data.query;

import java.util.Arrays;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNQueryListener.CCNQueryType;
import com.parc.ccn.data.security.ContentAuthenticator;

/**
 * This base class just combines all of the information
 * that went into the query. Subclasses might add specific
 * identifiers to allow easier cancellation of queries.
 * 
 * DKS -- figure out how to integrate identifiers for
 * remote queries.
 * @author smetters
 *
 */
public class CCNQueryDescriptor {
	
	/**
	 * Do we handle recursive queries this way or
	 * with the query type?
	 */
	public static final String RECURSIVE_POSTFIX = "*";

	protected ContentName _name;
	protected ContentAuthenticator _authenticator;
	protected CCNQueryListener.CCNQueryType _type;
	protected long _TTL;
	/*
	 * First pass at handling identifiers for queries.
	 */
	protected Object _queryIdentifier = null;
	
	public CCNQueryDescriptor(ContentName name,
							  ContentAuthenticator authenticator,
							  CCNQueryListener.CCNQueryType type,
							  long TTL) {
		_name = name;
		_authenticator = authenticator;
		_type = type;
		_TTL = TTL;
	}
	
	public CCNQueryDescriptor(ContentName name,
			  ContentAuthenticator authenticator,
			  CCNQueryListener.CCNQueryType type,
			  long TTL, 
			  Object identifier) {
		this(name, authenticator, type, TTL);
		_queryIdentifier = identifier;
	}
	
	public CCNQueryDescriptor(CCNQueryDescriptor descriptor) {
		this(descriptor.name(), descriptor.authenticator(),
			 descriptor.type(), descriptor.TTL(), descriptor.queryIdentifier());
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
    	if (CCNQueryType.EXACT_MATCH == type()) {
   			if (name().component(name().count()-1).equals(RECURSIVE_POSTFIX.getBytes())) {
    			if (!name().equals(object.name(), name().count()-1)) {
    				return false;
    			}
   			} else if (!name().equals(object.name())) {
    			return false;
    		}
   			// Now take into account other specializations
   			// on the query, most notably publisherID.
   			// DKS -- also think about version...
   			if ((null != authenticator()) && (!authenticator().empty())) {
   				if (!authenticator().emptyPublisher()) {
   					if (!Arrays.equals(authenticator().publisher(), object.authenticator().publisher())) {
   						return false;
   					}
   	   				if (!authenticator().emptyContentHash()) {
   	   					if (!Arrays.equals(authenticator().contentHash(), object.authenticator().contentHash()))
   	   						return false;
   	   				}
   	   	   			if (!authenticator().emptySignature()) {
   	   	   				if (!Arrays.equals(authenticator().signature(), object.authenticator().signature()))
   	   	   					return false;
   	   	   			}
   				}
   			}
   			return true;
    	} else {
    		// Unknown query type
    		return false;
    	}
    }
    
	public ContentName name() { return _name; }
	public ContentAuthenticator authenticator() { return _authenticator; }
	public CCNQueryListener.CCNQueryType type() { return _type; }
	public long TTL() { return _TTL; }
}
