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

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * Subclass of CCNNetworkObject that wraps classes implementing XMLEncodable, and uses
 * XMLEncodable's encode() and decode() methods to read and write those objects to 
 * CCN.
 */
public class CCNEncodableObject<E extends XMLEncodable> extends CCNNetworkObject<E> {
	
	public CCNEncodableObject(Class<E> type, ContentName name, E data, CCNHandle handle) throws IOException {
		super(type, name, data, null, null, handle);
	}
	
	public CCNEncodableObject(Class<E> type, ContentName name, E data, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNHandle handle) throws IOException {
		super(type, name, data, publisher, keyLocator, handle);
	}

	public CCNEncodableObject(Class<E> type, ContentName name, E data,
			boolean raw, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNHandle handle) throws IOException {
		super(type, name, data, raw, publisher, keyLocator, handle);
	}

	protected CCNEncodableObject(Class<E> type, ContentName name, E data, PublisherPublicKeyDigest publisher,
			KeyLocator keyLocator, CCNFlowControl flowControl) throws IOException {
		super(type, name, data, publisher, keyLocator, flowControl);
	}
	
	public CCNEncodableObject(Class<E> type, ContentName name, 
			CCNHandle handle) throws IOException, XMLStreamException {
		super(type, name, (PublisherPublicKeyDigest)null, handle);
	}
	
	public CCNEncodableObject(Class<E> type, ContentName name, PublisherPublicKeyDigest publisher,
			CCNHandle handle) throws IOException, XMLStreamException {
		super(type, name, publisher, handle);
	}

	public CCNEncodableObject(Class<E> type, ContentName name,
			PublisherPublicKeyDigest publisher, boolean raw, CCNHandle handle)
			throws IOException, XMLStreamException {
		super(type, name, publisher, raw, handle);
	}

	protected CCNEncodableObject(Class<E> type, ContentName name,
			PublisherPublicKeyDigest publisher, CCNFlowControl flowControl)
			throws IOException, XMLStreamException {
		super(type, name, publisher, flowControl);
	}

	public CCNEncodableObject(Class<E> type, ContentObject firstBlock,
			CCNHandle handle) throws IOException, XMLStreamException {
		super(type, firstBlock, handle);
	}

	public CCNEncodableObject(Class<E> type, ContentObject firstBlock,
			boolean raw, CCNHandle handle) throws IOException,
			XMLStreamException {
		super(type, firstBlock, raw, handle);
	}

	protected CCNEncodableObject(Class<E> type, ContentObject firstBlock,
			CCNFlowControl flowControl) throws IOException, XMLStreamException {
		super(type, firstBlock, flowControl);
	}

	@Override
	protected E readObjectImpl(InputStream input) throws IOException,
			XMLStreamException {
		E newData = factory();
		try {
			newData.decode(input);	
		} catch (XMLStreamException xe) {
			Log.info("XML exception parsing data in block: " + ((CCNInputStream)input).currentSegmentName());
			throw xe;
		}
		return newData;
	}

	@Override
	protected void writeObjectImpl(OutputStream output) throws IOException,
			XMLStreamException {
		if (null == _data)
			throw new ContentNotReadyException("No content available to save for object " + getBaseName());
		_data.encode(output);
	}
}
