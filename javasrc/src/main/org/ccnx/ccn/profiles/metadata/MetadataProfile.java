/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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
import java.util.Arrays;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.profiles.CCNProfile;
import org.ccnx.ccn.profiles.CommandMarker;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;

/**
 * Includes routines to find correct version of a metadata file based on its base file
 */
public class MetadataProfile implements CCNProfile {

	public static final String METADATA_NAMESPACE = "META";
	public static final CommandMarker METADATA_MARKER = 
		CommandMarker.commandMarker(METADATA_NAMESPACE, "M");

	public static final byte [] OLD_METADATA_NAMESPACE = 
		(CCNProfile.MARKER + "meta" + CCNProfile.MARKER).getBytes();
	
	public static final byte [] HEADER_NAME = Component.parseNative(".header");

	/**
	 * This interface allows getLatestVersion of metadata within one of the supported meta
	 * namespaces.
	 */
	public interface MetaNamer {
		public ContentName getMetaName(ContentName baseName, ContentName metaName);
	}
	
	/**
	 * General getter for generic metadata
	 */
	private static class LocalMetaNamer implements MetaNamer {
		public ContentName getMetaName(ContentName baseName, ContentName metaName) {
			return metadataName(baseName).append(metaName);
		}
	}
	
	/**
	 * Get a standard metadata path for a base file
	 * @param baseName the base file
	 * @return metadata path for base file
	 */
	public static ContentName metadataName(ContentName baseName) {
		return new ContentName(baseName, METADATA_MARKER);
	}
	
	/**
	 * Get the latest version of a metadata file which is associated with a base file. Before
	 * searching for the metadata version, we find the latest version of the base file.  This
	 * call requires the calling function to check for a terminal version before attempting to
	 * retrieve the metadata content.  If the base file name does not have a version, this
	 * function will return null.  If the metadata file for the latest version of the base file
	 * does not exist, this function will return a content name without a terminal version.
	 * 
	 * Example where both versions are found:
	 * baseName/version/metadataMarkers/version
	 * 
	 * Example where only the base file name version is found:
	 * baseName/version/metadataMarkers
	 * 
	 * Example where no base file version is found:
	 * null
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
		return getLatestVersion(baseName, new LocalMetaNamer(), metaName, timeout, handle);
	}
	
	/**
	 * This call requires the calling function to check for a terminal version before
	 * attempting to retrieve the metadata content.
	 * 
	 * If the
	 * base file name does not have a version, this function will return null.  If the metadata
	 * file for the latest version of the base file does not exist, this function will return a
	 * content name without a terminal version.
	 * 
	 * Example where both versions are found:
	 * baseName/version/metadataMarkers/version
	 * 
	 * Example where only the base file name version is found:
	 * baseName/version/metadataMarkers
	 * 
	 * Example where no base file version is found:
	 * null
	 * 
	 * 
	 * @param baseName
	 * @param namer
	 * @param metaName
	 * @param timeout
	 * @param handle
	 * @return
	 * @throws IOException
	 */
	public static ContentName getLatestVersion(ContentName baseName, MetaNamer namer, ContentName metaName,
			 long timeout, CCNHandle handle) throws IOException {
		ContentName baseVersion = baseName;
		//removing the stream instance:  1 - we are not actually attempting to get the stream, we just want to discover the latest version
		//CCNInputStream checker = new CCNInputStream(baseName, handle);
		//if (null == checker)
		//	return null;
		if (!VersioningProfile.containsVersion(baseVersion)) {
			//ContentObject co = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, checker.publisher(), timeout, checker, handle);
			ContentObject co = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, timeout, null, handle);
			if (null == co)
				return null;
			baseVersion = co.name();
			baseVersion = SegmentationProfile.segmentRoot(baseVersion);
		}
		ContentName unversionedName = namer.getMetaName(baseVersion, metaName);
		//ContentObject meta = VersioningProfile.getFirstBlockOfLatestVersion(unversionedName, null, checker.publisher(), timeout, checker, handle);
		ContentObject meta = VersioningProfile.getFirstBlockOfLatestVersion(unversionedName, null, null, timeout, null, handle);
		if (null == meta) {
			//we did not find a version of the metadata content...  do not append the current time. This can be
			//misleading to the calling application because it cannot discern between metadata that exists and metadata that
			//is not available.
			//return VersioningProfile.addVersion(unversionedName);
			return unversionedName;
		}
		return meta.name();
	}

	/**
	 * Check to see if we have (a block of) the header. Headers are also versioned.
	 * @param baseName The name of the object whose header we are looking for (including version, but
	 * 			not including segmentation information).
	 * @param headerName The name of the object we think might be a header block (can include
	 * 			segmentation).
	 * @return
	 */
	public static boolean isHeader(ContentName baseName, ContentName headerName) {
		if (!baseName.isPrefixOf(headerName)) {
			return false;
		}
		return MetadataProfile.isHeader(headerName);
	}

	/**
	 * Slightly more heuristic isHeader; looks to see if this is a segment of something that
	 * ends in the header name (and version), without knowing the prefix..
	 */
	public static boolean isHeader(ContentName potentialHeaderName) {
		
		if (SegmentationProfile.isSegment(potentialHeaderName)) {
			potentialHeaderName = SegmentationProfile.segmentRoot(potentialHeaderName);
		}
		
		// Header itself is likely versioned.
		if (VersioningProfile.isVersionComponent(potentialHeaderName.lastComponent())) {
			potentialHeaderName = potentialHeaderName.parent();
		}
		
		if (potentialHeaderName.count() < 2)
			return false;
		
		if (!Arrays.equals(potentialHeaderName.lastComponent(), MetadataProfile.HEADER_NAME))
			return false;
		
		if (!Arrays.equals(potentialHeaderName.component(potentialHeaderName.count()-2), 
				METADATA_MARKER.getBytes()))
			return false;
		
		return true;
	}

	/**
	 * Move header from <content>/<version> as its name to
	 * <content>/<version>/_metadata_marker_/HEADER/<version>
	 * where the second version is imposed by the use of versioning
	 * network objects (i.e. this function should return up through HEADER above)
	 * Header name generation may want to move to a MetadataProfile.
	 * 
	 * @param name
	 * @return
	 */
	public static ContentName headerName(ContentName name) {
		// Want to make sure we don't add a header name
		// to a fragment. Go back up to the fragment root.
		// Currently no header name added.
		if (SegmentationProfile.isSegment(name)) {
			name = SegmentationProfile.segmentRoot(name);
		}
		return new ContentName(name, METADATA_MARKER, MetadataProfile.HEADER_NAME);
	}
	
	public static ContentName oldHeaderName(ContentName name) {
		if (SegmentationProfile.isSegment(name)) {
			name = SegmentationProfile.segmentRoot(name);
		}
		return new ContentName(name, OLD_METADATA_NAMESPACE, MetadataProfile.HEADER_NAME);
	}
}
