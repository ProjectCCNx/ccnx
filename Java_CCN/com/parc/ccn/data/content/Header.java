
package com.parc.ccn.data.content;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import java.io.InputStream;

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
	
	protected static final String HEADER_ELEMENT = "Header";
	protected static final String START_ELEMENT = "Start";
	protected static final String COUNT_ELEMENT = "Count";
	protected static final String BLOCKSIZE_ELEMENT = "BlockSize";
	protected static final String LENGTH_ELEMENT = "Length";
	
	public static final int DEFAULT_BLOCKSIZE = 4096;
	public static final int DEFAULT_START = 1;
	
	protected int _start;	// starting block number ( >= 0)
	protected int _count;	// number of blocks in sequence (>= 0)
	protected int _blockSize; // size in bytes(?) of a block (> 0)
	protected int _length; // total length in bytes (? same unit as _blockSize) to account for partial last block (>= 0)

	/**
	 * Basic constructor for content sequences
	 * @param start
	 * @param count
	 * @param blockSize
	 * @param length
	 */
	public Header(int start, int count, int blockSize, int length) {
		_start = start;
		_count = count;
		_blockSize = blockSize;
		_length = length;
	}

	/**
	 * 
	 */
	public Header(int length) {
		this(DEFAULT_START, (length + DEFAULT_BLOCKSIZE - 1) / DEFAULT_BLOCKSIZE, DEFAULT_BLOCKSIZE, length);
	}
	
	public Header() {
		this(DEFAULT_START, 0, DEFAULT_BLOCKSIZE, 0);
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
		writer.writeEndElement();
	}

	@Override
	public boolean validate() {
		if (_start < 0 || _count < 0 ||  _length < 0) return false;
		if (_blockSize <= 0) return false;
		return true;
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
		return true;
	}


}
