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

package org.ccnx.ccn.io;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * An output stream that adds a version to the names it outputs. Reading this
 * output with CCNVersionedInputStream allows retrieval of the "latest version"
 * of a stream.
 */
public class CCNVersionedOutputStream extends CCNOutputStream {

	/**
	 * Constructor for a CCN output stream writing under a versioned name.
	 * @param baseName name prefix under which to write content segments; if it is already
	 *   versioned, that version is used, otherwise a new version is added.
	 * @param handle if null, new handle created with CCNHandle#open().
	 * @throws IOException if stream setup fails
	 */
	public CCNVersionedOutputStream(ContentName baseName, CCNHandle handle) throws IOException {
		this(baseName, (PublisherPublicKeyDigest)null, handle);
	}

	/**
	 * Constructor for a CCN output stream writing under a versioned name.
	 * @param baseName name prefix under which to write content segments; if it is already
	 *   versioned, that version is used, otherwise a new version is added.
	 * @param publisher key to use to sign the segments, if null, default for user is used.
	 * @param handle if null, new handle created with CCNHandle#open()
	 * @throws IOException if stream setup fails
	 */
	public CCNVersionedOutputStream(ContentName baseName,
						   			PublisherPublicKeyDigest publisher,
						   			CCNHandle handle) throws IOException {
		this(baseName, null, publisher, null, null, handle);
	}

	/**
	 * Constructor for a CCN output stream writing under a versioned name.
	 * @param baseName name prefix under which to write content segments; if it is already
	 *   versioned, that version is used, otherwise a new version is added.
	 * @param keys keys with which to encrypt content, if null content either unencrypted
	 * 		or keys retrieved according to local policy
	 * @param handle if null, new handle created with CCNHandle#open()
	 * @throws IOException if stream setup fails
	 */
	public CCNVersionedOutputStream(ContentName baseName, 
									ContentKeys keys, 
									CCNHandle handle) throws IOException {
		this(baseName, null, null, null, keys, handle);
	}

	/**
	 * Constructor for a CCN output stream writing under a versioned name.
	 * @param baseName name prefix under which to write content segments; if it is already
	 *   versioned, that version is used, otherwise a new version is added.
	 * @param locator key locator to use, if null, default for key is used.
	 * @param publisher key to use to sign the segments, if null, default for user is used.
	 * @param keys keys with which to encrypt content, if null content either unencrypted
	 * 		or keys retrieved according to local policy
	 * @param handle if null, new handle created with CCNHandle#open()
	 * @throws IOException if stream setup fails
	 */
	public CCNVersionedOutputStream(ContentName baseName, 
			  			   			KeyLocator locator, 
			  			   			PublisherPublicKeyDigest publisher,
			  			   			ContentKeys keys,
			  			   			CCNHandle handle) throws IOException {
		this(baseName, locator, publisher, null, keys, handle);
	}

	/**
	 * Constructor for a CCN output stream writing under a versioned name.
	 * @param baseName name prefix under which to write content segments; if it is already
	 *   versioned, that version is used, otherwise a new version is added.
	 * @param locator key locator to use, if null, default for key is used.
	 * @param publisher key to use to sign the segments, if null, default for user is used.
	 * @param type type to mark content (see ContentType), if null, DATA is used; if
	 * 			content encrypted, ENCR is used.
	 * @param keys keys with which to encrypt content, if null content either unencrypted
	 * 		or keys retrieved according to local policy
	 * @param handle if null, new handle created with CCNHandle#open().
	 * @throws IOException if stream setup fails
	 */
	public CCNVersionedOutputStream(ContentName baseName, 
									KeyLocator locator,
									PublisherPublicKeyDigest publisher, 
									ContentType type, 
									ContentKeys keys, 
									CCNHandle handle)
			throws IOException {
		/*
		 * The Flow Controller must register a Filter above the version no. for someone else's
		 * getLatestVersion interests to see this stream.
		 */
		this(baseName, locator, publisher, type, keys, 
			 new CCNFlowControl(VersioningProfile.cutTerminalVersion(baseName).first(), handle));
	}

	/**
	 * Low-level constructor used by clients that need to specify flow control behavior.
	 * @param baseName name prefix under which to write content segments; if it is already
	 *   versioned, that version is used, otherwise a new version is added.
	 * @param locator key locator to use, if null, default for key is used.
	 * @param publisher key to use to sign the segments, if null, default for user is used.
	 * @param type type to mark content (see ContentType), if null, DATA is used; if
	 * 			content encrypted, ENCR is used.
	 * @param keys keys with which to encrypt content, if null content either unencrypted
	 * 		or keys retrieved according to local policy
	 * @param flowControl flow controller used to buffer output content
	 * @throws IOException if flow controller setup fails
	 */
	public CCNVersionedOutputStream(ContentName baseName, 
									   KeyLocator locator, 
									   PublisherPublicKeyDigest publisher,
									   ContentType type, 
									   ContentKeys keys,
									   CCNFlowControl flowControl) throws IOException {
		super((VersioningProfile.hasTerminalVersion(baseName) ? baseName : VersioningProfile.addVersion(baseName)), 
				locator, publisher, type, keys, flowControl);
	}
}
