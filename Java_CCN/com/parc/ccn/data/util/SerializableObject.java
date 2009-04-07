package com.parc.ccn.data.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;

import javax.xml.stream.XMLStreamException;

/**
 * Prototypical wrapper around a Serializable object. Expand to variants
 * for CCNObjects. 
 * TODO - synchronization
 * @author smetters
 *
 * @param <E>
 */
public class SerializableObject<E extends Serializable> extends NetworkObject<E> {
	

	public SerializableObject(Class<E> type) {
		super(type);
	}
	
	public SerializableObject(Class<E> type, E data) {
		super(type, data);
	}
		
	@Override
	protected Object readObjectImpl(InputStream input) throws IOException, XMLStreamException, ClassNotFoundException {
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
