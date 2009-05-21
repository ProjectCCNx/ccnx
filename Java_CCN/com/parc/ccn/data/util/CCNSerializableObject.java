package com.parc.ccn.data.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
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
public class CCNSerializableObject<E extends Serializable> extends CCNNetworkObject<E> {
	
	public CCNSerializableObject(Class<E> type) throws ConfigurationException, IOException {
		super(type, CCNLibrary.open());
	}
	
	public CCNSerializableObject(Class<E> type, CCNLibrary library) {
		super(type, library);
	}
	
	public CCNSerializableObject(Class<E> type, ContentName name, E data, CCNLibrary library) {
		super(type, name, data, library);
	}
	
	public CCNSerializableObject(Class<E> type, ContentName name, E data) throws ConfigurationException, IOException {
		this(type, name, data, CCNLibrary.open());
	}
	
	public CCNSerializableObject(Class<E> type, E data, CCNLibrary library) {
		super(type, null, data, library);
	}
	
	public CCNSerializableObject(Class<E> type, E data) throws ConfigurationException, IOException {
		this(type, data, CCNLibrary.open());
	}

	/**
	 * Construct an object from stored CCN data.
	 * @param type
	 * @param content The object to recover, or one of its fragments.
	 * @param library
	 * @throws XMLStreamException
	 * @throws IOException
	 * @throws ClassNotFoundException 
	 */
	public CCNSerializableObject(Class<E> type, ContentObject content, CCNLibrary library) throws XMLStreamException, IOException, ClassNotFoundException {
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
	 * @throws ClassNotFoundException 
	 */
	public CCNSerializableObject(Class<E> type, ContentName name, PublisherPublicKeyDigest publisher, CCNLibrary library) throws XMLStreamException, IOException, ClassNotFoundException {
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
	 * @throws ClassNotFoundException 
	 */
	public CCNSerializableObject(Class<E> type, ContentName name, CCNLibrary library) throws XMLStreamException, IOException, ClassNotFoundException {
		this(type, name, (PublisherPublicKeyDigest)null, library);
	}
	
	public CCNSerializableObject(Class<E> type, ContentName name) throws XMLStreamException, IOException, ConfigurationException, ClassNotFoundException {
		this(type, name, CCNLibrary.open());
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
