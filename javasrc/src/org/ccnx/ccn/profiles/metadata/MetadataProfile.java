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
import org.ccnx.ccn.profiles.CommandMarker;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;

/**
 * Includes routines to find correct version of a metadata file based on its base file
 */
public class MetadataProfile implements CCNProfile {

	public static final String METADATA_NAMESPACE = "meta";
	public static final CommandMarker METADATA_MARKER = 
		CommandMarker.commandMarker(METADATA_NAMESPACE, "M");

	/**
	 * This interface allows getLatestVersion of metadata within one of the supported meta
	 * namespaces.
	 */
	public interface MetaNamer {
		public ContentName getMetaName(ContentName baseName, ArrayList<byte[]> metaName);
	}
	
	/**
	 * General getter for generic metadata
	 */
	private static class LocalMetaNamer implements MetaNamer {
		public ContentName getMetaName(ContentName baseName, ArrayList<byte[]> metaName) {
			return new ContentName(metadataName(baseName), metaName);
		}
	}
	
	/**
	 * Get a standard metadata path for a base file
	 * @param baseName the base file
	 * @return metadata path for base file
	 */
	public static ContentName metadataName(ContentName baseName) {
		return new ContentName(baseName, METADATA_MARKER.getBytes());
	}
	
	/**
	 * Get the latest version of a metadata file which is associated with a base file. Before
	 * searching for the metadata version, we find the latest version of the base file
	 * 
	 * @param baseName the base file
	 * @param metaName the meta file. This should be a ContentName containing only the relative path
	 * 				   from the base file.
	 * @param timeout  time to search for the latest version in ms. Applies separately to each latest
	 *                 version search.
	 * @param handle   CCNHandle to use for search.
	 * @return
	 * @throws IOException
	 */
	public static ContentName getLatestVersion(ContentName baseName, ContentName metaName, 
			long timeout, CCNHandle handle) throws IOException {
		return getLatestVersion(baseName, new LocalMetaNamer(), metaName.components(), timeout, handle);
	}
	
	/**
	 * Internal version of getLatestVersion
	 * @param baseName
	 * @param namer
	 * @param metaName
	 * @param timeout
	 * @param handle
	 * @return
	 * @throws IOException
	 */
	protected static ContentName getLatestVersion(ContentName baseName, MetaNamer namer, ArrayList<byte[]> metaName,
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
