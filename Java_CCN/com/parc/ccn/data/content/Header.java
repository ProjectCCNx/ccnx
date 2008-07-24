
package com.parc.ccn.data.content;

import java.util.Arrays;
import java.util.HashMap;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

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
public class Header extends GenericXMLEncodable implements XMLEncodable  {
	
    public enum FragmentationType {SIMPLE_BLOCK};
    protected static final HashMap<FragmentationType, String> FragmentationTypeNames = new HashMap<FragmentationType, String>();
    protected static final HashMap<String, FragmentationType> FragmentationNameTypes = new HashMap<String, FragmentationType>();

    static {
    	FragmentationTypeNames.put(FragmentationType.SIMPLE_BLOCK, "SIMPLE_BLOCK");
    	FragmentationNameTypes.put("SIMPLE_BLOCK", FragmentationType.SIMPLE_BLOCK);
    }
        
    protected static final String HEADER_ELEMENT = "Header";
	protected static final String START_ELEMENT = "Start";
	
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
	
	public static final int DEFAULT_BLOCKSIZE = 4096;
	public static final int DEFAULT_START = 1;
	
	/**
	 * Specific to simple block fragmentation.
	 */
	protected int _start;	// starting block number ( >= 0)
	protected int _count;	// number of blocks in sequence (>= 0)
	protected int _blockSize; // size in bytes(?) of a block (> 0)
	protected int _length; // total length in bytes (? same unit as _blockSize) to account for partial last block (>= 0)

	
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
	public Header(int start, int count, 
				  int blockSize, int length,
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

	/**
	 * 
	 */
	public Header(int length,
			  	  byte [] contentDigest,
			  	  byte [] rootDigest) {
		this(DEFAULT_START, (length + DEFAULT_BLOCKSIZE - 1) / DEFAULT_BLOCKSIZE, DEFAULT_BLOCKSIZE, length,
			 contentDigest, rootDigest);
	}
	
	public Header() {
		this(DEFAULT_START, 0, DEFAULT_BLOCKSIZE, 0, null, null);
	}
	
	public int start() { 
		return _start;
	}
	public int count() { 
		return _count;
	}
	public int blockSize() { 
		return _blockSize;
	}
	public int length() { 
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
		decoder.readStartElement(HEADER_ELEMENT);
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
		encoder.writeElement(START_ELEMENT,	 Integer.toString(_start));
		encoder.writeElement(COUNT_ELEMENT,	 Integer.toString(_count));
		encoder.writeElement(BLOCKSIZE_ELEMENT,	 Integer.toString(_blockSize));
		encoder.writeElement(LENGTH_ELEMENT,	Integer.toString(_length));
		encoder.writeElement(CONTENT_DIGEST_ELEMENT, contentDigest());
		if (null != rootDigest())
			encoder.writeElement(MERKLE_ROOT_ELEMENT, rootDigest());
		encoder.writeEndElement();
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
		result = prime * result + _count;
		result = prime * result + _length;
		result = prime * result + _start;
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
		final Header other = (Header) obj;
		if (_length != other._length)
			return false;
		if (_count != other._count)
			return false;
		if (_start != other._start)
			return false;
		if (_blockSize != other._blockSize)
			return false;
		if (_type == null) {
			if (other.type() != null)
				return false;
		} else if (!_type.equals(other.type()))
			return false;
		if (!Arrays.equals(rootDigest(), other.rootDigest()))
			return false;
		if (!Arrays.equals(contentDigest(), other.contentDigest()))
			return false;
		return true;
	}


}
