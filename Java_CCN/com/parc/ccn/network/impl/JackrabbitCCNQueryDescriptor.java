package com.parc.ccn.network.impl;

import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.data.security.ContentAuthenticator;

public class JackrabbitCCNQueryDescriptor extends CCNQueryDescriptor {

	public JackrabbitCCNQueryDescriptor(
			ContentName name,
			ContentAuthenticator authenticator, 
			JackrabbitEventListener identifier,
			CCNQueryListener listener) {
		super(name, authenticator, identifier, listener);
	}

	public JackrabbitCCNQueryDescriptor(CompleteName name, CCNQueryListener listener) {
		super(name, listener);
	}

	public JackrabbitCCNQueryDescriptor(
				CompleteName name, JackrabbitEventListener identifier,
				CCNQueryListener listener) {
		super(name, identifier, listener);
	}

	public JackrabbitCCNQueryDescriptor(CCNQueryDescriptor descriptor) {
		super(descriptor);
	}
	
	public JackrabbitEventListener jackrabbitListener() { return (JackrabbitEventListener)queryIdentifier(); }

}
