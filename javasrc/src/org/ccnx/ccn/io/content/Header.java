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

package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * A Header is a set of metadata describing a particular CCN stream; basically it provides
 * summary file-level information about that set of content. It is usually stored and
 * read by CCNFileOutputStream and CCNFileInputStream and their subclasses, rather than
 * being created directly by clients.
 * 
 * A number of the segmentation-related definitions currently found in Header will
 * eventually move to the SegmentationProfile.
 */
public class Header extends GenericXMLEncodable implements XMLEncodable  {
	
	/**
	 * A CCNNetworkObject wrapper around Header, used for easily saving and retrieving
	 * versioned Headers to CCN. A typical pattern for using network objects to save
	 * objects that happen to be encodable or serializable is to incorporate such a static
	 * member wrapper class subclassing CCNEncodableObject, CCNSerializableObject, or
	 * CCNNetworkObject itself inside the main class definition.
	 */
	public static class HeaderObject extends CCNEncodableObject<Header> {
		
		public HeaderObject(ContentName name, Header data, SaveType saveType, CCNHandle handle) throws IOException {
			super(Header.class, true, name, data, saveType, handle);
		}
		
		public HeaderObject(ContentName name, Header data, SaveType saveType,
							PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNHandle handle) throws IOException {
			super(Header.class, true, name, data, saveType, publisher, keyLocator, handle);
		}

		public HeaderObject(ContentName name,
				Header data, 
				PublisherPublicKeyDigest publisher,
				KeyLocator keyLocator, CCNFlowControl flowControl) 
				throws ContentDecodingException, IOException {
			super(Header.class, true, name, data, publisher, keyLocator, flowControl);
		}
	
		public HeaderObject(ContentName name, CCNHandle handle) 
				throws ContentDecodingException, IOException {
			super(Header.class, true, name, (PublisherPublicKeyDigest)null, handle);
		}
		
		public HeaderObject(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle handle) 
				throws ContentDecodingException, IOException {
			super(Header.class, true, name, publisher, handle);
		}
		
		public HeaderObject(ContentObject firstBlock, CCNHandle handle) 
				throws ContentDecodingException, IOException {
			super(Header.class, true, firstBlock, handle);
		}
		
		public long start() throws ContentGoneException, ContentNotReadyException, ErrorStateException { 
			Header h = header();
			return h.start(); 
		}

		public long count() throws ContentGoneException, ContentNotReadyException, ErrorStateException { 
			Header h = header();
			return h.count(); 
		}
		
		public int blockSize() throws ContentGoneException, ContentNotReadyException, ErrorStateException { 
			Header h = header();
			return h.blockSize(); 
		}
		
		public long length() throws ContentGoneException, ContentNotReadyException, ErrorStateException { 
			Header h = header();
			return h.length(); 
		}
		
		public byte [] rootDigest() throws ContentGoneException, ContentNotReadyException, ErrorStateException { 
			Header h = header();
			return h.rootDigest(); 
		}
		
		public byte [] contentDigest() throws ContentGoneException, ContentNotReadyException, ErrorStateException {
			Header h = header();
			return h.contentDigest(); 
		}
		
		public SegmentationType type() throws ContentGoneException, ContentNotReadyException, ErrorStateException {
			Header h = header();
			return h.type(); 
		}

		public String typeName() throws ContentNotReadyException, ContentGoneException, ErrorStateException {
			Header h = header();
			return h.typeName(); 
		}
		
		public int[] positionToSegmentLocation(long position) throws ContentNotReadyException, ContentGoneException, ErrorStateException {
			Header h = header();
			return h.positionToSegmentLocation(position);
		}

		public long segmentLocationToPosition(long block, int offset) throws ContentNotReadyException, ContentGoneException, ErrorStateException {
			Header h = header();
			return h.segmentLocationToPosition(block, offset);
		}

		public int segmentCount() throws ContentNotReadyException, ContentGoneException, ErrorStateException {
			Header h = header();
			return h.segmentCount();
		}
		
		public int segmentRemainder() throws ContentNotReadyException, ContentGoneException, ErrorStateException {
			Header h = header();
			return h.segmentRemainder();
		}

		public Header header() throws ContentNotReadyException, ContentGoneException, ErrorStateException { 
			if (null == data())
				return null;
			return data(); 
		}
	}
	
	public enum SegmentationType {SIMPLE_BLOCK};
    protected static final HashMap<SegmentationType, String> SegmentationTypeNames = new HashMap<SegmentationType, String>();
    protected static final HashMap<String, SegmentationType> SegmentationNameTypes = new HashMap<String, SegmentationType>();

    static {
    	SegmentationTypeNames.put(SegmentationType.SIMPLE_BLOCK, "SIMPLE_BLOCK");
    	SegmentationNameTypes.put("SIMPLE_BLOCK", SegmentationType.SIMPLE_BLOCK);
    }
		
	/**
	 * Specific to simple block fragmentation.
	 */
	protected long _start;	// starting block number ( >= 0)
	protected long _count;	// number of blocks in sequence (>= 0)
	protected long _blockSize; // size in bytes(?) of a block (> 0)
	protected long _length; // total length in bytes (? same unit as _blockSize) to account for partial last block (>= 0)

	
	/**
	 * Generic.
	 * 
	 */
	protected SegmentationType _type;
	protected byte [] _contentDigest;
	protected byte [] _rootDigest; // root of the Merkle tree
	
	/**
	 * Basic constructor for content sequence headers.
	 * @param start The starting byte offset for this file.
	 * @param count The number of blocks.
	 * @param blockSize The size of blocks (in bytes).
	 * @param length The total length of the stream.
	 * @param contentDigest For convenience, the digest of the unsegmented content.
	 * @param rootDigest The root digest of the bulk signature tree for the content (Merkle Hash Tree).
	 *  		This turns out to be less useful than you'd think as there are typically multiple
	 *  		MHT's per file, and is likely to be removed.
	 */
	public Header(long start, long count, 
				  int blockSize, long length,
				  byte [] contentDigest,
				  byte [] rootDigest) {
		_start = start;
		_count = count;
		_blockSize = blockSize;
		_length = length;
		_contentDigest = contentDigest;
		_rootDigest = rootDigest;
		_type = SegmentationType.SIMPLE_BLOCK;
	}
	
	/**
	 * Basic constructor for content sequences
	 * @param length The total length of the stream.
	 * @param contentDigest For convenience, the digest of the unsegmented content.
	 * @param rootDigest The root digest of the bulk signature tree for the content (Merkle Hash Tree).
	 *  		This turns out to be less useful than you'd think as there are typically multiple
	 *  		MHT's per file, and is likely to be removed.
	 * @param blockSize The size of blocks (in bytes).
	 */
	public Header(long length,
			byte [] contentDigest,
			byte [] rootDigest, int blockSize)  {
		this(SegmentationProfile.baseSegment(), 
				(length + blockSize - 1) / blockSize, blockSize, length,
				contentDigest, rootDigest);
	}
	
	/**
	 * For decoders
	 */
	public Header() {}
	
	public long start() { 
		return _start;
	}
	public long count() { 
		return _count;
	}
	public int blockSize() { 
		return (int)_blockSize;
	}
	public long length() { 
		return _length;
	}
	
	public byte [] rootDigest() { 
		return _rootDigest;
	}
	
	public byte [] contentDigest() {
		return _contentDigest;
	}
	
	public SegmentationType type() {
		return _type;
	}

	public void type(SegmentationType type) {
		this._type = type;
	}
	
	public String typeName() {
		return typeToName(type());
	}
	
	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());
		_start = decoder.readLongElement(CCNProtocolDTags.Start);
		_count = decoder.readLongElement(CCNProtocolDTags.Count);
		_blockSize = decoder.readLongElement(CCNProtocolDTags.BlockSize);
		_length = decoder.readLongElement(CCNProtocolDTags.Length);
		_contentDigest = decoder.readBinaryElement(CCNProtocolDTags.ContentDigest);
		if (null == _contentDigest) {
			throw new ContentDecodingException("Cannot parse content digest.");
		}
		
		if (decoder.peekStartElement(CCNProtocolDTags.RootDigest)) {
			_rootDigest = decoder.readBinaryElement(CCNProtocolDTags.RootDigest);
			if (null == _rootDigest) {
				throw new ContentDecodingException("Cannot parse root digest.");
			}
		}
		decoder.readEndElement();
		
		// Right now, we're just setting this field to default, and it's not encoded
		_type = SegmentationType.SIMPLE_BLOCK;
	}

	@Override
	public void encode(XMLEncoder encoder)
			throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(getElementLabel());
		encoder.writeElement(CCNProtocolDTags.Start, _start);
		encoder.writeElement(CCNProtocolDTags.Count, _count);
		encoder.writeElement(CCNProtocolDTags.BlockSize, _blockSize);
		encoder.writeElement(CCNProtocolDTags.Length, _length);
		encoder.writeElement(CCNProtocolDTags.ContentDigest, contentDigest());
		if (null != rootDigest())
			encoder.writeElement(CCNProtocolDTags.RootDigest, rootDigest());
		encoder.writeEndElement();
		
		// DKS -- currently not putting _type on the wire, not sure why it's here...
	}

	@Override
	public long getElementLabel() { return CCNProtocolDTags.Header; }

	@Override
	public boolean validate() {
		if (_start < 0 || _count < 0 ||  _length < 0) return false;
		if (_blockSize <= 0) return false;
		return true;
	}
	
	public static String typeToName(SegmentationType type) {
		return SegmentationTypeNames.get(type);
	}

	public static SegmentationType nameToType(String name) {
		return SegmentationNameTypes.get(name);
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (int) (_blockSize ^ (_blockSize >>> 32));
		result = prime * result + Arrays.hashCode(_contentDigest);
		result = prime * result + (int) (_count ^ (_count >>> 32));
		result = prime * result + (int) (_length ^ (_length >>> 32));
		result = prime * result + Arrays.hashCode(_rootDigest);
		result = prime * result + (int) (_start ^ (_start >>> 32));
		result = prime * result + ((_type == null) ? 0 : _type.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Header other = (Header) obj;
		if (_blockSize != other._blockSize)
			return false;
		if (!Arrays.equals(_contentDigest, other._contentDigest))
			return false;
		if (_count != other._count)
			return false;
		if (_length != other._length)
			return false;
		if (!Arrays.equals(_rootDigest, other._rootDigest))
			return false;
		if (_start != other._start)
			return false;
		if (_type == null) {
			if (other._type != null)
				return false;
		} else if (!_type.equals(other._type))
			return false;
		return true;
	}

	public int[] positionToSegmentLocation(long position) {
		int [] blockLocation = new int[2];
		Log.info("Header: " + this);
		Log.info("position: " + position + " blockSize " + blockSize() + " position/blockSize " + position/blockSize() + " start: " + start());
		blockLocation[0] = (int)(position / blockSize());
		blockLocation[1] = (int)(position % blockSize());
		return blockLocation;
	}

	public long segmentLocationToPosition(long block, int offset) {
		if (offset > blockSize()) {
			block += offset / blockSize();
			offset = offset % blockSize();
		}
		if (block >= segmentCount()) {
			return length();
		}
		return block * blockSize() + offset;
	}

	public int segmentCount() {
		return (int) (length() + blockSize() - 1) / blockSize();
	}
	
	/**
	 * Length of last block.
	 * @return
	 */
	public int segmentRemainder() {
		int remainder = (int)(length() % blockSize());
		if (remainder == 0)
			return blockSize();
		return remainder;
	}
}
