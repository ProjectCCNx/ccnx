/**
 * 
 */
package org.ccnx.ccn.io.content;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


public class CCNStringObject extends CCNSerializableObject<String> {

	public CCNStringObject(ContentName name, String data, CCNHandle library) throws ConfigurationException, IOException {
		super(String.class, name, data, library);
	}
	
	public CCNStringObject(ContentName name, PublisherPublicKeyDigest publisher,
			CCNHandle library) throws ConfigurationException, IOException, XMLStreamException {
		super(String.class, name, publisher, library);
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
	public CCNStringObject(ContentName name, 
			CCNHandle library) throws ConfigurationException, IOException, XMLStreamException {
		super(String.class, name, (PublisherPublicKeyDigest)null, library);
	}
	
	public CCNStringObject(ContentObject firstBlock,
			CCNHandle library) throws ConfigurationException, IOException, XMLStreamException {
		super(String.class, firstBlock, library);
	}
	
	public String string() { return data(); }
}