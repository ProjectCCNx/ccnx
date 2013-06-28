/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
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
import java.util.EnumSet;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * A CCNInputStream that reads and writes versioned streams.
 * Names are versioned using the VersioningProfile. If you
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
 */
public class CCNVersionedInputStream extends CCNInputStream {

	/**
	 * Set up an input stream to read segmented CCN content under a given versioned name. 
	 * Content is assumed to be unencrypted, or keys will be retrieved automatically via another
	 * process. 
	 * Will use the default handle given by CCNHandle#getHandle().
	 * Note that this constructor does not currently retrieve any
	 * data; data is not retrieved until read() is called. This will change in the future, and
	 * this constructor will retrieve the first block.
	 * 
	 * @param baseName Name to read from. If it ends with a version, will retrieve that
	 * specific version. If not, will find the latest version available. If it ends with
	 * both a version and a segment number, will start to read from that segment of that version.
	 * @throws IOException Not currently thrown, will be thrown when constructors retrieve first block.
	 */
	public CCNVersionedInputStream(ContentName baseName) throws IOException {
		super(baseName);
	}

	/**
	 * Set up an input stream to read segmented CCN content under a given versioned name. 
	 * Content is assumed to be unencrypted, or keys will be retrieved automatically via another
	 * process. 
	 * Will use the default handle given by CCNHandle#getHandle().
	 * Note that this constructor does not currently retrieve any
	 * data; data is not retrieved until read() is called. This will change in the future, and
	 * this constructor will retrieve the first block.
	 * 
	 * @param baseName Name to read from. If it ends with a version, will retrieve that
	 * specific version. If not, will find the latest version available. If it ends with
	 * both a version and a segment number, will start to read from that segment of that version.
	 * @param handle The CCN handle to use for data retrieval. If null, the default handle
	 * 		given by CCNHandle#getHandle() will be used.
	 * @throws IOException Not currently thrown, will be thrown when constructors retrieve first block.
	 */
	public CCNVersionedInputStream(ContentName baseName, CCNHandle handle)
										throws IOException {
		super(baseName, handle);
	}

	/**
	 * Set up an input stream to read segmented CCN content under a given versioned name. 
	 * Content is assumed to be unencrypted, or keys will be retrieved automatically via another
	 * process. 
	 * Will use the default handle given by CCNHandle#getHandle().
	 * Note that this constructor does not currently retrieve any
	 * data; data is not retrieved until read() is called. This will change in the future, and
	 * this constructor will retrieve the first block.
	 * 
	 * @param baseName Name to read from. If it ends with a version, will retrieve that
	 * specific version. If not, will find the latest version available. If it ends with
	 * both a version and a segment number, will start to read from that segment of that version.
	 * @param publisher The key we require to have signed this content. If null, will accept any publisher
	 * 				(subject to higher-level verification).
	 * @param handle The CCN handle to use for data retrieval. If null, the default handle
	 * 		given by CCNHandle#getHandle() will be used.
	 * @throws IOException Not currently thrown, will be thrown when constructors retrieve first block.
	 */
	public CCNVersionedInputStream(ContentName baseName, PublisherPublicKeyDigest publisher,
			CCNHandle handle) throws IOException {
		super(baseName, publisher, handle);
	}

	/**
	 * Set up an input stream to read segmented CCN content under a given versioned name. 
	 * Content is assumed to be unencrypted, or keys will be retrieved automatically via another
	 * process. 
	 * Will use the default handle given by CCNHandle#getHandle().
	 * Note that this constructor does not currently retrieve any
	 * data; data is not retrieved until read() is called. This will change in the future, and
	 * this constructor will retrieve the first block.
	 * 
	 * @param baseName Name to read from. If it ends with a version, will retrieve that
	 * specific version. If not, will find the latest version available. If it ends with
	 * both a version and a segment number, will start to read from that segment of that version.
	 * @param startingSegmentNumber Alternative specification of starting segment number. If
	 * 		null, will be SegmentationProfile#baseSegment().
	 * @param handle The CCN handle to use for data retrieval. If null, the default handle
	 * 		given by CCNHandle#getHandle() will be used.
	 * @throws IOException Not currently thrown, will be thrown when constructors retrieve first block.
	 */
	public CCNVersionedInputStream(ContentName baseName, Long startingSegmentNumber, CCNHandle handle)
			throws IOException {
		super(baseName, startingSegmentNumber, handle);
	}

	/**
	 * Set up an input stream to read segmented CCN content under a given versioned name. 
	 * Content is assumed to be unencrypted, or keys will be retrieved automatically via another
	 * process. 
	 * Will use the default handle given by CCNHandle#getHandle().
	 * Note that this constructor does not currently retrieve any
	 * data; data is not retrieved until read() is called. This will change in the future, and
	 * this constructor will retrieve the first block.
	 * 
	 * @param baseName Name to read from. If it ends with a version, will retrieve that
	 * specific version. If not, will find the latest version available. If it ends with
	 * both a version and a segment number, will start to read from that segment of that version.
	 * @param startingSegmentNumber Alternative specification of starting segment number. If
	 * 		null, will beSegmentationProfile#baseSegment().
	 * @param publisher The key we require to have signed this content. If null, will accept any publisher
	 * 				(subject to higher-level verification).
	 * @param handle The CCN handle to use for data retrieval. If null, the default handle
	 * 		given by CCNHandle#getHandle() will be used.
	 * @throws IOException Not currently thrown, will be thrown when constructors retrieve first block.
	 */
	public CCNVersionedInputStream(ContentName baseName,
			Long startingSegmentNumber, PublisherPublicKeyDigest publisher,
			CCNHandle handle) throws IOException {
		super(baseName, startingSegmentNumber, publisher, handle);
	}

	/**
	 * Set up an input stream to read segmented CCN content under a given versioned name. 
	 * Will use the default handle given by CCNHandle#getHandle().
	 * Note that this constructor does not currently retrieve any
	 * data; data is not retrieved until read() is called. This will change in the future, and
	 * this constructor will retrieve the first block.
	 * 
	 * @param baseName Name to read from. If it ends with a version, will retrieve that
	 * specific version. If not, will find the latest version available. If it ends with
	 * both a version and a segment number, will start to read from that segment of that version.
	 * @param startingSegmentNumber Alternative specification of starting segment number. If
	 * 		null, will be SegmentationProfile#baseSegment().
	 * @param publisher The key we require to have signed this content. If null, will accept any publisher
	 * 				(subject to higher-level verification).
	 * @param keys The keys to use to decrypt this content. If null, assumes content unencrypted, or another
	 * 				process will be used to retrieve the keys.
	 * @param handle The CCN handle to use for data retrieval. If null, the default handle
	 * 		given by CCNHandle#getHandle() will be used.
	 * @throws IOException Not currently thrown, will be thrown when constructors retrieve first block.
	 */
	public CCNVersionedInputStream(ContentName baseName,
			Long startingSegmentNumber, PublisherPublicKeyDigest publisher,
			ContentKeys keys, CCNHandle handle)
			throws IOException {
		super(baseName, startingSegmentNumber, publisher, keys, handle);
	}

	/**
	 * Set up an input stream to read segmented CCN content starting with a given
	 * ContentObject that has already been retrieved.  Content is assumed
	 * to be unencrypted, or keys will be retrieved automatically via another
	 * process.
	 * @param startingSegment The first segment to read from. If this is not the
	 * 		first segment of the stream, reading will begin from this point.
	 * 		We assume that the signature on this segment was verified by our caller.
	 * @param flags any stream flags that must be set to handle even this first block (otherwise
	 * 	they can be set with setFlags prior to read). Can be null.
	 * @param handle The CCN handle to use for data retrieval. If null, the default handle
	 * 		given by CCNHandle#getHandle() will be used.
	 * @throws IOException if startingSegment does not contain a valid segment ID
	 */
	public CCNVersionedInputStream(ContentObject startingSegment,
			EnumSet<FlagTypes> flags, CCNHandle handle) throws IOException {
		super(startingSegment, flags, handle);
	}
	
	/**
	 * Set up an input stream to read segmented CCN content starting with a given
	 * ContentObject that has already been retrieved.  
	 * @param startingSegment The first segment to read from. If this is not the
	 * 		first segment of the stream, reading will begin from this point.
	 * 		We assume that the signature on this segment was verified by our caller.
	 * @param keys The keys to use to decrypt this content. Null if content unencrypted, or another
	 * 				process will be used to retrieve the keys.
	 * @param flags any stream flags that must be set to handle even this first block (otherwise
	 * 	they can be set with setFlags prior to read). Can be null.
	 * @param handle The CCN handle to use for data retrieval. If null, the default handle
	 * 		given by CCNHandle#getHandle() will be used.
	 * @throws IOException if startingSegment does not contain a valid segment ID
	 */
	public CCNVersionedInputStream(ContentObject startingSegment, ContentKeys keys, EnumSet<FlagTypes> flags, CCNHandle handle) throws IOException {
		super(startingSegment, keys, flags, handle);
	}
	
	/**
	 * Implementation of getFirstSegment() that expects segments to be versioned. If a version
	 * (and optionally a segment) is specified in the name, gets that specific version (and segment). Otherwise,
	 * gets the latest version available. Uses VersioningProfile#getFirstBlockOfLatestVersion(ContentName, Long, PublisherPublicKeyDigest, long, org.ccnx.ccn.ContentVerifier, CCNHandle).
	 * @throws IOException If no block found (NoMatchingContentFoundException}), or there is
	 *   an error retrieving the block.
	 */
	@Override
	public ContentObject getFirstSegment() throws IOException {
		if (VersioningProfile.hasTerminalVersion(_baseName)) {
			// Get exactly this version
			return super.getFirstSegment();
		}
		Log.info(Log.FAC_IO, "getFirstSegment: getting latest version of {0}", _baseName);
		ContentObject result = 
			VersioningProfile.getFirstBlockOfLatestVersion(_baseName, _startingSegmentNumber, _publisher, _timeout, _handle.defaultVerifier(), _handle);
		if (null != result){
            if (Log.isLoggable(Log.FAC_IO, Level.INFO))
                Log.info(Log.FAC_IO, "getFirstSegment: retrieved latest version object {0} type: {1}", result.name(), result.signedInfo().getTypeName());
			_baseName = SegmentationProfile.segmentRoot(result.name());
		} else {
			Log.info(Log.FAC_IO, "getFirstSegment: no segment available for latest version of {0}", _baseName);
		}
		return result;
	}
	
	/**
	 * Determines whether a given content object is the first block of the versioned stream specified.
	 */
	@Override
	protected boolean isFirstSegment(ContentName desiredName, ContentObject potentialFirstSegment) {
		return VersioningProfile.isVersionedFirstSegment(desiredName, potentialFirstSegment, _startingSegmentNumber);
	}
	
	/**
	 * Convenience method.
	 * @return The version of this content as a CCNTime.
	 * @throws VersionMissingException If we do not yet have a versioned content name.
	 */
	public CCNTime getVersionAsTimestamp() throws VersionMissingException {
		if (null == _baseName)
			throw new VersionMissingException("Have not yet retrieved content name!");
		return VersioningProfile.getLastVersionAsTimestamp(_baseName);
	}
}
