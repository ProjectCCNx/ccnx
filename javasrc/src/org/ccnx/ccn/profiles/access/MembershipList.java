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

package org.ccnx.ccn.profiles.access;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.io.content.CCNEncodableObject;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * Eventually should extend Collection, when that moves onto encodable objects.
 * @author smetters
 *
 */
public class MembershipList extends CCNEncodableObject<Collection> {

	public MembershipList(ContentName name, Collection data, CCNHandle library) throws ConfigurationException, IOException {
		super(Collection.class, name, data, library);
	}
	
	public MembershipList(ContentName name, PublisherPublicKeyDigest publisher,
			CCNHandle library) throws ConfigurationException, IOException, XMLStreamException {
		super(Collection.class, name, publisher, library);
	}
	
	/**
	 * Read constructor -- opens existing object.
	 * @param type
	 * @param name
	 * @param library
	 * @throws XMLStreamException
	 * @throws IOException
	 * @throws ClassNotFoundException 
	 */
	public MembershipList(ContentName name, 
			CCNHandle library) throws ConfigurationException, IOException, XMLStreamException {
		super(Collection.class, name, (PublisherPublicKeyDigest)null, library);
	}
	
	public MembershipList(ContentObject firstBlock,
			CCNHandle library) throws ConfigurationException, IOException, XMLStreamException {
		super(Collection.class, firstBlock, library);
	}
	
	public Collection membershipList() throws ContentNotReadyException, ContentGoneException { return data(); }

}
