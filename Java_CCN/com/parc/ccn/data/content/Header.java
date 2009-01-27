
package com.parc.ccn.data.content;

import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.SignatureException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.HeaderData.FragmentationType;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.Signature;
import com.parc.ccn.data.security.ContentAuthenticator.ContentType;
import com.parc.ccn.library.CCNLibrary;

/**
 * Mapping from a sequence to the underlying XML representation.
 * A Header is a content object giving a compact description of a
 * sequence of named blocks.
 * 
 * @author rasmusse
 *
 */
public class Header extends ContentObject  {
	
	protected HeaderData _data;

	public static final String HEADER_ELEMENT = "Header";
	public static final String START_ELEMENT = "Start";
	
	public static final int DEFAULT_BLOCKSIZE = 4096;
	
	public Header(ContentName name,
			 int start, int count, 
			 int blockSize, int length,
			 byte [] contentDigest,
			 byte [] rootDigest,
			 PublisherKeyID publisher,
			 KeyLocator locator,
			 Signature signature
			 ) throws XMLStreamException {
		super(name, new ContentAuthenticator(publisher, ContentType.HEADER, locator), null, 
				(Signature)null);
		_signature = signature;
		_data = new HeaderData(start, count, blockSize, length, contentDigest, rootDigest);
		_content = _data.encode();
	}
	
	public Header(ContentName name,
			 int start, int count, 
			 int blockSize, int length,
			 byte [] contentDigest,
			 byte [] rootDigest,
			 PublisherKeyID publisher,
			 KeyLocator locator,
			 PrivateKey signingKey
			 ) throws XMLStreamException, InvalidKeyException, SignatureException {
		this(name, start, count, blockSize, length, contentDigest, rootDigest, publisher, locator, (Signature)null);
		_signature = sign(name, authenticator(), _content, signingKey);
	}
	
	public Header(ContentName name,
			 int length,
		  	 byte [] contentDigest,
		  	 byte [] rootDigest, int blockSize,
			 PublisherKeyID publisher,
			 KeyLocator locator,
			 PrivateKey signingKey
			 ) throws XMLStreamException, InvalidKeyException, SignatureException {
		this(name, CCNLibrary.baseFragment(), (length + blockSize - 1) / blockSize, blockSize, length,
				 contentDigest, rootDigest, publisher, locator, signingKey);
	}
	
	public Header(ContentName name,
			 PublisherKeyID publisher, 
			 KeyLocator locator,
			 PrivateKey signingKey
			 ) throws XMLStreamException, InvalidKeyException, SignatureException {
		this(name, CCNLibrary.baseFragment(), 0, DEFAULT_BLOCKSIZE, 0, null, null, publisher, locator, (Signature)null);
	}
	
	/**
	 * Decoding constructor.
	 */
	public Header() {}
	
	public static Header contentToHeader(ContentObject co) throws XMLStreamException {
		Header header = new Header();
		header.decode(co.encode());
		header.decodeData();
		return header;
	}
	
	private void decodeData() throws XMLStreamException {
		_data = new HeaderData();
		_data.decode(_content);
	}
	
	public int start() { 
		return _data.start();
	}
	public int count() { 
		return _data.count();
	}
	public int blockSize() { 
		return _data.blockSize();
	}
	public int length() { 
		return _data.length();
	}
	
	public byte [] rootDigest() { 
		return _data.rootDigest();
	}
	
	public byte [] contentDigest() {
		return _data.contentDigest();
	}
	
	public FragmentationType type() {
		return _data.type();
	}

	public void type(FragmentationType type) {
		_data.type(type);
	}
	
	public String typeName() {
		return _data.typeName();
	}
	
	public int[] positionToBlockLocation(long position) {
		return _data.positionToBlockLocation(position);
	}

	public long blockLocationToPosition(int block, int offset) {
		return _data.blockLocationToPosition(block, offset);
	}

	public int blockCount() {
		return _data.blockCount();
	}
	
	public int blockRemainder() {
		return _data.blockRemainder();
	}
}
