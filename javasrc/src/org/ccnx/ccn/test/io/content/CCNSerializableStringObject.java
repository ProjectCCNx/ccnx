/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.io.content;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.io.content.CCNSerializableObject;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * A CCNNetworkObject wrapper around Java Strings, which uses Java serialization
 * to write those strings. Allows reading and writing of
 * versioned strings to CCN, and background updating of same. Very useful class
 * for writing simple tests and applications, but requires both communicating
 * partners to speak Java Serialization. See CCNStringObject for a more generally
 * useful string object that serializes the string in pure UTF-8, making
 * something that can be more easily read from other languages.
 */
public class CCNSerializableStringObject extends CCNSerializableObject<String> {
	
	public CCNSerializableStringObject(ContentName name, String data, SaveType saveType, CCNHandle handle) 
	throws IOException {
		super(String.class, false, name, data, saveType, handle);
	}

	public CCNSerializableStringObject(ContentName name, String data, SaveType saveType, PublisherPublicKeyDigest publisher, 
			KeyLocator locator, CCNHandle handle) throws IOException {
		super(String.class, false, name, data, saveType, publisher, locator, handle);
	}

	public CCNSerializableStringObject(ContentName name, CCNHandle handle) 
	throws ContentDecodingException, IOException {
		super(String.class, false, name, (PublisherPublicKeyDigest)null, handle);
	}

	public CCNSerializableStringObject(ContentName name, PublisherPublicKeyDigest publisher,
			CCNHandle handle) 
	throws ContentDecodingException, IOException {
		super(String.class, false, name, publisher, handle);
	}

	public CCNSerializableStringObject(ContentObject firstBlock, CCNHandle handle) 
	throws ContentDecodingException, IOException {
		super(String.class, false, firstBlock, handle);
	}

	/**
	 * Internal constructor used by low-level network operations. Don't use unless you know what 
	 * you are doing.
	 * @param name name under which to save data
	 * @param data data to save when save() is called; or null if the next call will be updateInBackground()
	 * @param publisher key (identity) to use to sign the content (null for default)
	 * @param locator key locator to use to tell people where to find our key, should match publisher, (null for default for key)
	 * @param flowControl flow controller to use for network output
	 * @throws IOException
	 */
	public CCNSerializableStringObject(ContentName name, String data, 
			PublisherPublicKeyDigest publisher, 
			KeyLocator locator,
			CCNFlowControl flowControl) throws IOException {
		super(String.class, false, name, data, publisher, locator, flowControl);
	}

	/**
	 * Internal constructor used by low-level network operations. Don't use unless you know what 
	 * you are doing.
	 * @param name name under which to save data
	 * @param data data to save when save() is called; or null if the next call will be updateInBackground()
	 * @param publisher key (identity) to use to sign the content (null for default)
	 * @param locator key locator to use to tell people where to find our key, should match publisher, (null for default for key)
	 * @param flowControl flow controller to use for network output
	 * @throws IOException
	 */
	public CCNSerializableStringObject(ContentName name, PublisherPublicKeyDigest publisher,
			CCNFlowControl flowControl) throws ContentDecodingException, IOException {
		super(String.class, false, name, publisher, flowControl);
	}

	/**
	 * Internal constructor used by low-level network operations. Don't use unless you know what 
	 * you are doing.
	 * @param name name under which to save data
	 * @param data data to save when save() is called; or null if the next call will be updateInBackground()
	 * @param publisher key (identity) to use to sign the content (null for default)
	 * @param locator key locator to use to tell people where to find our key, should match publisher, (null for default for key)
	 * @param flowControl flow controller to use for network output
	 * @throws IOException
	 */
	public CCNSerializableStringObject(ContentObject firstSegment, CCNFlowControl flowControl) 
	throws ContentDecodingException, IOException {
		super(String.class, false, firstSegment, flowControl);
	}

	public String string() throws ContentNotReadyException, ContentGoneException, ErrorStateException { return data(); }
}
