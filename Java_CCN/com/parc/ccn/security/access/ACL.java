package com.parc.ccn.security.access;

import java.io.IOException;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.util.CCNEncodableObject;
import com.parc.ccn.library.CCNLibrary;

public class ACL extends CollectionData {
	
	public static final String LABEL_READER = "r";
	public static final String LABEL_WRITER = "rw";
	public static final String LABEL_MANAGER = "rw+";
	public static final String [] ROLE_LABELS = {LABEL_READER, LABEL_WRITER, LABEL_MANAGER};

	public static class ACLObject extends CCNEncodableObject<ACL> {

		public ACLObject() throws ConfigurationException, IOException {
			super(ACL.class);
		}
		
		public ACLObject(ContentName name, CCNLibrary library) throws XMLStreamException, IOException {
			super(ACL.class, name, library);
		}

		public ACLObject(ContentName name) throws XMLStreamException, IOException, ConfigurationException {
			super(ACL.class, name);
		}
		public ACLObject(ContentName name, ACL acl, CCNLibrary library) {
			super(ACL.class, name, acl, library);
		}

		public ACLObject(ContentName name, ACL acl) throws ConfigurationException, IOException {
			super(ACL.class, name, acl);
		}
		
		public ACL acl() { return data(); }
	}

	public ACL() {
		super();
	}

	public ACL(ArrayList<LinkReference> contents) {
		super(contents);
		if (!validate()) {
			throw new IllegalArgumentException("Invalid contents for ACL.");
		}
	}
	
	@Override
	public void add(LinkReference link) {
		if (validLabel(link))
			super.add(link);
		throw new IllegalArgumentException("Invalid label: " + link.targetLabel());
	}
	
	public boolean validLabel(LinkReference lr) {
		return LABEL_MANAGER.contains(lr.targetLabel());
	}

	@Override
	public boolean validate() {
		if (!super.validate())
			return false;
		for (LinkReference lr : contents()) {
			if ((null == lr.targetLabel()) || (!validLabel(lr))) {
				return false;
			}
		}
		return true;
	}
}
