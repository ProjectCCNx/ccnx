package com.parc.ccn.security.access;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.Collection;

public class Group {
	
	private ContentName _groupNamespace;
	private ContentObject _groupPublicKey;
	private Collection _groupMembers; 
	private String _groupName;
	
	public Group() {
		
	}
	
	public String name() { return _groupName; }

}
