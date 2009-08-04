package com.parc.ccn.data.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNLibrary;

/**
 * Takes a class E, and backs it securely to CCN.
 * @author smetters
 *
 * @param <E>
 */
public class CCNSerializableObject<E extends Serializable> extends CCNNetworkObject<E> {
	
	/**
	 * Doesn't save until you call save, in case you want to tweak things first.
	 * @param type
	 * @param name
	 * @param data
	 * @param library
	 * @throws IOException
	 */
	public CCNSerializableObject(Class<E> type, ContentName name, E data, CCNLibrary library) throws IOException {
		super(type, name, data, library);
	}

	public CCNSerializableObject(Class<E> type, ContentName name, E data, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNLibrary library) throws IOException {
		super(type, name, data, publisher, keyLocator, library);
	}

	public CCNSerializableObject(Class<E> type, ContentName name, E data,
			boolean raw, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNLibrary library) throws IOException {
		super(type, name, data, raw, publisher, keyLocator, library);
	}

	protected CCNSerializableObject(Class<E> type, ContentName name, E data, PublisherPublicKeyDigest publisher,
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
	public CCNSerializableObject(Class<E> type, ContentName name, 
			CCNLibrary library) throws IOException, XMLStreamException {
		super(type, name, (PublisherPublicKeyDigest)null, library);
	}
	
	public CCNSerializableObject(Class<E> type, ContentName name, PublisherPublicKeyDigest publisher,
			CCNLibrary library) throws IOException, XMLStreamException {
		super(type, name, publisher, library);
	}
	
	public CCNSerializableObject(Class<E> type, ContentName name,
			PublisherPublicKeyDigest publisher, boolean raw, CCNLibrary library)
			throws IOException, XMLStreamException {
		super(type, name, publisher, raw, library);
	}

	protected CCNSerializableObject(Class<E> type, ContentName name,
			PublisherPublicKeyDigest publisher, CCNFlowControl flowControl)
			throws IOException, XMLStreamException {
		super(type, name, publisher, flowControl);
	}

	public CCNSerializableObject(Class<E> type, ContentObject firstBlock,
			CCNLibrary library) throws IOException, XMLStreamException {
		super(type, firstBlock, library);
	}

	public CCNSerializableObject(Class<E> type, ContentObject firstBlock,
			boolean raw, CCNLibrary library) throws IOException,
			XMLStreamException {
		super(type, firstBlock, raw, library);
	}

	protected CCNSerializableObject(Class<E> type, ContentObject firstBlock,
			CCNFlowControl flowControl) throws IOException, XMLStreamException {
		super(type, firstBlock, flowControl);
	}

	@Override
	protected E readObjectImpl(InputStream input) throws IOException,
			XMLStreamException {
		GenericObjectInputStream<E> ois = new GenericObjectInputStream<E>(input);
		E newData;
		try {
			newData = ois.genericReadObject();
		} catch (ClassNotFoundException e) {
			Library.logger().warning("Unexpected ClassNotFoundException in SerializedObject<" + _type.getName() + ">: " + e.getMessage());
			throw new IOException("Unexpected ClassNotFoundException in SerializedObject<" + _type.getName() + ">: " + e.getMessage());
		}
		return newData;
	}

	@Override
	protected void writeObjectImpl(OutputStream output) throws IOException,
			XMLStreamException {
		ObjectOutputStream oos = new ObjectOutputStream(output);		
		oos.writeObject(_data);
		oos.flush();
		output.flush();
		oos.close();
	}
}
