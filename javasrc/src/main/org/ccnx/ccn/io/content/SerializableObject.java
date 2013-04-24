/*
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
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.GenericObjectInputStream;



/**
 * Subclass of NetworkObject that wraps classes implementing Serializable, and uses
 * Java serialization to read and write those objects to 
 * an OutputStream. Not CCN-specific, the CCN variant of this is CCNSerializableObject.
 */
public class SerializableObject<E extends Serializable> extends NetworkObject<E> {

	public SerializableObject(Class<E> type, boolean contentIsMutable) {
		super(type, contentIsMutable);
	}
	
	public SerializableObject(Class<E> type, boolean contentIsMutable, E data) {
		super(type, contentIsMutable, data);
	}
		
	@Override
	protected E readObjectImpl(InputStream input) throws ContentDecodingException, IOException {
		GenericObjectInputStream<E> ois = new GenericObjectInputStream<E>(input);
		E newData;
		try {
			newData = ois.genericReadObject();
		} catch (ClassNotFoundException e) {
			Log.warning("Unexpected ClassNotFoundException in SerializedObject<" + _type.getName() + ">: " + e.getMessage());
			throw new IOException("Unexpected ClassNotFoundException in SerializedObject<" + _type.getName() + ">: " + e.getMessage());
		}
		return newData;
	}

	@Override
	protected void writeObjectImpl(OutputStream output) throws ContentEncodingException, IOException {
		ObjectOutputStream oos = new ObjectOutputStream(output);		
		oos.writeObject(_data);
		oos.flush();
		output.flush();
		oos.close();
	}
}
