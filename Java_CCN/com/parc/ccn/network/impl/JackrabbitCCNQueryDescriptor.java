package com.parc.ccn.network.impl;

import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.security.ContentAuthenticator;

public class JackrabbitCCNQueryDescriptor extends CCNQueryDescriptor {

	public JackrabbitCCNQueryDescriptor(ContentName name,
			ContentAuthenticator authenticator, JackrabbitEventListener identifier) {
		super(name, authenticator, identifier);
	}

	public JackrabbitCCNQueryDescriptor(CompleteName name) {
		super(name);
	}

	public JackrabbitCCNQueryDescriptor(CompleteName name, JackrabbitEventListener identifier) {
		super(name, identifier);
	}

	public JackrabbitCCNQueryDescriptor(CCNQueryDescriptor descriptor) {
		super(descriptor);
	}
	
	public JackrabbitEventListener listener() { return (JackrabbitEventListener)queryIdentifier(); }

}
