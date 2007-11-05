
package com.parc.ccn.data.content;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.util.XMLHelper;

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
public class Header extends ContentObject {
	
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
	protected static final String CONTENT_HASH_ELEMENT = "ContentDigest";
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
	
	/**
	 * @param encoded
	 * @throws XMLStreamException
	 */
	public Header(byte[] encoded) throws XMLStreamException {
		super(encoded);
	}
	
	public Header(InputStream iStream) throws XMLStreamException {
		decode(iStream);
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
	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, HEADER_ELEMENT);
		_start = Integer.valueOf(XMLHelper.readElementText(reader, START_ELEMENT));
		_count = Integer.valueOf(XMLHelper.readElementText(reader, COUNT_ELEMENT));
		_blockSize = Integer.valueOf(XMLHelper.readElementText(reader, BLOCKSIZE_ELEMENT));
		_length = Integer.valueOf(XMLHelper.readElementText(reader, LENGTH_ELEMENT));
		String strDig = XMLHelper.readElementText(reader, CONTENT_HASH_ELEMENT);
		try {
			_contentDigest = XMLHelper.decodeElement(strDig);
		} catch (IOException e) {
			throw new XMLStreamException("Cannot parse content digest: " + strDig, e);
		}
		if (null == _contentDigest) {
			throw new XMLStreamException("Cannot parse content digest: " + strDig);
		}
		String strRoot = XMLHelper.readElementText(reader, MERKLE_ROOT_ELEMENT);
		try {
			_rootDigest = XMLHelper.decodeElement(strRoot);
		} catch (IOException e) {
			throw new XMLStreamException("Cannot parse root digest: " + strRoot, e);
		}
		if (null == _rootDigest) {
			throw new XMLStreamException("Cannot parse root digest: " + strRoot);
		}
		XMLHelper.readEndElement(reader);
	}

	/* (non-Javadoc)
	 * @see com.parc.ccn.data.util.XMLEncodable#encode(javax.xml.stream.XMLStreamWriter, boolean)
	 */
	@Override
	public void encode(XMLStreamWriter writer, boolean isFirstElement)
			throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		XMLHelper.writeStartElement(writer, HEADER_ELEMENT, isFirstElement);
		XMLHelper.writeElement(writer, START_ELEMENT,	 Integer.toString(_start));
		XMLHelper.writeElement(writer, COUNT_ELEMENT,	 Integer.toString(_count));
		XMLHelper.writeElement(writer, BLOCKSIZE_ELEMENT,	 Integer.toString(_blockSize));
		XMLHelper.writeElement(writer, LENGTH_ELEMENT,	Integer.toString(_length));
		writer.writeStartElement(CONTENT_HASH_ELEMENT);
		writer.writeCharacters(XMLHelper.encodeElement(contentDigest()));
		writer.writeEndElement();   
		writer.writeStartElement(MERKLE_ROOT_ELEMENT);
		writer.writeCharacters(XMLHelper.encodeElement(rootDigest()));
		writer.writeEndElement();   
		writer.writeEndElement();
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
