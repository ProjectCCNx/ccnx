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

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * A CCNNetworkObject wrapper around Java Strings. Allows reading and writing of
 * versioned strings to CCN, and background updating of same. Very useful class
 * for writing simple tests and applications. See CCNChat for an example.
 */
public class CCNStringObject extends CCNSerializableObject<String> {

	public CCNStringObject(ContentName name, String data, CCNHandle handle) 
				throws IOException {
		super(String.class, false, name, data, handle);
	}
	
	public CCNStringObject(ContentName name, PublisherPublicKeyDigest publisher,
							CCNHandle handle) 
				throws ContentDecodingException, IOException {
		super(String.class, false, name, publisher, handle);
	}
	
	public CCNStringObject(ContentName name, CCNHandle handle) 
				throws ContentDecodingException, IOException {
		super(String.class, false, name, (PublisherPublicKeyDigest)null, handle);
	}
	
	public CCNStringObject(ContentObject firstBlock, CCNHandle handle) 
				throws ContentDecodingException, IOException {
		super(String.class, false, firstBlock, handle);
	}
	
	public String string() throws ContentNotReadyException, ContentGoneException { return data(); }
}
