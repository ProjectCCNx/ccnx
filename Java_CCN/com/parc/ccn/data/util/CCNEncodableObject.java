package com.parc.ccn.data.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.library.CCNLibrary;

/**
 * Takes a class E, and backs it securely to CCN.
 * @author smetters
 *
 * @param <E>
 */
public class CCNEncodableObject<E extends XMLEncodable> extends CCNNetworkObject<E> {
	
	/**
	 * Doesn't save until you call save, in case you want to tweak things first.
	 * @param type
	 * @param name
	 * @param data
	 * @param library
	 * @throws ConfigurationException
	 * @throws IOException
	 */
	public CCNEncodableObject(Class<E> type, ContentName name, E data, CCNLibrary library) throws ConfigurationException, IOException {
		super(type, name, data, library);
	}
	
	public CCNEncodableObject(Class<E> type, ContentName name, PublisherPublicKeyDigest publisher,
			CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
		super(type, name, publisher, library);
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
	public CCNEncodableObject(Class<E> type, ContentName name, 
			CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
		super(type, name, (PublisherPublicKeyDigest)null, library);
	}
	
	public CCNEncodableObject(Class<E> type, ContentObject firstBlock,
			CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
		super(type, firstBlock, library);
	}

	@Override
	protected E readObjectImpl(InputStream input) throws IOException,
			XMLStreamException {
		E newData = factory();
		newData.decode(input);	
		return newData;
	}

	@Override
	protected void writeObjectImpl(OutputStream output) throws IOException,
			XMLStreamException {
		_data.encode(output);
	}
}
