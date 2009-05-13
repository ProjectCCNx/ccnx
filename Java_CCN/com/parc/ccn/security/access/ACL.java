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

	public class ACLObject extends CCNEncodableObject<ACL> {

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
		// TODO Auto-generated constructor stub
	}

}
