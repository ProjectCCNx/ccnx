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

package org.ccnx.ccn.profiles.security.access.group;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * This class records the membership list of Group. Might want to define its own tag.
 */
public class MembershipList extends Collection.CollectionObject {

	public MembershipList(ContentName name, Collection data, CCNHandle handle) 
			throws IOException {
		super(name, data, handle);
	}
	
	/**
	 * Read constructor -- opens existing object.
	 * @param name
	 * @param handle
	 * @throws ContentDecodingException
	 * @throws IOException
	 */
	public MembershipList(ContentName name, CCNHandle handle) throws ContentDecodingException, IOException {
		super(name, (PublisherPublicKeyDigest)null, handle);
	}

	public MembershipList(ContentName name, PublisherPublicKeyDigest publisher,
			CCNHandle handle) 
			throws ContentDecodingException, IOException {
		super(name, publisher, handle);
	}
	
	
	public MembershipList(ContentObject firstBlock, CCNHandle handle) 
				throws ContentDecodingException, IOException {
		super(firstBlock, handle);
	}
	
	/**
	 * Returns the membership list as a collection.
	 * @return
	 * @throws ContentNotReadyException
	 * @throws ContentGoneException
	 */
	public Collection membershipList() throws ContentNotReadyException, ContentGoneException { return data(); }

}
