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

package org.ccnx.ccn.profiles.metadata;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.profiles.CCNProfile;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

public class MetadataProfile implements CCNProfile {

	public static final byte [] METADATA_MARKER = ContentName.componentParseNative(MARKER + "meta" + MARKER);
	
	public interface MetaNamer {
		public ContentName getMetaName(ContentName baseName, byte[] metaName);
	}
	
	public static ContentName metadataName(ContentName baseName) {
		return new ContentName(baseName, METADATA_MARKER);
	}
	
	public static ContentName getLatestVersion(ContentName baseName, MetaNamer namer, byte[] metaName, PublisherPublicKeyDigest publisher, 
			 long timeout, ContentVerifier verifier, CCNHandle handle) throws IOException {
		ContentName baseVersion = baseName;
		if (!VersioningProfile.containsVersion(baseVersion)) {
			baseVersion = VersioningProfile.getLatestVersion(baseName, publisher, timeout, verifier, handle).name();
			baseVersion = SegmentationProfile.segmentRoot(baseVersion);
		}
		ContentName unversionedName = namer.getMetaName(baseVersion, metaName);
		ContentObject meta = VersioningProfile.getLatestVersion(unversionedName, publisher, timeout, verifier, handle);
		if (null == meta)
			return VersioningProfile.addVersion(unversionedName);
		return meta.name();
	}
}
