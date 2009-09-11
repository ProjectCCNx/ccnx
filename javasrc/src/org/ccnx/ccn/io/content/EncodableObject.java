/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.encoding.XMLEncodable;


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
