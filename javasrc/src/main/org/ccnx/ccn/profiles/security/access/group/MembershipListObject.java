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

package org.ccnx.ccn.profiles.security.access.group;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * This class records the membership list of a Group, which can consist of
 * individual users or other groups). This is sometimes redundant with other
 * representations of the membership of a Group or association; it would be
 * good in future work to make explicit membership lists optional (TODO).
 * 
 * Might want to define its own tag for encoding; right now it encodes as a straight
 * Collection.
 */
public class MembershipListObject extends Collection.CollectionObject {

	/**
	 * Write constructors. Prepare to save object.
	 * @param name
	 * @param data
	 * @param saveType
	 * @param handle
	 * @throws IOException
	 */
	public MembershipListObject(ContentName name, Collection data, SaveType saveType, CCNHandle handle) 
			throws IOException {
		super(name, data, saveType, handle);
	}
	
	public MembershipListObject(ContentName name, Collection data, SaveType saveType,
			PublisherPublicKeyDigest publisher, KeyLocator keyLocator,
			CCNHandle handle) throws IOException {
		super(name, data, saveType, publisher, keyLocator, handle);
	}

	public MembershipListObject(ContentName name, java.util.Collection<Link> data,
			SaveType saveType, CCNHandle handle) throws IOException {
		super(name, data, saveType, handle);
	}

	public MembershipListObject(ContentName name, java.util.Collection<Link> data,
			SaveType saveType, PublisherPublicKeyDigest publisher,
			KeyLocator keyLocator, CCNHandle handle) throws IOException {
		super(name, data, saveType, publisher, keyLocator, handle);
	}

	public MembershipListObject(ContentName name, Link[] contents, SaveType saveType,
			CCNHandle handle) throws IOException {
		super(name, contents, saveType, handle);
	}

	public MembershipListObject(ContentName name, Link[] contents, SaveType saveType,
			PublisherPublicKeyDigest publisher, KeyLocator keyLocator,
			CCNHandle handle) throws IOException {
		super(name, contents, saveType, publisher, keyLocator, handle);
	}

	public MembershipListObject(ContentName name, Collection data, 
			PublisherPublicKeyDigest publisher, 
			KeyLocator keyLocator, CCNFlowControl flowControl) throws IOException {
		super(name, data, publisher, keyLocator, flowControl);
	}

	/**
	 * Read constructor -- opens existing object.
	 * @param name
	 * @param handle
	 * @throws ContentDecodingException
	 * @throws IOException
	 */
	public MembershipListObject(ContentName name, CCNHandle handle) throws ContentDecodingException, IOException {
		super(name, (PublisherPublicKeyDigest)null, handle);
	}

	public MembershipListObject(ContentName name, PublisherPublicKeyDigest publisher,
			CCNHandle handle) 
			throws ContentDecodingException, IOException {
		super(name, publisher, handle);
	}
	
	
	public MembershipListObject(ContentObject firstBlock, CCNHandle handle) 
				throws ContentDecodingException, IOException {
		super(firstBlock, handle);
	}
	
	public MembershipListObject(ContentName name,
			PublisherPublicKeyDigest publisher, CCNFlowControl flowControl)
	throws ContentDecodingException, IOException {
		super(name, publisher, flowControl);
	}

	public MembershipListObject(ContentObject firstBlock,
			CCNFlowControl flowControl) 
	throws ContentDecodingException, IOException {
		super(firstBlock, flowControl);
	}

	/**
	 * Returns the membership list as a collection.
	 * @return
	 * @throws ContentNotReadyException
	 * @throws ContentGoneException
	 * @throws ErrorStateException 
	 */
	public Collection membershipList() throws ContentNotReadyException, ContentGoneException, ErrorStateException { return data(); }

}
