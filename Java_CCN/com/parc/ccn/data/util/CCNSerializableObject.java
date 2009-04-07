package com.parc.ccn.data.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.library.CCNFlowControl;
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
		this(type, CCNLibrary.open());
	}
	
	public CCNSerializableObject(Class<E> type, CCNLibrary library) {
		super(type);
		_library = library;
		_flowControl = new CCNFlowControl(_library);
	}
	
	public CCNSerializableObject(Class<E> type, ContentName name, E data, CCNLibrary library) {
		super(type, data);
		_currentName = name;
		_library = library;
		_flowControl = new CCNFlowControl(name, _library);
	}
	
	public CCNSerializableObject(Class<E> type, ContentName name, E data) throws ConfigurationException, IOException {
		this(type,name, data, CCNLibrary.open());
	}
	
	public CCNSerializableObject(Class<E> type, E data, CCNLibrary library) {
		this(type, null, data, library);
		_flowControl = new CCNFlowControl(_library);
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
	public CCNSerializableObject(Class<E> type, ContentName name, PublisherKeyID publisher, CCNLibrary library) throws XMLStreamException, IOException, ClassNotFoundException {
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
		this(type, name, (PublisherKeyID)null, library);
	}
	
	public CCNSerializableObject(Class<E> type, ContentName name) throws XMLStreamException, IOException, ConfigurationException, ClassNotFoundException {
		this(type, name, CCNLibrary.open());
	}

	@Override
	protected Object readObjectImpl(InputStream input) throws IOException,
			XMLStreamException, ClassNotFoundException {
		ObjectInputStream ois = new ObjectInputStream(input);
		Object newData = ois.readObject();
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
