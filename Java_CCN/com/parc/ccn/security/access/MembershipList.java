package com.parc.ccn.security.access;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.content.Collection;
import com.parc.ccn.data.util.CCNEncodableObject;

/**
 * Eventually should extend Collection, when that moves onto encodable objects.
 * @author smetters
 *
 */
public class MembershipList extends CCNEncodableObject<Collection> {

	public MembershipList(ContentName name, Collection data, CCNHandle library) throws ConfigurationException, IOException {
		super(Collection.class, name, data, library);
	}
	
	public MembershipList(ContentName name, PublisherPublicKeyDigest publisher,
			CCNHandle library) throws ConfigurationException, IOException, XMLStreamException {
		super(Collection.class, name, publisher, library);
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
			CCNHandle library) throws ConfigurationException, IOException, XMLStreamException {
		super(Collection.class, name, (PublisherPublicKeyDigest)null, library);
	}
	
	public MembershipList(ContentObject firstBlock,
			CCNHandle library) throws ConfigurationException, IOException, XMLStreamException {
		super(Collection.class, firstBlock, library);
	}
	
	public Collection membershipList() { return data(); }

}
