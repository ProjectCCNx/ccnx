package com.parc.ccn.data.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.stream.XMLStreamException;

/**
 * Prototypical wrapper around an XMLEncodable object. Expand to variants
 * for CCNObjects. 
 * TODO - synchronization
 * @author smetters
 *
 * @param <E>
 */
public class EncodableObject<E extends XMLEncodable> extends NetworkObject<E> {
		
	public EncodableObject(Class<E> type) {
		super(type);
	}
	
	public EncodableObject(Class<E> type, E data) {
		super(type, data);
	}
	
	protected void writeObjectImpl(OutputStream output) throws IOException, XMLStreamException {
		_data.encode(output);
	}

	protected E readObjectImpl(InputStream input) throws IOException, XMLStreamException {
		E newData = factory();
		newData.decode(input);	
		return newData;
	}
}
