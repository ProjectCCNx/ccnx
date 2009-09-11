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
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * A CCNInputStream that embodies the Versioning profile. If you
 * ask to open a name that is already versioned, it opens that
 * version for you. If you ask to open a name without a version,
 * it attempts to open the latest version of that name. If you
 * attempt to open a name with a segment marker on it as well,
 * it opens that version of that content at that segment.
 * 
 * The only behavior we have to change from superclass is that
 * involved in getting the first segment -- header or regular segment.
 * We need to make an interest that gets the latest version, and
 * then fills in the version information on the name we
 * are working with, to make sure we continue to get blocks
 * from the same version (even if, say someone writes another
 * version on top of us).
 * 
 * TODO -- outstanding concern -- depending on when the header arrives,
 * the response of this class may differ (not entirely clear). Given
 * that we're moving away from headers, perhaps, this may not be an
 * issue, but it brings up the point that we have to write unit tests
 * that seed ccnd or the repo with potentially complicating data and
 * make sure we can still retrieve it.
 */
public class CCNVersionedInputStream extends CCNInputStream {

	public CCNVersionedInputStream(ContentName name,
			Long startingSegmentNumber, PublisherPublicKeyDigest publisher,
			ContentKeys keys, CCNHandle library)
			throws XMLStreamException, IOException {
		super(name, startingSegmentNumber, publisher, keys, library);
	}

	public CCNVersionedInputStream(ContentName name,
			Long startingSegmentNumber, PublisherPublicKeyDigest publisher,
			CCNHandle library) throws XMLStreamException, IOException {
		super(name, startingSegmentNumber, publisher, library);
	}

	public CCNVersionedInputStream(ContentName name, PublisherPublicKeyDigest publisher,
			CCNHandle library) throws XMLStreamException, IOException {
		super(name, publisher, library);
	}

	public CCNVersionedInputStream(ContentName name) throws XMLStreamException,
			IOException {
		super(name);
	}

	public CCNVersionedInputStream(ContentName name, CCNHandle library)
			throws XMLStreamException, IOException {
		super(name, library);
	}

	public CCNVersionedInputStream(ContentName name, Long startingSegmentNumber)
			throws XMLStreamException, IOException {
		super(name, startingSegmentNumber);
	}

	public CCNVersionedInputStream(ContentObject firstSegment,
			CCNHandle library) throws XMLStreamException, IOException {
		super(firstSegment, library);
	}
	
	@Override
	protected ContentObject getFirstSegment() throws IOException {
		if (VersioningProfile.hasTerminalVersion(_baseName)) {
			// Get exactly this version
			return super.getFirstSegment();
		}
		Log.info("getFirstSegment: getting latest version of " + _baseName);
		ContentObject result = 
			VersioningProfile.getFirstBlockOfLatestVersion(_baseName, _startingSegmentNumber, _publisher, _timeout, this, _library);
		if (null != result){
			Log.info("getFirstSegment: retrieved latest version object " + result.name() + " type: " + result.signedInfo().getTypeName());
			_baseName = result.name().cut(_baseName.count() + 1);
		} else {
			Log.info("getFirstSegment: no segment available for latest version of " + _baseName);
		}
		return result;
	}
	
	protected boolean isFirstSegment(ContentName desiredName, ContentObject potentialFirstSegment) {
		return VersioningProfile.isVersionedFirstSegment(desiredName, potentialFirstSegment, _startingSegmentNumber);
	}
	
	public CCNTime getVersionAsTimestamp() throws VersionMissingException {
		if (null == _baseName)
			throw new VersionMissingException("Have not yet retrieved content name!");
		return VersioningProfile.getLastVersionAsTimestamp(_baseName);
	}
}
