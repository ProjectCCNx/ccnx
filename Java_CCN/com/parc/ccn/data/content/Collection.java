package com.parc.ccn.data.content;

import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.Signature;
import com.parc.ccn.data.security.ContentAuthenticator.ContentType;

/**
 * 
 * @author rasmusse
 *
 */
public class Collection extends ContentObject {
	protected CollectionData _data = new CollectionData();
	
	public Collection(ContentName name,
			 LinkReferenceData[] references,
			 PublisherKeyID publisher, 
			 KeyLocator locator,
			 Signature signature
			 ) throws XMLStreamException {
		super(name, new ContentAuthenticator(publisher, ContentType.COLLECTION, locator), null, 
				(Signature)null);
		if (null != references) {
			for (LinkReferenceData reference : references) {
				_data.add(reference);
			}
		}
		_content = _data.encode();
		_signature = signature;
	}
	
	public Collection(ContentName name,
			 LinkReferenceData[] references,
			 PublisherKeyID publisher, 
			 KeyLocator locator,
			 PrivateKey signingKey
			 ) throws XMLStreamException, InvalidKeyException, SignatureException {
		this(name, references, publisher, locator, (Signature)null);
    	_signature = sign(name, authenticator(), _content, signingKey);
	}

	public Collection() {} // for use by decoders
	
	public ArrayList<LinkReferenceData> contents() { 
		return _data.contents(); 
	}
	
	public static Collection contentToCollection(ContentObject co) throws XMLStreamException {
		Collection collection = new Collection();
		collection.decode(co.encode());
		collection.decodeData();
		return collection;
	}
	
	private void decodeData() throws XMLStreamException {
		_data = new CollectionData();
		_data.decode(_content);
	}
		
	public LinkReferenceData get(int i) {
		return contents().get(i);
	}
	
	public int size() { return contents().size(); }
}
