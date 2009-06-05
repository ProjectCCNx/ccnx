package com.parc.ccn.security.access;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.util.CCNEncodableObject;
import com.parc.ccn.library.CCNLibrary;

/**
 * Eventually should extend Collection, when that moves onto encodable objects.
 * @author smetters
 *
 */
public class MembershipList extends CCNEncodableObject<CollectionData> {

	public MembershipList(ContentName name, CollectionData data, CCNLibrary library) throws ConfigurationException, IOException {
		super(CollectionData.class, name, data, library);
	}
	
	public MembershipList(ContentName name, PublisherPublicKeyDigest publisher,
			CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
		super(CollectionData.class, name, publisher, library);
	}
	
	/**
	 * Read constructor -- opens existing object.
	 * @param type
	 * @param name
	 * @param library
	 * @throws XMLStreamException
	 * @throws IOException
	 * @throws ClassNotFoundException 
	 */
	public MembershipList(ContentName name, 
			CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
		super(CollectionData.class, name, (PublisherPublicKeyDigest)null, library);
	}
	
	public MembershipList(ContentObject firstBlock,
			CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
		super(CollectionData.class, firstBlock, library);
	}
	
	public CollectionData membershipList() { return data(); }

}
