package com.parc.ccn.data;

import java.io.IOException;

import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener.CCNQueryType;
import com.parc.ccn.data.security.ContentAuthenticator;

public interface CCNBase {
	
	public void put(ContentName name,
					ContentAuthenticator authenticator,
					byte [] content) throws IOException;
	
	public CCNQueryDescriptor get(ContentName name,
				    			  ContentAuthenticator authenticator,
				    			  CCNQueryType type,
				    			  CCNQueryListener listener,
				    			  long TTL) throws IOException;
	
	
	
	public void cancel(CCNQueryDescriptor query) throws IOException;

}
