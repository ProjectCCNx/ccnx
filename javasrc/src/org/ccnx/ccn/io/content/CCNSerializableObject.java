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

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.GenericObjectInputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * Subclass of CCNNetworkObject that wraps classes implementing Serializable, and uses
 * Java serialization to read and write those objects to CCN. Note that using Java
 * serialization writes Java-specific object headers for all built-in objects that
 * already define serialization formats. Using this as a serialization format for
 * CCN objects works extremely well when those objects will be read and written
 * from Java. If objects will be read or written from languages other than Java,
 * Java serialization may not be the best backing representation.
 */
public class CCNSerializableObject<E extends Serializable> extends CCNNetworkObject<E> {
	
	public CCNSerializableObject(Class<E> type, boolean contentIsMutable, 
								 ContentName name, E data, SaveType saveType,
								 CCNHandle handle) throws IOException {
		super(type, contentIsMutable, name, data, saveType, handle);
	}

	public CCNSerializableObject(Class<E> type, boolean contentIsMutable, ContentName name, E data,
			SaveType saveType, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNHandle handle) throws IOException {
		super(type, contentIsMutable, name, data, saveType, publisher, keyLocator, handle);
	}

	protected CCNSerializableObject(Class<E> type, boolean contentIsMutable, 
									ContentName name, E data, PublisherPublicKeyDigest publisher,
									KeyLocator keyLocator, CCNFlowControl flowControl) throws IOException {
		super(type, contentIsMutable, name, data, publisher, keyLocator, flowControl);
	}

	public CCNSerializableObject(Class<E> type, boolean contentIsMutable, 
								 ContentName name, CCNHandle handle) 
				throws ContentDecodingException, IOException {
		super(type, contentIsMutable, name, (PublisherPublicKeyDigest)null, handle);
	}
	
	public CCNSerializableObject(Class<E> type, boolean contentIsMutable, 
								 ContentName name, PublisherPublicKeyDigest publisher,
								 CCNHandle handle) 
				throws ContentDecodingException, IOException {
		super(type, contentIsMutable, name, publisher, handle);
	}
	
	protected CCNSerializableObject(Class<E> type, boolean contentIsMutable, 
									ContentName name,
									PublisherPublicKeyDigest publisher, 
									CCNFlowControl flowControl)
				throws ContentDecodingException, IOException {
		super(type, contentIsMutable, name, publisher, flowControl);
	}

	public CCNSerializableObject(Class<E> type, boolean contentIsMutable, 
								ContentObject firstBlock, CCNHandle handle) 
				throws ContentDecodingException, IOException {
		super(type, contentIsMutable, firstBlock, handle);
	}

	protected CCNSerializableObject(Class<E> type, boolean contentIsMutable, 
									ContentObject firstBlock,
									CCNFlowControl flowControl) 
				throws ContentDecodingException, IOException {
		super(type, contentIsMutable, firstBlock, flowControl);
	}

	protected CCNSerializableObject(Class<E> type, CCNSerializableObject<? extends E> other) {
		super(type, other);
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
		if (null == _data)
			throw new ContentNotReadyException("No content available to save for object " + getBaseName());
		ObjectOutputStream oos = new ObjectOutputStream(output);		
		oos.writeObject(_data);
		oos.flush();
		output.flush();
		oos.close();
	}
}
