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

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNSegmenter;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


public class CCNVersionedOutputStream extends CCNOutputStream {

	public CCNVersionedOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, CCNHandle library)
			throws IOException {
		this(name, locator, publisher, null, new CCNSegmenter(new CCNFlowControl(name, library)));
	}

	public CCNVersionedOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, ContentKeys keys, CCNHandle library)
			throws IOException {
		/*
		 * The Flow Controller must register a Filter above the version no. for someone else's
		 * getLatestVersion interests to see this stream.
		 */
		this(name, locator, publisher, null, new CCNSegmenter(
				new CCNFlowControl(VersioningProfile.cutTerminalVersion(name).first(), library), null, keys));
	}

	public CCNVersionedOutputStream(ContentName name, CCNHandle library)
			throws IOException {
		this(name, null, null, library);
	}

	/**
	 * Assume if name is already versioned, the caller knows what name it
	 * wants to write. Otherwise generate a new version number for it.
	 * @param name
	 * @param locator
	 * @param publisher
	 * @param segmenter
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	public CCNVersionedOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, ContentType type, CCNSegmenter segmenter)
			throws IOException {
		super((VersioningProfile.hasTerminalVersion(name) ? name : VersioningProfile.addVersion(name)), 
				locator, publisher, type, segmenter);
	}

	public CCNVersionedOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, ContentType type, CCNFlowControl flowControl)
			throws IOException {
		this(name, locator, publisher, type, new CCNSegmenter(flowControl));
	}
}
