package com.parc.ccn.data.content;

import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.util.LinkedList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.Signature;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.util.XMLDecoder;

/**
 * 
 * @author rasmusse
 * 
 * Being replaced by CollectionObject.
 *
 */
@Deprecated
public class Collection extends ContentObject {
	protected CollectionData _data = new CollectionData();
	
	public Collection(ContentName name,
			 LinkReference[] references,
			 PublisherPublicKeyDigest publisher, 
			 KeyLocator locator,
			 Signature signature
			 ) throws XMLStreamException {
		super(name, new SignedInfo(publisher, locator));
		if (null != references) {
			for (LinkReference reference : references) {
				_data.add(reference);
			}
		}
		_content = _data.encode();
		_signature = signature;
	}
	
	public Collection(ContentName name,
			 LinkReference[] references,
			 PublisherPublicKeyDigest publisher, 
			 KeyLocator locator,
			 PrivateKey signingKey
			 ) throws XMLStreamException, InvalidKeyException, SignatureException {
		this(name, references, publisher, locator, (Signature)null);
    	_signature = sign(name, signedInfo(), _content, 0, _content.length, signingKey);
	}

	public Collection() {} // for use by decoders
	
	/**
	 * Need to make final objects sometimes, for which we
	 * need an atomic create from byte array option. But
	 * if we do it with a constructor, we run into the problem
	 * that each subclass must reimplement it, to be sure
	 * that their members are constructed prior to decoding.
	 * So do it this way.
	 * @throws XMLStreamException 
	 */
	public static Collection newCollection(byte [] encodedCollection) throws XMLStreamException {
		Collection newCollection = new Collection();
		newCollection.decode(encodedCollection);
		return newCollection;
	}

	public LinkedList<LinkReference> contents() { 
		return _data.contents(); 
	}
	
	public static Collection contentToCollection(ContentObject co) throws XMLStreamException {
		Collection collection = new Collection();
		collection.decode(co.encode()); // calls decodeData
		return collection;
	}
	
	private void decodeData() throws XMLStreamException {
		_data = new CollectionData();
		_data.decode(_content);
	}
	
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		super.decode(decoder);
		decodeData();
	}
		
	public LinkReference get(int i) {
		return contents().get(i);
	}
	
	public int size() { return contents().size(); }

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + ((_data == null) ? 0 : _data.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		Collection other = (Collection) obj;
		if (_data == null) {
			if (other._data != null)
				return false;
		} else if (!_data.equals(other._data))
			return false;
		return true;
	}

}
