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

package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.Log;
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
		
		public HeaderObject(ContentName name, Header data, CCNHandle handle) throws IOException {
			super(Header.class, name, data, handle);
		}
		
		public HeaderObject(ContentName name, Header data, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNHandle handle) throws IOException {
			super(Header.class, name, data, publisher, keyLocator, handle);
		}

		public HeaderObject(ContentName name,
				Header data, PublisherPublicKeyDigest publisher,
				KeyLocator keyLocator, CCNFlowControl flowControl) throws IOException, XMLStreamException {
			super(Header.class, name, data, publisher, keyLocator, flowControl);
		}
	
		public HeaderObject(ContentName name, CCNHandle handle) throws IOException, XMLStreamException {
			super(Header.class, name, (PublisherPublicKeyDigest)null, handle);
		}
		
		public HeaderObject(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle handle) throws IOException, XMLStreamException {
			super(Header.class, name, publisher, handle);
		}
		
		public HeaderObject(ContentObject firstBlock, CCNHandle handle) throws IOException, XMLStreamException {
			super(Header.class, firstBlock, handle);
		}
		
		public long start() throws ContentGoneException, ContentNotReadyException { 
			Header h = header();
			return h.start(); 
		}

		public long count() throws ContentGoneException, ContentNotReadyException { 
			Header h = header();
			return h.count(); 
		}
		
		public int blockSize() throws ContentGoneException, ContentNotReadyException { 
			Header h = header();
			return h.blockSize(); 
		}
		
		public long length() throws ContentGoneException, ContentNotReadyException { 
			Header h = header();
			return h.length(); 
		}
		
		public byte [] rootDigest() throws ContentGoneException, ContentNotReadyException { 
			Header h = header();
			return h.rootDigest(); 
		}
		
		public byte [] contentDigest() throws ContentGoneException, ContentNotReadyException {
			Header h = header();
			return h.contentDigest(); 
		}
		
		public SegmentationType type() throws ContentGoneException, ContentNotReadyException {
			Header h = header();
			return h.type(); 
		}

		public String typeName() throws ContentNotReadyException, ContentGoneException {
			Header h = header();
			return h.typeName(); 
		}
		
		public int[] positionToSegmentLocation(long position) throws ContentNotReadyException, ContentGoneException {
			Header h = header();
			return h.positionToSegmentLocation(position);
		}

		public long segmentLocationToPosition(long block, int offset) throws ContentNotReadyException, ContentGoneException {
			Header h = header();
			return h.segmentLocationToPosition(block, offset);
		}

		public int segmentCount() throws ContentNotReadyException, ContentGoneException {
			Header h = header();
			return h.segmentCount();
		}
		
		public int segmentRemainder() throws ContentNotReadyException, ContentGoneException {
			Header h = header();
			return h.segmentRemainder();
		}

		public Header header() throws ContentNotReadyException, ContentGoneException { 
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
	
	public static final String START_ELEMENT = "Start";
	public static final String HEADER_ELEMENT = "Header";
	
	/**
	 * These are specific to simple block fragmentation.
	 */
	protected static final String COUNT_ELEMENT = "Count";
	protected static final String BLOCKSIZE_ELEMENT = "BlockSize";
	protected static final String LENGTH_ELEMENT = "Length";
	
	/**
	 * These are generic.
	 */
	protected static final String CONTENT_DIGEST_ELEMENT = "ContentDigest";
	protected static final String MERKLE_ROOT_ELEMENT = "RootDigest";
	
	/**
	 * Specific to simple block fragmentation.
	 */
	protected long _start;	// starting block number ( >= 0)
	protected long _count;	// number of blocks in sequence (>= 0)
	protected int _blockSize; // size in bytes(?) of a block (> 0)
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
			byte [] rootDigest, int blockSize
	) throws XMLStreamException {
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
		return _blockSize;
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
	
	/* (non-Javadoc)
	 * @see com.parc.ccn.data.util.XMLEncodable#decode(javax.xml.stream.XMLEventReader)
	 */
	@Override
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(getElementLabel());
		_start = Integer.valueOf(decoder.readUTF8Element(START_ELEMENT));
		_count = Integer.valueOf(decoder.readUTF8Element(COUNT_ELEMENT));
		_blockSize = Integer.valueOf(decoder.readUTF8Element(BLOCKSIZE_ELEMENT));
		_length = Integer.valueOf(decoder.readUTF8Element(LENGTH_ELEMENT));
		_contentDigest = decoder.readBinaryElement(CONTENT_DIGEST_ELEMENT);
		if (null == _contentDigest) {
			throw new XMLStreamException("Cannot parse content digest.");
		}
		
		if (decoder.peekStartElement(MERKLE_ROOT_ELEMENT)) {
			_rootDigest = decoder.readBinaryElement(MERKLE_ROOT_ELEMENT);
			if (null == _rootDigest) {
				throw new XMLStreamException("Cannot parse root digest.");
			}
		}
		decoder.readEndElement();
		
		// Right now, we're just setting this field to default, and it's not encoded
		_type = SegmentationType.SIMPLE_BLOCK;
	}

	/* (non-Javadoc)
	 * @see com.parc.ccn.data.util.XMLEncodable#encode(javax.xml.stream.XMLStreamWriter, boolean)
	 */
	@Override
	public void encode(XMLEncoder encoder)
			throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(getElementLabel());
		encoder.writeElement(START_ELEMENT,	 Long.toString(_start));
		encoder.writeElement(COUNT_ELEMENT,	 Long.toString(_count));
		encoder.writeElement(BLOCKSIZE_ELEMENT,	 Long.toString(_blockSize));
		encoder.writeElement(LENGTH_ELEMENT,	Long.toString(_length));
		encoder.writeElement(CONTENT_DIGEST_ELEMENT, contentDigest());
		if (null != rootDigest())
			encoder.writeElement(MERKLE_ROOT_ELEMENT, rootDigest());
		encoder.writeEndElement();
		
		// DKS -- currently not putting _type on the wire, not sure why it's here...
	}

	@Override
	public String getElementLabel() { return HEADER_ELEMENT; }

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
		result = prime * result + _blockSize;
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
		blockLocation[0] = (int)(Math.floor(1.0*position/blockSize()));
		blockLocation[1] = (int)(1.0*position % blockSize());
		return blockLocation;
	}

	public long segmentLocationToPosition(long block, int offset) {
		if (offset > blockSize()) {
			block += (int)(Math.floor(1.0*offset/blockSize()));
			offset = (int)(1.0*offset % blockSize());
		}
		if (block >= segmentCount()) {
			return length();
		}
		return block*blockSize() + offset;
	}

	public int segmentCount() {
		return (int)(Math.ceil(1.0*length()/blockSize()));
	}
	
	/**
	 * Length of last block.
	 * @return
	 */
	public int segmentRemainder() {
		int remainder = (int)(1.0*length() % blockSize());
		if (remainder == 0)
			return blockSize();
		return remainder;
	}
}
