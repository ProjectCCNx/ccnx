/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010 Palo Alto Research Center, Inc.
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
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * A subclass of CCNVersionedOutputStream which writes its segments to a repository,
 * using versioned names. If the specified name includes a version, it will write that specific
 * version; otherwise it will add a version to the name of the stream it writes.  If the 
 * constructor is called with the boolean local set to true, the stream will be written to the
 * repository on the local device.
 * If no repository is available, it will throw an exception.
 * 
 * Data written using this class can be read using a normal CCNVersionedInputStream; that
 * class doesn't care whether its content comes from a repository or a cache (or a mix of the two).
 */
public class RepositoryVersionedOutputStream extends CCNVersionedOutputStream {

	public RepositoryVersionedOutputStream(ContentName name, CCNHandle handle) throws IOException {
		this(name, (PublisherPublicKeyDigest)null, handle);
	}

	public RepositoryVersionedOutputStream(ContentName name,
						   				   PublisherPublicKeyDigest publisher,
						   				   CCNHandle handle) throws IOException {
		this(name, null, publisher, null, null, handle);
	}

	public RepositoryVersionedOutputStream(ContentName name, 
										   ContentKeys keys, 
										   CCNHandle handle) throws IOException {
		this(name, null, null, null, keys, handle);
	}

	public RepositoryVersionedOutputStream(ContentName name, 
			  			   			KeyLocator locator, 
			  			   			PublisherPublicKeyDigest publisher,
			  			   			ContentKeys keys,
			  			   			CCNHandle handle) throws IOException {
		this(name, locator, publisher, null, keys, handle);
	}

	public RepositoryVersionedOutputStream(ContentName name, 
										   KeyLocator locator,
										   PublisherPublicKeyDigest publisher, 
										   ContentType type, 
										   ContentKeys keys, 
										   CCNHandle handle)
			throws IOException {
	
		 // The Flow Controller must register a Filter above the version no. for someone else's
		 // getLatestVersion interests to see this stream.
		super(name, locator, publisher, type, keys, 
			  new RepositoryFlowControl(VersioningProfile.cutTerminalVersion(name).first(), handle));
	}
	

	public RepositoryVersionedOutputStream(ContentName name, CCNHandle handle, boolean local) throws IOException {
		this(name, (PublisherPublicKeyDigest)null, handle, local);
	}

	public RepositoryVersionedOutputStream(ContentName name,
						   				   PublisherPublicKeyDigest publisher,
						   				   CCNHandle handle,
						   				   boolean local) throws IOException {
		this(name, null, publisher, null, null, handle, local);
	}

	public RepositoryVersionedOutputStream(ContentName name, 
										   ContentKeys keys, 
										   CCNHandle handle,
										   boolean local) throws IOException {
		this(name, null, null, null, keys, handle, local);
	}

	public RepositoryVersionedOutputStream(ContentName name, 
			  			   			KeyLocator locator, 
			  			   			PublisherPublicKeyDigest publisher,
			  			   			ContentKeys keys,
			  			   			CCNHandle handle,
			  			   			boolean local) throws IOException {
		this(name, locator, publisher, null, keys, handle, local);
	}

	public RepositoryVersionedOutputStream(ContentName name, 
										   KeyLocator locator,
										   PublisherPublicKeyDigest publisher, 
										   ContentType type, 
										   ContentKeys keys, 
										   CCNHandle handle,
										   boolean local)
			throws IOException {
	
		 // The Flow Controller must register a Filter above the version no. for someone else's
		 // getLatestVersion interests to see this stream.
		super(name, locator, publisher, type, keys, 
			  new RepositoryFlowControl(VersioningProfile.cutTerminalVersion(name).first(), handle, local));
	}
}
