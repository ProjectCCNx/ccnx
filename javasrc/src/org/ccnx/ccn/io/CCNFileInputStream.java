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
import java.util.EnumSet;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.CCNNetworkObject;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.Header;
import org.ccnx.ccn.io.content.UpdateListener;
import org.ccnx.ccn.io.content.Header.HeaderObject;
import org.ccnx.ccn.profiles.metadata.MetadataProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * A CCN input stream that expects content names to be versioned, and streams to have a Header
 * containing file-level metadata about each stream. See CCNVersionedInputStream for
 * a description of versioning behavior, and CCNFileOutputStream for a description of
 * header information. The header is read asynchronously, and may not be available at all until the complete
 * stream has been written (in other words, the publisher typically writes the header last).
 * Stream data can be read normally before the header has been read, and the consumer
 * may opt to ignore the header completely, in which case this acts exactly like a 
 * CCNVersionedInputStream. In fact, a CCNVersionedInputStream can be used
 * to read data read by CCNFileOutputStream (except for the header). Using a
 * CCNFileInputStream to read something not written by a CCNFileOutputStream or one
 * of its subclasses (in other words, something without a header) will still try to retrieve
 * the (nonexistent) header in the background, but will not cause an error unless someone tries to access
 * the header data itself. 
 * 
 * Headers are named according to definitions in the SegmentationProfile.
 *
 */
public class CCNFileInputStream extends CCNVersionedInputStream implements UpdateListener {

	/**
	 * The header information for that object, once
	 * we've read it. 
	 */
	protected HeaderObject _header = null;
	
	/**
	 * Temporary backwards-compatibility move...
	 */
	protected HeaderObject _oldHeader = null;

	
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
	public CCNFileInputStream(ContentName baseName) throws IOException {
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
	public CCNFileInputStream(ContentName baseName, CCNHandle handle)
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
	public CCNFileInputStream(ContentName baseName, PublisherPublicKeyDigest publisher,
			CCNHandle handle) throws IOException {
		this(baseName, null, publisher, handle);
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
	public CCNFileInputStream(ContentName baseName, Long startingSegmentNumber, CCNHandle handle)
										throws IOException {
		this(baseName, startingSegmentNumber, null, handle);
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
	 * @param publisher The key we require to have signed this content. If null, will accept any publisher
	 * 				(subject to higher-level verification).
	 * @param handle The CCN handle to use for data retrieval. If null, the default handle
	 * 		given by CCNHandle#getHandle() will be used.
	 * @throws IOException Not currently thrown, will be thrown when constructors retrieve first block.
	 */
	public CCNFileInputStream(ContentName baseName, Long startingSegmentNumber,
			PublisherPublicKeyDigest publisher, CCNHandle handle)
			throws IOException {
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
	public CCNFileInputStream(ContentName baseName, Long startingSegmentNumber,
			PublisherPublicKeyDigest publisher, ContentKeys keys, CCNHandle handle)
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
	 * @throws IOException
	 */
	public CCNFileInputStream(ContentObject startingSegment, EnumSet<FlagTypes> flags, CCNHandle handle)
			throws IOException {
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
	 * @throws IOException
	 */
	public CCNFileInputStream(ContentObject startingSegment, 
				ContentKeys keys, EnumSet<FlagTypes> flags, CCNHandle handle) throws IOException {
		super(startingSegment, keys, flags, handle);
	}
	
	/**
	 * @return true if we have started the header retrieval process. To begin the process,
	 *   we must first know what version of the content we are reading.
	 */
	protected boolean headerRequested() {
		return (null != _header);
	}
	
	/**
	 * 
	 * @return true if we have retrieved the header.
	 */
	public boolean hasHeader() {
		return (headerRequested() && (_header.available() && !_header.isGone()) || 
				((null != _oldHeader) && (_oldHeader.available() && !_oldHeader.isGone())));
	}
	
	/**
	 * Callers who wish to access the header should call this first; it will wait until the header
	 * has been successfully retrieved (if the retrieval has started). 
	 * @throws ContentNotReadyException if we have not requested the header yet.
	 */
	public void waitForHeader(Long timeout) throws ContentNotReadyException {
		if (!headerRequested())
			throw new ContentNotReadyException("Not enough information available to request header!");
		_header.waitForData((null != timeout) ? timeout : SystemConfiguration.getDefaultTimeout()); // should take timeout
		// wait for old header implicitly; probably too long.
	}
	
	public void waitForHeader() throws ContentNotReadyException {
		waitForHeader(null);
	}
	
	/**
	 * Accesses the header data if it has been requested.
	 * @return the Header for this stream.
	 * @throws ContentNotReadyException if we have not retrieved the header yet, or it hasn't been requested.
	 * @throws ContentGoneException if the header has been deleted.
	 * @throws ErrorStateException 
	 */
	public Header header() throws ContentNotReadyException, ContentGoneException, ErrorStateException {
		if (!headerRequested())
			throw new ContentNotReadyException("Not enough information available to request header!");
		if (_header.available() || (null == _oldHeader)) {
			return _header.header();
		}
		return _oldHeader.header();
	}
	
	/**
	 * Request the header in the background.
	 * @param baseName name of the content, including the version, from which the header name will be derived.
	 * @param publisher expected publisher
	 * @throws IOException If the header cannot be retrieved.
	 * @throws ContentDecodingException If the header cannot be decoded.
	 */
	protected void requestHeader(ContentName baseName, PublisherPublicKeyDigest publisher) 
			throws ContentDecodingException, IOException {
		if (headerRequested())
			return; // done already
		// Ask for the header, but update it in the background, as it may not be there yet.
		_header = new HeaderObject(MetadataProfile.headerName(baseName), null, null, publisher, null, _handle);
		if (Log.isLoggable(Log.FAC_IO, Level.INFO))
			Log.info(Log.FAC_IO, "Retrieving header : " + _header.getBaseName() + " in background.");
		_header.updateInBackground(false, this);
		
		if (SystemConfiguration.OLD_HEADER_NAMES) {
			_oldHeader = new HeaderObject(MetadataProfile.oldHeaderName(baseName), null, null, publisher, null, _handle);
			if (Log.isLoggable(Log.FAC_IO, Level.INFO))
				Log.info(Log.FAC_IO, "Retrieving header under old name: " + _oldHeader.getBaseName() + " in background.");
			_oldHeader.updateInBackground(false, this);
		}
	}

	/**
	 * Once we have retrieved the first segment of this stream using CCNVersionedInputStream#getFirstSegment(),
	 * initiate header retrieval.
	 */
	@Override
	public ContentObject getFirstSegment() throws IOException {
		// Give up efficiency where we try to detect auto-caught header, and just
		// use superclass method to really get us a first content block, then
		// go after the header. Later on we can worry about re-adding the optimization.
		// This helps because the superclass method dereferences any links, so when we retrieve
		// the header we use the resolved name of the content to do so, which is more likely
		// to be correct.
		ContentObject result = super.getFirstSegment();
		if (null == result) {
			throw new IOException("Cannot retrieve first block of " + _baseName + "!");
		}
		// Have to wait to request the header till we know what version we're looking for.
		// Don't want to request the header if this stream is LINK or GONE.
		if (!headerRequested() && (!result.isGone()) && (!result.isLink())) {
			requestHeader(_baseName, result.signedInfo().getPublisherKeyID());
		}
		return result;
	}

	@Override
	public long skip(long n) throws IOException {
		
		if (Log.isLoggable(Log.FAC_IO, Level.FINE))
            Log.fine(Log.FAC_IO, "in skip({0})", n);
		
		if (n < 0) {
			return 0;
		}
		
		if (!hasHeader()){
			return super.skip(n);
		}
		
		int[] toGetBlockAndOffset = null;
		long toGetPosition = 0;
		
		long currentBlock = -1;
		int currentBlockOffset = 0;
		long currentPosition = 0;
		
		if (_currentSegment == null) {
			//we do not have a block already
			//skip position is n
			currentPosition = 0;
			toGetPosition = n;
		} else {
		    //we already have a block...  need to handle some tricky cases
			currentBlock = segmentNumber();
			currentBlockOffset = (int)super.tell();
			currentPosition = _header.segmentLocationToPosition(currentBlock, currentBlockOffset);
			toGetPosition = currentPosition + n;
		}
		//make sure we don't skip past end of the object
		if (toGetPosition >= _header.length()) {
			toGetPosition = _header.length();
			_atEOF = true;
		}
			
		toGetBlockAndOffset = _header.positionToSegmentLocation(toGetPosition);
		
		//make sure the position makes sense
		//is this a valid block?
		if (toGetBlockAndOffset[0] >= _header.segmentCount()){
			//this is not a valid block number, subtract 1
			if (toGetBlockAndOffset[0] > 0) {
				toGetBlockAndOffset[0]--;
			}
			//now we have the last block if the position was too long
		}
		
		//is the offset > 0?
		if (toGetBlockAndOffset[1] < 0) {
			toGetBlockAndOffset[1] = 0;
		}
			
		//now we should get the block and check the offset
		// TODO: once first block is always set in a constructor this conditional can be removed
		if (_currentSegment == null)
			setFirstSegment(getSegment(toGetBlockAndOffset[0]));
		else
			setCurrentSegment(getSegment(toGetBlockAndOffset[0]));
		if (_currentSegment == null) {
			//we had an error getting the block
			throw new IOException("Error getting block "+toGetBlockAndOffset[0]+" in CCNInputStream.skip("+n+")");
		} else {
			//we have a valid block!
			//first make sure the offset is valid
			if (toGetBlockAndOffset[1] <= _currentSegment.contentLength()) {
				//this is good, our offset is somewhere in this block
			} else {
				//our offset is past the end of our block, reset to the end.
				toGetBlockAndOffset[1] = _currentSegment.contentLength();
			}
			_segmentReadStream.skip(toGetBlockAndOffset[1]);
			return _header.segmentLocationToPosition(toGetBlockAndOffset[0], toGetBlockAndOffset[1]) - currentPosition;
		}
	}
	
	@Override
	protected int segmentCount() throws IOException {
		if (hasHeader()) {
            return _header.segmentCount();
		}
		return super.segmentCount();
	}

	@Override
	public void seek(long position) throws IOException {
        if (Log.isLoggable(Log.FAC_IO, Level.FINE))
            Log.fine(Log.FAC_IO, "Seeking stream to {0}: have header? {1}", position, hasHeader());
		if (hasHeader()) {
			int [] blockAndOffset = _header.positionToSegmentLocation(position);
			if (Log.isLoggable(Log.FAC_IO, Level.FINE)) {
				Log.fine(Log.FAC_IO, "seek:  position: {0} block: {1} offset: {2} currentSegment: {3}",
                         position, blockAndOffset[0], blockAndOffset[1], currentSegmentNumber());
			}
			if (currentSegmentNumber() == blockAndOffset[0]) {
				//already have the correct block
				if (super.tell() == blockAndOffset[1]){
					//already have the correct offset
				} else {
					// Reset and skip.
					if (_segmentReadStream.markSupported()) {
						_segmentReadStream.reset();
					} else {
						setCurrentSegment(_currentSegment);
					}
					_segmentReadStream.skip(blockAndOffset[1]);
				}
				return;
			}
			
			// TODO: once first block is always set in a constructor this conditional can be removed
			if (_currentSegment == null)
				setFirstSegment(getSegment(blockAndOffset[0]));
			else
				setCurrentSegment(getSegment(blockAndOffset[0]));
			super.skip(blockAndOffset[1]);
			long check = _header.segmentLocationToPosition(blockAndOffset[0], blockAndOffset[1]);
			if (Log.isLoggable(Log.FAC_IO, Level.FINE))
				Log.fine(Log.FAC_IO, "current position: block "+blockAndOffset[0]+" _blockOffset "+super.tell()+" ("+check+")");

			if (_currentSegment != null) {
				_atEOF=false;
			}
			// Might be at end of stream, so different value than came in...
			//long check = _header.blockLocationToPosition(blockAndOffset[0], blockAndOffset[1]);
			//Log.info(Log.FAC_IO, "return val check: "+check);
			
			//return _header.blockLocationToPosition(blockAndOffset[0], blockAndOffset[1]);
			//skip(check);
			
			//Log.info(Log.FAC_IO, " _blockOffset "+_blockOffset);
		} else {
			super.seek(position);
		}
	}

	@Override
	public long tell() throws IOException {
		if (hasHeader()) {
			return _header.segmentLocationToPosition(segmentNumber(), (int)super.tell());
		} else {
			return super.tell();
		}
	}
	
	@Override
	public long length() throws IOException {
		if (hasHeader()) {
			return _header.length();
		}
		return super.length();
	}

	public void newVersionAvailable(CCNNetworkObject<?> newVersion, boolean wasSave) {
		if (!headerRequested()) {
			if (Log.isLoggable(Log.FAC_IO, Level.WARNING)) {
				Log.warning(Log.FAC_IO, "CCNFileInputStream: got a notification of a new header version {0} when none requested!", 
						newVersion.getVersionedName());
			}
			return;
		}
		// One of our headers has come back. Cancel the other one. 
		if (MetadataProfile.isHeader(newVersion.getBaseName())) {
			// cancel the old one
			if (null != _oldHeader) {
				if (Log.isLoggable(Log.FAC_IO, Level.FINE)) {
					Log.fine(Log.FAC_IO, "CCNFileInputStream: retrieved new header {0}, canceling request for old one.", 
							newVersion.getVersionedName());
				}
				_oldHeader.cancelInterest();
			}
		} else if (null != _header) {
			if (Log.isLoggable(Log.FAC_IO, Level.FINE)) {
				Log.fine(Log.FAC_IO, "CCNFileInputStream: retrieved old header {0}, canceling request for new one.", 
						newVersion.getVersionedName());
			}
			_header.cancelInterest();			
		}
		
	}
	
	@Override
	public void close() throws IOException{
		Log.info(Log.FAC_IO, "CCNFileInputStream: close {0}.  closing header objects and will make call to shut down pipelining", _baseName);

		//need to close the header object to cancel any outstanding interests
		if (_header != null) {
			_header.close();
		}
		
		if (_oldHeader!=null) {
			_oldHeader.close();
		}
		
		//then make sure any pipelining state is also cleaned up...
		super.close();
	}
}
