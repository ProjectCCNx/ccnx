package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.support.Library;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


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
	public CCNEncodableObject(Class<E> type, ContentName name, E data, CCNHandle library) throws IOException {
		super(type, name, data, null, null, library);
	}
	
	public CCNEncodableObject(Class<E> type, ContentName name, E data, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNHandle library) throws IOException {
		super(type, name, data, publisher, keyLocator, library);
	}

	public CCNEncodableObject(Class<E> type, ContentName name, E data,
			boolean raw, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNHandle library) throws IOException {
		super(type, name, data, raw, publisher, keyLocator, library);
	}

	protected CCNEncodableObject(Class<E> type, ContentName name, E data, PublisherPublicKeyDigest publisher,
			KeyLocator keyLocator, CCNFlowControl flowControl) throws IOException {
		super(type, name, data, publisher, keyLocator, flowControl);
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
			CCNHandle library) throws IOException, XMLStreamException {
		super(type, name, (PublisherPublicKeyDigest)null, library);
	}
	
	public CCNEncodableObject(Class<E> type, ContentName name, PublisherPublicKeyDigest publisher,
			CCNHandle library) throws IOException, XMLStreamException {
		super(type, name, publisher, library);
	}

	public CCNEncodableObject(Class<E> type, ContentName name,
			PublisherPublicKeyDigest publisher, boolean raw, CCNHandle library)
			throws IOException, XMLStreamException {
		super(type, name, publisher, raw, library);
	}

	protected CCNEncodableObject(Class<E> type, ContentName name,
			PublisherPublicKeyDigest publisher, CCNFlowControl flowControl)
			throws IOException, XMLStreamException {
		super(type, name, publisher, flowControl);
	}

	public CCNEncodableObject(Class<E> type, ContentObject firstBlock,
			CCNHandle library) throws IOException, XMLStreamException {
		super(type, firstBlock, library);
	}

	public CCNEncodableObject(Class<E> type, ContentObject firstBlock,
			boolean raw, CCNHandle library) throws IOException,
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
			Library.logger().info("XML exception parsing data in block: " + ((CCNInputStream)input).currentSegmentName());
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