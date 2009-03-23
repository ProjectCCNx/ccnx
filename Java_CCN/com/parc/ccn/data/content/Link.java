package com.parc.ccn.data.content;

import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.SignatureException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.Signature;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.data.util.XMLDecoder;

/**
 * Links are signed by the publisher of the link. However,
 * the content of the link is an XML document that contains
 * a complete name, including an indication of who the linker
 * trusts to write the linked document (or to extend the
 * linked-to hierarchy). The type of key referred to in the
 * linked-to name is any of the usual types (key, cert, or
 * name), but it can play one of two roles -- SIGNER, or
 * the direct signer of the content, or CERTIFIER, the
 * person who must have certified whoever's key signed
 * the linked-to content. 
 * Mapping from a link to the underlying XML representation.
 * Basically a Link is a content object containing
 * a name and optionally some content authentication
 * information to specify whose value for the name
 * to take.
 * @author smetters
 *
 */
public class Link extends ContentObject {
	protected LinkReference _data;
	
	protected static final String LINK_ELEMENT = "Link";
	public Link(ContentName name,
			 ContentName targetName,
			 LinkAuthenticator targetAuthenticator,
			 PublisherKeyID publisher,
			 KeyLocator locator,
			 Signature signature
			 ) throws XMLStreamException {
		super(name, new SignedInfo(publisher, ContentType.LINK, locator), null, 
				(Signature)null);
		_signature = signature;
		_data = new LinkReference(targetName, targetAuthenticator);
		_content = _data.encode();
	}
	
	public Link(ContentName name,
			 ContentName targetName,
			 LinkAuthenticator targetAuthenticator,
			 PublisherKeyID publisher, 
			 KeyLocator locator,
			 PrivateKey signingKey
			 ) throws XMLStreamException, InvalidKeyException, SignatureException {
		this(name, targetName, targetAuthenticator, publisher, locator, (Signature)null);
		_signature = sign(name, signedInfo(), _content, signingKey);
	}
	
	public Link(ContentName name,
			 ContentName targetName,
			 PublisherKeyID publisher, 
			 KeyLocator locator,
			 PrivateKey signingKey
			 ) throws XMLStreamException, InvalidKeyException, SignatureException {
		this(name, targetName, null, publisher, locator, (Signature)null);
	}
	
	/**
	 * Decoding constructor.
	 */
	public Link() {}
	
	public static Link contentToLinkReference(ContentObject co) throws XMLStreamException {
		Link reference = new Link();
		reference.decode(co.encode());
		reference.decodeData();
		return reference;
	}
	
	private void decodeData() throws XMLStreamException {
		_data = new LinkReference();
		_data.decode(_content);
	}
	
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		super.decode(decoder);
		decodeData();
	}
	public ContentName targetName() { return _data.targetName(); }
	public LinkAuthenticator targetAuthenticator() { return _data._targetAuthenticator; }	
}
