package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.support.Library;
import org.ccnx.ccn.io.GenericObjectInputStream;



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
	protected E readObjectImpl(InputStream input) throws IOException, XMLStreamException {
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
