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
import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.profiles.CCNProfile;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;

public class MetadataProfile implements CCNProfile {

	public static final byte [] METADATA_MARKER = ContentName.componentParseNative(MARKER + "meta" + MARKER);
	
	public interface MetaNamer {
		public ContentName getMetaName(ContentName baseName, ArrayList<byte[]> metaName);
	}
	
	private static class LocalMetaNamer implements MetaNamer {
		public ContentName getMetaName(ContentName baseName, ArrayList<byte[]> metaName) {
			return new ContentName(metadataName(baseName), metaName);
		}
	}
	
	public static ContentName metadataName(ContentName baseName) {
		return new ContentName(baseName, METADATA_MARKER);
	}
	
	public static ContentName getLatestVersion(ContentName baseName, ContentName metaName, 
			long timeout, CCNHandle handle) throws IOException {
		return getLatestVersion(baseName, new LocalMetaNamer(), metaName.components(), timeout, handle);
	}
	
	public static ContentName getLatestVersion(ContentName baseName, MetaNamer namer, ArrayList<byte[]> metaName,
			 long timeout, CCNHandle handle) throws IOException {
		ContentName baseVersion = baseName;
		CCNInputStream checker = new CCNInputStream(baseName, handle);
		if (null == checker)
			return null;
		if (!VersioningProfile.containsVersion(baseVersion)) {
			ContentObject co = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, checker.publisher(), timeout, checker, handle);
			if (null == co)
				return null;
			baseVersion = co.name();
			baseVersion = SegmentationProfile.segmentRoot(baseVersion);
		}
		ContentName unversionedName = namer.getMetaName(baseVersion, metaName);
		ContentObject meta = VersioningProfile.getFirstBlockOfLatestVersion(unversionedName, null, checker.publisher(), timeout, checker, handle);
		if (null == meta)
			return VersioningProfile.addVersion(unversionedName);
		return meta.name();
	}
}
