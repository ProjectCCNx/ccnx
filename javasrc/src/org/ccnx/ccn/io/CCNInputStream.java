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
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * Perform sequential reads on any segmented CCN content, as if it
 * were a standard InputStream.
 * This input stream will read from a sequence of blocks, authenticating
 * each as it goes, and caching what verification information it can to speed
 * up verification of future blocks. All it assumes
 * is that the last component of the name is a segment number as described in
 * SegmentationProfile.
 * 
 * Read buffer size is independent of segment size; the stream will pull additional
 * content fragments dynamically when possible to fill out the requested number
 * of bytes.
 * @author smetters
 */
public class CCNInputStream extends CCNAbstractInputStream {
	
	/**
	 * Set up an input stream to read segmented CCN content under a given name. Content is assumed
	 * to be unencrypted, or keys will be retrieved automatically via another
	 * process. 
	 * Will use the default handle given by CCNHandle#getHandle().
	 * This constructor will attempt to retrieve the first block of content.
	 * 
	 * @param baseName Name to read from. If contains a segment number, will start to read from that
	 *    segment.
	 * @throws IOException Not currently thrown, will be thrown when constructors retrieve first block.
	 */
	public CCNInputStream(ContentName baseName) throws IOException {
		this(baseName, null);
	}
	
	/**
	 * Set up an input stream to read segmented CCN content under a given name. Content is assumed
	 * to be unencrypted, or keys will be retrieved automatically via another
	 * process.
	 * This constructor will attempt to retrieve the first block of content.
	 * 
	 * @param baseName Name to read from. If contains a segment number, will start to read from that
	 *    segment.
	 * @param handle The CCN handle to use for data retrieval. If null, the default handle
	 * 		given by CCNHandle#getHandle() will be used.
	 * @throws IOException Not currently thrown, will be thrown when constructors retrieve first block.
	 */
	public CCNInputStream(ContentName baseName, CCNHandle handle) throws IOException {
		this(baseName, null, null, handle);
	}
	
	/**
	 * Set up an input stream to read segmented CCN content under a given name. Content is assumed
	 * to be unencrypted, or keys will be retrieved automatically via another
	 * process.
 	 * This constructor will attempt to retrieve the first block of content.
	 * 
	 * @param baseName Name to read from. If contains a segment number, will start to read from that
	 *    segment.
	 * @param publisher The key we require to have signed this content. If null, will accept any publisher
	 * 				(subject to higher-level verification).
	 * @param handle The CCN handle to use for data retrieval. If null, the default handle
	 * 		given by CCNHandle#getHandle() will be used.
	 * @throws IOException Not currently thrown, will be thrown when constructors retrieve first block.
	 */
	public CCNInputStream(ContentName baseName, PublisherPublicKeyDigest publisher, CCNHandle handle) 
    throws IOException {
		this(baseName, null, publisher, handle);
	}
    
	/**
	 * Set up an input stream to read segmented CCN content under a given name. Content is assumed
	 * to be unencrypted, or keys will be retrieved automatically via another
	 * process.
	 * This constructor will attempt to retrieve the first block of content.
	 * 
	 * @param baseName Name to read from. If contains a segment number, will start to read from that
	 *    segment.
	 * @param startingSegmentNumber Alternative specification of starting segment number. If
	 * 		null, will be SegmentationProfile#baseSegment().
	 * @param handle The CCN handle to use for data retrieval. If null, the default handle
	 * 		given by CCNHandle#getHandle() will be used.
	 * @throws IOException Not currently thrown, will be thrown when constructors retrieve first block.
	 */
	public CCNInputStream(ContentName baseName, Long startingSegmentNumber, CCNHandle handle) throws IOException {
		this(baseName, startingSegmentNumber, null, handle);
	}
	
	/**
	 * Set up an input stream to read segmented CCN content under a given name. Content is assumed
	 * to be unencrypted, or keys will be retrieved automatically via another
	 * process.
	 * This constructor will attempt to retrieve the first block of content.
	 * 
	 * @param baseName Name to read from. If contains a segment number, will start to read from that
	 *    segment.
	 * @param startingSegmentNumber Alternative specification of starting segment number. If
	 * 		null, will be SegmentationProfile#baseSegment().
	 * @param publisher The key we require to have signed this content. If null, will accept any publisher
	 * 				(subject to higher-level verification).
	 * @param handle The CCN handle to use for data retrieval. If null, the default handle
	 * 		given by CCNHandle#getHandle() will be used.
	 * @throws IOException Not currently thrown, will be thrown when constructors retrieve first block.
	 */
	public CCNInputStream(ContentName baseName, Long startingSegmentNumber, PublisherPublicKeyDigest publisher,
                          CCNHandle handle) throws IOException {
        
		super(baseName, startingSegmentNumber, publisher, null, null, handle);
	}
	
	/**
	 * Set up an input stream to read segmented CCN content under a given name. 
	 * This constructor will attempt to retrieve the first block of content.
	 * 
	 * @param baseName Name to read from. If contains a segment number, will start to read from that
	 *    segment.
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
	public CCNInputStream(ContentName baseName, Long startingSegmentNumber, PublisherPublicKeyDigest publisher, 
                          ContentKeys keys, CCNHandle handle) throws IOException {
        
		super(baseName, startingSegmentNumber, publisher, keys, null, handle);
	}
    
	/**
	 * Set up an input stream to read segmented CCN content starting with a given
	 * ContentObject that has already been retrieved.  Content is assumed
	 * to be unencrypted, or keys will be retrieved automatically via another
	 * process.
	 * 
	 * @param startingSegment The first segment to read from. If this is not the
	 * 		first segment of the stream, reading will begin from this point.
	 * 		We assume that the signature on this segment was verified by our caller.
	 * @param flags any stream flags that must be set to handle even this first block (otherwise
	 * 	they can be set with setFlags prior to read). Can be null.
	 * @param handle The CCN handle to use for data retrieval. If null, the default handle
	 * 		given by CCNHandle#getHandle() will be used.
	 * @throws IOException If startingSegment's name does not contain a valid segment number
	 */
	public CCNInputStream(ContentObject startingSegment, EnumSet<FlagTypes> flags, CCNHandle handle) throws IOException {
		super(startingSegment, null, flags, handle);
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
	 * @throws IOException If startingSegment's name does not contain a valid segment number
	 */
	public CCNInputStream(ContentObject startingSegment, ContentKeys keys, EnumSet<FlagTypes> flags, CCNHandle handle) throws IOException {
		super(startingSegment, keys, flags, handle);
	}
	
	/**
	 * Implement sequential reads of data across multiple segments. As we run out of bytes
	 * on a given segment, the next segment is retrieved and reading continues.
	 */
	@Override
	protected int readInternal(byte [] buf, int offset, int len) throws IOException {
		
		if (_atEOF) {
			if (Log.isLoggable(Log.FAC_IO, Level.FINE))
				Log.fine(Log.FAC_IO, "At EOF: {0}", ((null == _currentSegment) ? "null" : _currentSegment.name()));
			return -1;
		}
		
		if (Log.isLoggable(Log.FAC_IO, Level.FINEST))
			Log.finest(Log.FAC_IO, getBaseName() + ": reading " + len + " bytes into buffer of length " + 
                       ((null != buf) ? buf.length : "null") + " at offset " + offset);
		// is this the first block?
		if (null == _currentSegment) {
			// This will throw an exception if no block found, which is what we want.
			setFirstSegment(getFirstSegment());
		} 
		if (Log.isLoggable(Log.FAC_IO, Level.FINEST))
			Log.finest(Log.FAC_IO, "reading from block: {0}, length: {1}", _currentSegment.name(),  
                       _currentSegment.contentLength());
		
		// Now we have a block in place. Read from it. If we run out of block before
		// we've read len bytes, pull next block.
		int lenToRead = len;
		int lenRead = 0;
		long readCount = 0;
		while (lenToRead > 0) {
			if (null == _segmentReadStream) {
				Log.severe(Log.FAC_IO, "Unexpected null block read stream!");
			}
			if (null != buf) {  // use for skip
				if (Log.isLoggable(Log.FAC_IO, Level.FINEST))
					Log.finest(Log.FAC_IO, "before block read: content length "+_currentSegment.contentLength()+" position "+ tell() +" available: " + _segmentReadStream.available() + " dst length "+buf.length+" dst index "+offset+" len to read "+lenToRead);
				// Read as many bytes as we can
				readCount = _segmentReadStream.read(buf, offset, lenToRead);
			} else {
				readCount = _segmentReadStream.skip(lenToRead);
			}
            
			if (readCount <= 0) {
				if (Log.isLoggable(Log.FAC_IO, Level.FINE))
					Log.fine(Log.FAC_IO, "Tried to read at end of block, go get next block.");
				if (!hasNextSegment()) {
					if (Log.isLoggable(Log.FAC_IO, Level.FINE))
						Log.fine(Log.FAC_IO, "No next block expected, setting _atEOF, returning " + ((lenRead > 0) ? lenRead : -1));
					_atEOF = true;
					if (lenRead > 0) {
						return lenRead;
					}
					return -1; // no bytes read, at eof					
				}
				ContentObject nextSegment = getNextSegment();
				if (null == nextSegment) {
					if (Log.isLoggable(Log.FAC_IO, Level.FINE))
						Log.fine(Log.FAC_IO, "Next block is null, setting _atEOF, returning " + ((lenRead > 0) ? lenRead : -1));
					_atEOF = true;
					if (lenRead > 0) {
						return lenRead;
					}
					return -1; // no bytes read, at eof
				}
				setCurrentSegment(nextSegment);
                
				if (Log.isLoggable(Log.FAC_IO, Level.FINE))
                    Log.fine(Log.FAC_IO, "now reading from block: " + _currentSegment.name() + " length: " + 
                             _currentSegment.contentLength());
			} else {
				offset += readCount;
				lenToRead -= readCount;
				lenRead += readCount;
				if (Log.isLoggable(Log.FAC_IO, Level.FINEST))
					Log.finest(Log.FAC_IO, "     read " + readCount + " bytes for " + lenRead + " total, " + lenToRead + " remaining.");
			}
		}
		return lenRead;
	}
}

