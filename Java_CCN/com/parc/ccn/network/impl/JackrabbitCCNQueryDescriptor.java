package com.parc.ccn.network.impl;

import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;

public class JackrabbitCCNQueryDescriptor extends CCNQueryDescriptor {

	public JackrabbitCCNQueryDescriptor(
			Interest interest, 
			JackrabbitEventListener identifier,
			CCNInterestListener listener) {
		super(interest, identifier, listener);
	}

	public JackrabbitCCNQueryDescriptor(Interest interest, CCNInterestListener listener) {
		super(interest, listener);
	}

	public JackrabbitCCNQueryDescriptor(CCNQueryDescriptor descriptor) {
		super(descriptor);
	}
	
	public JackrabbitEventListener jackrabbitListener() { return (JackrabbitEventListener)queryIdentifier(); }

}
