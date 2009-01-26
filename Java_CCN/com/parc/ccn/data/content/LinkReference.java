package com.parc.ccn.data.content;

import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.SignatureException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.Signature;
import com.parc.ccn.data.security.ContentAuthenticator.ContentType;

/**
 * Mapping from a link to the underlying XML representation.
 * Basically a Link is a content object containing
 * a name and optionally some content authentication
 * information to specify whose value for the name
 * to take.
 * @author smetters
 *
 */
public class LinkReference extends ContentObject {
	protected LinkReferenceData _data;
	
	protected static final String LINK_ELEMENT = "Link";
	public LinkReference(ContentName name,
			 ContentName targetName,
			 LinkAuthenticator targetAuthenticator,
			 PublisherKeyID publisher,
			 KeyLocator locator,
			 Signature signature
			 ) throws XMLStreamException {
		super(name, new ContentAuthenticator(publisher, ContentType.LINK, locator), null, 
				(Signature)null);
		_signature = signature;
		_data = new LinkReferenceData(targetName, targetAuthenticator);
		_content = _data.encode();
	}
	
	public LinkReference(ContentName name,
			 ContentName targetName,
			 LinkAuthenticator targetAuthenticator,
			 PublisherKeyID publisher, 
			 KeyLocator locator,
			 PrivateKey signingKey
			 ) throws XMLStreamException, InvalidKeyException, SignatureException {
		this(name, targetName, targetAuthenticator, publisher, locator, (Signature)null);
		_signature = sign(name, authenticator(), _content, signingKey);
	}
	
	public LinkReference(ContentName name,
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
	public LinkReference() {}
	
	public static LinkReference contentToLinkReference(ContentObject co) throws XMLStreamException {
		LinkReference reference = new LinkReference();
		reference.decode(co.encode());
		reference.decodeData();
		return reference;
	}
	
	private void decodeData() throws XMLStreamException {
		_data = new LinkReferenceData();
		_data.decode(_content);
	}
	
	public ContentName targetName() { return _data.targetName(); }
	public LinkAuthenticator targetAuthenticator() { return _data._targetAuthenticator; }	
}
