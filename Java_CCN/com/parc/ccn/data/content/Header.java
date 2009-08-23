
package com.parc.ccn.data.content;

import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.SignatureException;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.Signature;
import org.ccnx.ccn.protocol.SignedInfo;

import com.parc.ccn.data.content.HeaderData.FragmentationType;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.library.profiles.SegmentationProfile;

/**
 * Mapping from a sequence to the underlying XML representation.
 * A Header is a content object giving a compact description of a
 * sequence of named blocks.
 * 
 * @author rasmusse
 *
 * Replaced by HeaderObject
 */
@Deprecated
public class Header extends ContentObject  {
	
	protected HeaderData _data;

	public Header(ContentName name,
			 long start, long count, 
			 int blockSize, long length,
			 byte [] contentDigest,
			 byte [] rootDigest,
			 PublisherPublicKeyDigest publisher,
			 KeyLocator locator,
			 Signature signature
			 ) throws XMLStreamException {
		super(name, new SignedInfo(publisher, locator));
		_signature = signature;
		_data = new HeaderData(start, count, blockSize, length, contentDigest, rootDigest);
		_content = _data.encode();
	}
	
	public Header(ContentName name,
			long start, long count, 
			 int blockSize, long length,
			 byte [] contentDigest,
			 byte [] rootDigest,
			 PublisherPublicKeyDigest publisher,
			 KeyLocator locator,
			 PrivateKey signingKey
			 ) throws XMLStreamException, InvalidKeyException, SignatureException {
		this(name, start, count, blockSize, length, contentDigest, rootDigest, publisher, locator, (Signature)null);
		_signature = sign(name, signedInfo(), _content, 0, _content.length, signingKey);
	}
	
	public Header(ContentName name,
			long length,
		  	 byte [] contentDigest,
		  	 byte [] rootDigest, int blockSize,
			 PublisherPublicKeyDigest publisher,
			 KeyLocator locator,
			 PrivateKey signingKey
			 ) throws XMLStreamException, InvalidKeyException, SignatureException {
		this(name, SegmentationProfile.baseSegment(), 
				(length + blockSize - 1) / blockSize, blockSize, length,
				 contentDigest, rootDigest, publisher, locator, signingKey);
	}
	
	public Header(ContentName name,
			 PublisherPublicKeyDigest publisher, 
			 KeyLocator locator,
			 PrivateKey signingKey
			 ) throws XMLStreamException, InvalidKeyException, SignatureException {
		this(name, SegmentationProfile.baseSegment(), 0, 
					SegmentationProfile.DEFAULT_BLOCKSIZE, 0, null, null, 
					publisher, locator, signingKey);
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
	
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		super.decode(decoder);
		decodeData();
	}
	
	private void decodeData() throws XMLStreamException {
		_data = new HeaderData();
		_data.decode(_content);
	}
	
	public long start() { 
		return _data.start();
	}
	public long count() { 
		return _data.count();
	}
	public int blockSize() { 
		return _data.blockSize();
	}
	public long length() { 
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
		return _data.positionToSegmentLocation(position);
	}

	public long blockLocationToPosition(long block, int offset) {
		return _data.segmentLocationToPosition(block, offset);
	}

	public int blockCount() {
		return _data.segmentCount();
	}
	
	public int blockRemainder() {
		return _data.segmentRemainder();
	}
}
