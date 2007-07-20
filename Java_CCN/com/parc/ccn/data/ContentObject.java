package com.parc.ccn.data;

import com.parc.ccn.data.security.ContentAuthenticator;

/**
 * Utility class for grouping all of the bits associated
 * with a piece of content.
 * @author smetters
 *
 */
public class ContentObject {
	
    protected ContentName _name;
    protected ContentAuthenticator _authenticator;
    protected byte [] _content;
    
    public ContentObject(ContentName name,
    					 ContentAuthenticator authenticator,
    					 byte [] content) {
    	_name = name;
    	_authenticator = authenticator;
    	_content = content;
    }
    
    public ContentName name() { return _name; }
    public ContentAuthenticator authenticator() { return _authenticator; }
    public byte [] content() { return _content; }
}
