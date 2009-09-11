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

package org.ccnx.ccn.io;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.repo.RepositoryFlowControl;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;



/**
 * Set up a CCNOutputStream that can talk to a repository
 * @author rasmusse
 *
 */

public class RepositoryOutputStream extends CCNOutputStream {
	
	public RepositoryOutputStream(ContentName name, CCNHandle library) throws IOException {
		this(name, null, null, library);
	}
	
	public RepositoryOutputStream(ContentName name, 
			KeyLocator locator, PublisherPublicKeyDigest publisher, CCNHandle library)
			throws IOException {
		super(name, locator, publisher, new RepositoryFlowControl(name, library));
	}
}

