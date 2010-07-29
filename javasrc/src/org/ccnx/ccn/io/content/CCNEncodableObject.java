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
import java.io.OutputStream;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * Provides persistence for classes implementing XMLEncodable using a CCN network to store/load
 * the data. This is similar to the Data Access Object pattern.
 * 
 * The data supplier (class implementing XMLEncodable's encode() and decode() methods) is called
 * to read and write those objects to CCN.
 */
public class CCNEncodableObject<E extends XMLEncodable> extends CCNNetworkObject<E> {
	
	public CCNEncodableObject(Class<E> type, boolean contentIsMutable,
							  ContentName name, E data, SaveType saveType,
							  CCNHandle handle) throws IOException {
		super(type, contentIsMutable, name, data, saveType, null, null, handle);
	}
	
	public CCNEncodableObject(Class<E> type, boolean contentIsMutable,
							 ContentName name, E data,
							 SaveType saveType, PublisherPublicKeyDigest publisher, 
							 KeyLocator keyLocator, CCNHandle handle) throws IOException {
		super(type, contentIsMutable, name, data, saveType, publisher, keyLocator, handle);
	}

	protected CCNEncodableObject(Class<E> type, boolean contentIsMutable,
								ContentName name, E data, PublisherPublicKeyDigest publisher,
								KeyLocator keyLocator, CCNFlowControl flowControl) throws IOException {
		super(type, contentIsMutable, name, data, publisher, keyLocator, flowControl);
	}
	
	public CCNEncodableObject(Class<E> type, boolean contentIsMutable, 
							  ContentName name, 
							  CCNHandle handle) 
			throws ContentDecodingException, IOException {
		super(type, contentIsMutable, name, (PublisherPublicKeyDigest)null, handle);
	}
	
	public CCNEncodableObject(Class<E> type, boolean contentIsMutable, 
							  ContentName name, PublisherPublicKeyDigest publisher,
							  CCNHandle handle) 
			throws ContentDecodingException, IOException {
		super(type, contentIsMutable, name, publisher, handle);
	}

	protected CCNEncodableObject(Class<E> type, boolean contentIsMutable, ContentName name,
								 PublisherPublicKeyDigest publisher, CCNFlowControl flowControl)
			throws ContentDecodingException, IOException {
		super(type, contentIsMutable, name, publisher, flowControl);
	}

	public CCNEncodableObject(Class<E> type, boolean contentIsMutable, 
						      ContentObject firstBlock,
							  CCNHandle handle) 
			throws ContentDecodingException, IOException {
		super(type, contentIsMutable, firstBlock, handle);
	}

	protected CCNEncodableObject(Class<E> type, boolean contentIsMutable, 
								ContentObject firstBlock,
								CCNFlowControl flowControl) 
			throws ContentDecodingException, IOException {
		super(type, contentIsMutable, firstBlock, flowControl);
	}

	protected CCNEncodableObject(Class<E> type, CCNEncodableObject<? extends E> other) {
		super(type, other);
	}
	
	@Override
	protected E readObjectImpl(InputStream input) throws ContentDecodingException, IOException {
		E newData = factory();
		newData.decode(input);	
		return newData;
	}

	@Override
	protected void writeObjectImpl(OutputStream output) throws ContentEncodingException, IOException {
		if (null == _data)
			throw new ContentNotReadyException("No content available to save for object " + getBaseName());
		_data.encode(output);
	}
}
