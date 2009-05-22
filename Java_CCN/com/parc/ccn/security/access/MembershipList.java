package com.parc.ccn.security.access;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.util.CCNEncodableObject;
import com.parc.ccn.library.CCNLibrary;

/**
 * Eventually should extend Collection, when that moves onto encodable objects.
 * @author smetters
 *
 */
public class MembershipList extends CCNEncodableObject<CollectionData> {

	public MembershipList() throws ConfigurationException, IOException {
		super(CollectionData.class);
	}
	
	public MembershipList(CCNLibrary library) throws IOException {
		super(CollectionData.class, library);
	}
	
	public MembershipList(ContentName name, CCNLibrary library) throws XMLStreamException, IOException {
		super(CollectionData.class, name, library);
	}

	public MembershipList(ContentName name) throws XMLStreamException, IOException, ConfigurationException {
		super(CollectionData.class, name);
	}
	public MembershipList(ContentName name, CollectionData data, CCNLibrary library) {
		super(CollectionData.class, name, data, library);
	}

	public MembershipList(ContentName name, CollectionData data) throws ConfigurationException, IOException {
		super(CollectionData.class, name, data);
	}
	
	public CollectionData membershipList() { return data(); }

}
