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
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * Perform sequential reads on any block-oriented CCN content, namely that
 * where the name component after the specified name prefix is an optionally
 * segment-encoded integer, and the content blocks are indicated by 
 * monotonically increasing (but not necessarily sequential)
 * optionally segment-encoded integers. For example, a file could be 
 * divided into sequential blocks, while an audio stream might have 
 * blocks named by time offsets into the stream. 
 * 
 * This input stream will read from a sequence of blocks, authenticating
 * each as it goes, and caching what information it can. All it assumes
 * is that the last component of the name is an increasing integer
 * value, where we start with the name we are given, and get right siblings
 * (next blocks) moving forward.
 * 
 * This input stream works with data with and without a header block; it
 * opportunistically queries for a header block and uses its information if
 * one is available. That means an extra interest for content that does not have
 * a header block.
 * 
 * Read size is independent of fragment size; the stream will pull additional
 * content fragments dynamically when possible to fill out the requested number
 * of bytes.
 * 
 * TODO remove header handling from here, add use of lastSegment marker in
 *    blocks leading up to the end. Headers, in whatever form they evolve
 *    into, will be used only by higher-level streams.
 * @author smetters
 *
 */
public class CCNInputStream extends CCNAbstractInputStream {
	
	public CCNInputStream(ContentName name) throws XMLStreamException, IOException {
		this(name, null);
	}
	
	public CCNInputStream(ContentName name, CCNHandle handle) throws XMLStreamException, IOException {
		this(name, null, null, handle);
	}
	
	public CCNInputStream(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle handle) 
			throws XMLStreamException, IOException {
		this(name, null, publisher, handle);
	}

	public CCNInputStream(ContentName name, long segmentNumber) throws XMLStreamException, IOException {
		this(name, segmentNumber, null, null);
	}
	
	public CCNInputStream(ContentName name, Long startingSegmentNumber, PublisherPublicKeyDigest publisher,
			CCNHandle handle) throws XMLStreamException, IOException {

		super(name, startingSegmentNumber, publisher, null, handle);
	}
	
	public CCNInputStream(ContentName name, Long startingSegmentNumber, PublisherPublicKeyDigest publisher, 
			ContentKeys keys, CCNHandle handle) throws XMLStreamException,
			IOException {

		super(name, startingSegmentNumber, publisher, keys, handle);
	}

	public CCNInputStream(ContentObject firstSegment, CCNHandle handle) throws XMLStreamException, IOException {
		super(firstSegment, null, handle);
	}
	
	public CCNInputStream(ContentObject firstSegment, ContentKeys keys, CCNHandle handle) throws XMLStreamException, IOException {
		super(firstSegment, keys, handle);
	}
	
	protected int readInternal(byte [] buf, int offset, int len) throws IOException {
		
		if (_atEOF) {
			return -1;
		}
		
		Log.finest(baseName() + ": reading " + len + " bytes into buffer of length " + 
				((null != buf) ? buf.length : "null") + " at offset " + offset);
		// is this the first block?
		if (null == _currentSegment) {
			// This will throw an exception if no block found, which is what we want.
			setFirstSegment(getFirstSegment());
		} 
		Log.finest("reading from block: {0}, length: {1}", _currentSegment.name(),  
				_currentSegment.contentLength());
		
		// Now we have a block in place. Read from it. If we run out of block before
		// we've read len bytes, pull next block.
		int lenToRead = len;
		int lenRead = 0;
		long readCount = 0;
		while (lenToRead > 0) {
			if (null == _segmentReadStream) {
				Log.severe("Unexpected null block read stream!");
			}
			if (null != buf) {  // use for skip
				Log.finest("before block read: content length "+_currentSegment.contentLength()+" position "+ tell() +" available: " + _segmentReadStream.available() + " dst length "+buf.length+" dst index "+offset+" len to read "+lenToRead);
				// Read as many bytes as we can
				readCount = _segmentReadStream.read(buf, offset, lenToRead);
			} else {
				readCount = _segmentReadStream.skip(lenToRead);
			}

			if (readCount <= 0) {
				Log.info("Tried to read at end of block, go get next block.");
				if (!hasNextSegment()) {
					Log.info("No next block expected, setting _atEOF, returning " + ((lenRead > 0) ? lenRead : -1));
					_atEOF = true;
					if (lenRead > 0) {
						return lenRead;
					}
					return -1; // no bytes read, at eof					
				}
				ContentObject nextSegment = getNextSegment();
				if (null == nextSegment) {
					Log.info("Next block is null, setting _atEOF, returning " + ((lenRead > 0) ? lenRead : -1));
					_atEOF = true;
					if (lenRead > 0) {
						return lenRead;
					}
					return -1; // no bytes read, at eof
				}
				setCurrentSegment(nextSegment);

				Log.info("now reading from block: " + _currentSegment.name() + " length: " + 
						_currentSegment.contentLength());
			} else {
				offset += readCount;
				lenToRead -= readCount;
				lenRead += readCount;
				Log.finest("     read " + readCount + " bytes for " + lenRead + " total, " + lenToRead + " remaining.");
			}
		}
		return lenRead;
	}
}

