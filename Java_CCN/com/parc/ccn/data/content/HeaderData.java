
package com.parc.ccn.data.content;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.util.CCNEncodableObject;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.SegmentationProfile;

/**
 * Mapping from a sequence to the underlying XML representation.
 * A Header is a content object giving a compact description of a
 * sequence of named blocks.
 * @author briggs
 * TODO: these need to be subtypes of ContentObjects
 * and give it a type.   see schema, make it choice of ...
 * and, root hash of hash tree, hash of reconstructed content
 *
 */
public class HeaderData extends GenericXMLEncodable implements XMLEncodable  {
	
	/**
	 * This should eventually be called Header, and the Header class deleted.
	 */
	public static class HeaderObject extends CCNEncodableObject<HeaderData> {
		
		/**
		 * Write constructor. Doesn't save until you call save, in case you want to tweak things first.
		 * @param name
		 * @param data
		 * @param library
		 * @throws IOException
		 */
		public HeaderObject(ContentName name, HeaderData data, CCNLibrary library) throws IOException {
			super(HeaderData.class, name, data, library);
		}
		
		public HeaderObject(ContentName name, HeaderData data, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNLibrary library) throws IOException {
			super(HeaderData.class, name, data, publisher, keyLocator, library);
		}

		public HeaderObject(ContentName name,
				HeaderData data, PublisherPublicKeyDigest publisher,
				KeyLocator keyLocator, CCNFlowControl flowControl) throws IOException, XMLStreamException {
			super(HeaderData.class, name, data, publisher, keyLocator, flowControl);
		}
	
		/**
		 * Read constructor -- opens existing object.
		 * @param name
		 * @param library
		 * @throws XMLStreamException
		 * @throws IOException
		 * @throws ClassNotFoundException 
		 */
		public HeaderObject(ContentName name, PublisherPublicKeyDigest publisher, CCNLibrary library) throws IOException, XMLStreamException {
			super(HeaderData.class, name, publisher, library);
		}
		
		public HeaderObject(ContentName name, CCNLibrary library) throws IOException, XMLStreamException {
			super(HeaderData.class, name, (PublisherPublicKeyDigest)null, library);
		}
		
		public HeaderObject(ContentObject firstBlock, CCNLibrary library) throws IOException, XMLStreamException {
			super(HeaderData.class, firstBlock, library);
		}
		
		public long start() { 
			HeaderData h = header();
			if (null == h)
				throw new IllegalStateException("HeaderObject does not have valid data! Gone? " + isGone() + " Ready? " + available());
			return h.start(); 
		}

		public long count() { 
			HeaderData h = header();
			if (null == h)
				throw new IllegalStateException("HeaderObject does not have valid data! Gone? " + isGone() + " Ready? " + available());
			return h.count(); 
		}
		
		public int blockSize() { 
			HeaderData h = header();
			if (null == h)
				throw new IllegalStateException("HeaderObject does not have valid data! Gone? " + isGone() + " Ready? " + available());
			return h.blockSize(); 
		}
		
		public long length() { 
			HeaderData h = header();
			if (null == h)
				throw new IllegalStateException("HeaderObject does not have valid data! Gone? " + isGone() + " Ready? " + available());
			return h.length(); 
		}
		
		public byte [] rootDigest() { 
			HeaderData h = header();
			if (null == h)
				throw new IllegalStateException("HeaderObject does not have valid data! Gone? " + isGone() + " Ready? " + available());
			return h.rootDigest(); 
		}
		
		public byte [] contentDigest() {
			HeaderData h = header();
			if (null == h)
				throw new IllegalStateException("HeaderObject does not have valid data! Gone? " + isGone() + " Ready? " + available());
			return h.contentDigest(); 
		}
		
		public FragmentationType type() {
			HeaderData h = header();
			if (null == h)
				throw new IllegalStateException("HeaderObject does not have valid data! Gone? " + isGone() + " Ready? " + available());
			return h.type(); 
		}

		public String typeName() {
			HeaderData h = header();
			if (null == h)
				throw new IllegalStateException("HeaderObject does not have valid data! Gone? " + isGone() + " Ready? " + available());
			return h.typeName(); 
		}
		
		public int[] positionToBlockLocation(long position) {
			HeaderData h = header();
			if (null == h)
				throw new IllegalStateException("HeaderObject does not have valid data! Gone? " + isGone() + " Ready? " + available());
			return h.positionToBlockLocation(position);
		}

		public long blockLocationToPosition(long block, int offset) {
			HeaderData h = header();
			if (null == h)
				throw new IllegalStateException("HeaderObject does not have valid data! Gone? " + isGone() + " Ready? " + available());
			return h.blockLocationToPosition(block, offset);
		}

		public int blockCount() {
			HeaderData h = header();
			if (null == h)
				throw new IllegalStateException("HeaderObject does not have valid data! Gone? " + isGone() + " Ready? " + available());
			return h.blockCount();
		}
		
		public int blockRemainder() {
			HeaderData h = header();
			if (null == h)
				throw new IllegalStateException("HeaderObject does not have valid data! Gone? " + isGone() + " Ready? " + available());
			return h.blockRemainder();
		}

		public HeaderData header() { 
			if (null == data())
				return null;
			return data(); 
		}
	}
	
	public enum FragmentationType {SIMPLE_BLOCK};
    protected static final HashMap<FragmentationType, String> FragmentationTypeNames = new HashMap<FragmentationType, String>();
    protected static final HashMap<String, FragmentationType> FragmentationNameTypes = new HashMap<String, FragmentationType>();

    static {
    	FragmentationTypeNames.put(FragmentationType.SIMPLE_BLOCK, "SIMPLE_BLOCK");
    	FragmentationNameTypes.put("SIMPLE_BLOCK", FragmentationType.SIMPLE_BLOCK);
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
	protected FragmentationType _type;
	protected byte [] _contentDigest;
	protected byte [] _rootDigest; // root of the Merkle tree
	
	/**
	 * Basic constructor for content sequences
	 * @param start
	 * @param count
	 * @param blockSize
	 * @param length
	 */
	public HeaderData(long start, long count, 
				  int blockSize, long length,
				  byte [] contentDigest,
				  byte [] rootDigest) {
		_start = start;
		_count = count;
		_blockSize = blockSize;
		_length = length;
		_contentDigest = contentDigest;
		_rootDigest = rootDigest;
		_type = FragmentationType.SIMPLE_BLOCK;
	}
	
	public HeaderData(long length,
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
	public HeaderData() {}
	
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
	
	public FragmentationType type() {
		return _type;
	}

	public void type(FragmentationType type) {
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
		decoder.readStartElement(HeaderData.HEADER_ELEMENT);
		_start = Integer.valueOf(decoder.readUTF8Element(HeaderData.START_ELEMENT));
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
		_type = FragmentationType.SIMPLE_BLOCK;
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
		encoder.writeStartElement(HEADER_ELEMENT);
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
	public boolean validate() {
		if (_start < 0 || _count < 0 ||  _length < 0) return false;
		if (_blockSize <= 0) return false;
		return true;
	}
	
	public static String typeToName(FragmentationType type) {
		return FragmentationTypeNames.get(type);
	}

	public static FragmentationType nameToType(String name) {
		return FragmentationNameTypes.get(name);
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
		HeaderData other = (HeaderData) obj;
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

	public int[] positionToBlockLocation(long position) {
		int [] blockLocation = new int[2];
		Library.logger().info("Header: " + this);
		Library.logger().info("position: " + position + " blockSize " + blockSize() + " position/blockSize " + position/blockSize() + " start: " + start());
		blockLocation[0] = (int)(Math.floor(1.0*position/blockSize()));
		blockLocation[1] = (int)(1.0*position % blockSize());
		return blockLocation;
	}

	public long blockLocationToPosition(long block, int offset) {
		if (offset > blockSize()) {
			block += (int)(Math.floor(1.0*offset/blockSize()));
			offset = (int)(1.0*offset % blockSize());
		}
		if (block >= blockCount()) {
			return length();
		}
		return block*blockSize() + offset;
	}

	public int blockCount() {
		return (int)(Math.ceil(1.0*length()/blockSize()));
	}
	
	/**
	 * Length of last block.
	 * @return
	 */
	public int blockRemainder() {
		int remainder = (int)(1.0*length() % blockSize());
		if (remainder == 0)
			return blockSize();
		return remainder;
	}
}
