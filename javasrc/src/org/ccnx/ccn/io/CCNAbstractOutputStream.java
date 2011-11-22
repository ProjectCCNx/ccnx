/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
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
import java.io.OutputStream;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNSegmenter;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.security.access.AccessControlManager;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * This abstract class is the superclass of all classes for writing an output stream of
 * bytes segmented and stored in CCN. 
 * 
 * @see SegmentationProfile for description of CCN segmentation
 */
public abstract class CCNAbstractOutputStream extends OutputStream {

	protected CCNHandle _handle = null;
	protected CCNSegmenter _segmenter = null;
	/** 
	 * The name for the content fragments, up to just before the sequence number.
	 */
	protected ContentName _baseName = null;
	/**
	 * type of content null == DATA (or ENCR if encrypted)
	 */
	protected ContentType _type; 

	protected ContentKeys _keys;
	protected KeyLocator _locator;
	protected PublisherPublicKeyDigest _publisher;

	/**
	 * Base constructor for all output streams.
	 * The behavior of an output stream derived class is determined
	 * largely by its buffering, segmentation configuration (embodied
	 * in a CCNSegmenter) and flow control (embodied in a CCNFlowControl,
	 * contained within the segmenter), as well as the way it constructs is names.
	 * @param baseName specifies the base name to write. Can be null, and set later.
	 * @param type specifies the type of data to write. 
	 * @param locator the key locator to be used in written segments. If null, default
	 * 		is used.
	 * @param publisher the key with which to sign the output. If null, default for user
	 * 		is used.
	 * @param segmenter The segmenter used to construct and sign output segments, specified
	 *    by subclasses to provide various kinds of behavior.
	 */
	public CCNAbstractOutputStream(ContentName baseName,
								   KeyLocator locator, 
								   PublisherPublicKeyDigest publisher,
								   ContentType type,
								   ContentKeys keys,
								   CCNSegmenter segmenter) {
		super();
		_baseName = baseName;
		_type = type;
		_segmenter = segmenter;
		_handle = _segmenter.getLibrary();
		if (null == _handle) {
			_handle = CCNHandle.getHandle();
		}

		// If these are null, the handle defaults will be used.
		_locator = locator;
		if (null == locator)
			_locator = _handle.keyManager().getKeyLocator(publisher);

		_publisher = publisher;		
		
		// Initialize keys here now. 
		_keys = keys;
		Log.info(Log.FAC_IO, "CCNAbstractOutputStream: {0} blocksize is {1}", baseName, _segmenter.getBlockSize());
	}
	
	/**
	 * Special purpose constructor used in tests.
	 */
	protected CCNAbstractOutputStream() {}	
	
	/**
	 * Override in subclasses that need to do something special with start writes 
	 * (see CCNFlowControl#startWrite(ContentName, Shape)). They should call this
	 * superclass method, though, to initialize keys (may need to move this later).
	 * @throws IOException
	 */
	protected void startWrite() throws IOException {
		if (null == _keys) {
			Log.info(Log.FAC_IO, "CCNAbstractOutputStream: startWrite -- searching for keys.");
			_keys = AccessControlManager.keysForOutput(_baseName, _publisher, getType(), _handle);
		}
	}

	/**
	 * Method for streams used by CCNFilterListeners to output a block
	 * in response to an Interest callback.
	 * We've received an Interest prior to setting up this stream. Use
	 * a method to push this Interest, rather than passing it in in the 
	 * constructor to make sure we have completed initializing the stream,
	 * and to limit the number of constructor types.
	 * (If the Interest doesn't match this stream's content, 
	 * no initial block will be output; the stream will wait for matching Interests prior
	 * to writing its blocks.)
	 * @param outstandingInterest An interest received prior to constructing
	 *   this stream, ideally on the same CCNHandle that the stream is using
	 *   for output. Only one stream should attempt to put() a block in response
	 *   to this Interest; it is up to the caller to make sure that is the case.
	 */
	public void addOutstandingInterest(Interest outstandingInterest) {
		_segmenter.getFlowControl().handleInterest(outstandingInterest);
	}
	
	@Override
	public void write(byte[] b) throws IOException {
		write(b, 0, b.length);
	}

	@Override
	public void write(int b) throws IOException {
		byte buf[] = {(byte)b};
		write(buf, 0, 1);
	}

	/**
	 * @return The name used as a prefix for segments of this stream (not including the segment number).
	 */
	public ContentName getBaseName() {
		return _baseName;
	}

	/**
	 * @return The version of the stream being written, if its name is versioned.
	 */
	public CCNTime getVersion() {
		if (null == _baseName) 
			return null;
		return VersioningProfile.getTerminalVersionAsTimestampIfVersioned(_baseName);
	}
	
	public ContentType getType() { return _type; }

	/**
	 * Return the first segment of this stream. 
	 * 
	 * @return The first segment of this stream or null if no segments generated yet
	 */
	public ContentObject getFirstSegment() {
		if (null != _segmenter) {	
			return _segmenter.getFirstSegment();
		} else {
			return null;
		}
	}
	
	/**
	 *
	 * Returns the digest of the first segment of this stream. 
	 * 
	 * @return The digest of the first segment of this stream or null if no segments generated yet.
	 */
	public byte[] getFirstDigest() {
		ContentObject firstSegment = _segmenter.getFirstSegment();
		if (null != firstSegment) {
			return firstSegment.digest();
		} else {
			return null;
		}
	}
	
	/**
	 * Returns the first segment number for this stream.
	 * @return The index of the first segment of stream data or null if no segments generated yet.
	 */
	public Long firstSegmentNumber() {
		ContentObject firstSegment = _segmenter.getFirstSegment();
		if (null != firstSegment) {
			return SegmentationProfile.getSegmentNumber(firstSegment.name());
		} else {
			return null;
		}
	}

	/**
	 * @return The CCNSegmenter responsible for segmenting and signing stream content. 
	 */
	protected CCNSegmenter getSegmenter() {
		return _segmenter;
	}
	
	/**
	 * Set the timeout that will be used for all content writes on this stream.
	 * Default is 10 seconds
	 * @param timeout in milliseconds
	 */
	public void setTimeout(int timeout) {
		getSegmenter().setTimeout(timeout);
	}
}
