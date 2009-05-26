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
import com.parc.ccn.library.io.CCNVersionedInputStream;

/**
 * Takes a class E, and backs it securely to CCN.
 * @author smetters
 *
 * @param <E>
 */
public class CCNEncodableObject<E extends XMLEncodable> extends CCNNetworkObject<E> {
	
	public CCNEncodableObject(Class<E> type) throws ConfigurationException, IOException {
		super(type, CCNLibrary.open());
	}
	
	public CCNEncodableObject(Class<E> type, CCNLibrary library) {
		super(type, library);
	}
	
	public CCNEncodableObject(Class<E> type, ContentName name, E data, CCNLibrary library) {
		super(type, name, data, library);
	}
	
	public CCNEncodableObject(Class<E> type, ContentName name, E data) throws ConfigurationException, IOException {
		this(type, name, data, CCNLibrary.open());
	}
	
	public CCNEncodableObject(Class<E> type, E data, CCNLibrary library) {
		super(type, null, data, library);
	}
	
	public CCNEncodableObject(Class<E> type, E data) throws ConfigurationException, IOException {
		this(type, data, CCNLibrary.open());
	}

	/**
	 * Construct an object from stored CCN data.
	 * @param type
	 * @param content The object to recover, or one of its fragments.
	 * @param library
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	public CCNEncodableObject(Class<E> type, ContentObject content, CCNLibrary library) throws XMLStreamException, IOException {
		this(type, library);
		CCNVersionedInputStream is = new CCNVersionedInputStream(content, library);
		is.seek(0); // In case we start with something other than the first fragment.
		update(is);
	}
	
	/**
	 * Ambiguous. Are we supposed to pull this object based on its name,
	 *   or merely attach the name to the object which we will then construct
	 *   and save. Let's assume the former, and allow the name to be specified
	 *   for save() for the latter.
	 * @param type
	 * @param name
	 * @param library
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	public CCNEncodableObject(
			Class<E> type, ContentName name, 
			PublisherPublicKeyDigest publisher, CCNLibrary library) throws XMLStreamException, IOException {
		super(type);
		_library = library;
		CCNVersionedInputStream is = new CCNVersionedInputStream(name, publisher, library);
		update(is);
	}
	
	/**
	 * Read constructor -- opens existing object.
	 * @param type
	 * @param name
	 * @param library
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	public CCNEncodableObject(Class<E> type, ContentName name, CCNLibrary library) throws XMLStreamException, IOException {
		this(type, name, (PublisherPublicKeyDigest)null, library);
	}
	
	public CCNEncodableObject(Class<E> type, ContentName name) throws XMLStreamException, IOException, ConfigurationException {
		this(type, name, CCNLibrary.open());
	}

	@Override
	protected Object readObjectImpl(InputStream input) throws IOException,
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
