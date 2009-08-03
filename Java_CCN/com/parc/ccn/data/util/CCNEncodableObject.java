package com.parc.ccn.data.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNInputStream;

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
	public CCNEncodableObject(Class<E> type, ContentName name, E data, CCNLibrary library) throws IOException {
		super(type, name, data, null, library);
	}
	
	public CCNEncodableObject(Class<E> type, ContentName name, E data, PublisherPublicKeyDigest publisher, CCNLibrary library) throws IOException {
		super(type, name, data, publisher, library);
	}

	public CCNEncodableObject(Class<E> type, ContentName name, E data,
			boolean raw, PublisherPublicKeyDigest publisher, CCNLibrary library) throws IOException {
		super(type, name, data, raw, publisher, library);
	}

	protected CCNEncodableObject(Class<E> type, ContentName name, E data, PublisherPublicKeyDigest publisher,
			CCNFlowControl flowControl) throws IOException {
		super(type, name, data, publisher, flowControl);
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
			CCNLibrary library) throws IOException, XMLStreamException {
		super(type, name, (PublisherPublicKeyDigest)null, library);
	}
	
	public CCNEncodableObject(Class<E> type, ContentName name, PublisherPublicKeyDigest publisher,
			CCNLibrary library) throws IOException, XMLStreamException {
		super(type, name, publisher, library);
	}

	public CCNEncodableObject(Class<E> type, ContentName name,
			PublisherPublicKeyDigest publisher, boolean raw, CCNLibrary library)
			throws IOException, XMLStreamException {
		super(type, name, publisher, raw, library);
	}

	protected CCNEncodableObject(Class<E> type, ContentName name,
			PublisherPublicKeyDigest publisher, CCNFlowControl flowControl)
			throws IOException, XMLStreamException {
		super(type, name, publisher, flowControl);
	}

	public CCNEncodableObject(Class<E> type, ContentObject firstBlock,
			CCNLibrary library) throws IOException, XMLStreamException {
		super(type, firstBlock, library);
	}

	public CCNEncodableObject(Class<E> type, ContentObject firstBlock,
			boolean raw, CCNLibrary library) throws IOException,
			XMLStreamException {
		super(type, firstBlock, raw, library);
	}

	protected CCNEncodableObject(Class<E> type, ContentObject firstBlock,
			CCNFlowControl flowControl) throws IOException, XMLStreamException {
		super(type, firstBlock, flowControl);
	}

	@Override
	protected E readObjectImpl(InputStream input) throws IOException,
			XMLStreamException {
		E newData = factory();
		try {
			newData.decode(input);	
		} catch (XMLStreamException xe) {
			Library.logger().info("XML exception parsing data in block: " + ((CCNInputStream)input).currentBlockName());
			throw xe;
		}
		return newData;
	}

	@Override
	protected void writeObjectImpl(OutputStream output) throws IOException,
			XMLStreamException {
		_data.encode(output);
	}
}
