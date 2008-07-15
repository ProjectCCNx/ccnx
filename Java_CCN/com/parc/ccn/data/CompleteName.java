package com.parc.ccn.data;

import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.sql.Timestamp;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.Signature;
import com.parc.ccn.data.security.ContentAuthenticator.ContentType;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

/**
 * We sometimes need to refer to the "complete" name
 * of an object -- the unique combination of a ContentName
 * and a ContentAuthenticator. The authenticator can
 * be null, as can any of its fields.
 * @author smetters
 *
 */
public class CompleteName extends GenericXMLEncodable implements XMLEncodable {
	
	protected static final String COMPLETE_NAME_ELEMENT = "CompleteName";

	protected ContentName _name;
	protected ContentAuthenticator _authenticator;
	protected Signature _signature; 
	
	/**
	 * TODO: DKS figure out how to handle encoding faster,
	 * and how to handle shorter version of names without
	 * copying, particularly without 1.6 array ops.
	 * @param name
	 * @param authenticator
	 */
	public CompleteName(ContentName name, 
						ContentAuthenticator authenticator,
						Signature signature) {
		_name = name;
		_authenticator = authenticator;
		_signature = signature;
	}
	
	public CompleteName() {} // for use by decoders

	public ContentName name() { return _name; }
	
	public ContentAuthenticator authenticator() { return _authenticator; }
	
	public Signature signature() { return _signature; }
	
	/**
	 * Thought about encoding and decoding as flat -- no wrapping
	 * declaration. But then couldn't use these solo.
	 */
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(COMPLETE_NAME_ELEMENT);

		_signature = new Signature();
		_signature.decode(decoder);
		
		_name = new ContentName();
		_name.decode(decoder);
		
		if (decoder.peekStartElement(ContentAuthenticator.CONTENT_AUTHENTICATOR_ELEMENT)) {
			_authenticator = new ContentAuthenticator();
			_authenticator.decode(decoder);
		}
		
		decoder.readEndElement();
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(COMPLETE_NAME_ELEMENT);
		
		signature().encode(encoder);

		name().encode(encoder);
		
		if (null != authenticator())
			authenticator().encode(encoder);
		
		encoder.writeEndElement();   		
	}
	
	public boolean validate() {
		// null authenticator ok
		return (null != name());
	}
	
	/**
	 * No longer need unique names, but keep API for now so
	 * as to not break existing code.
     * @param signingKey if null, only generates unique name
     * 	and filled-in content authenticator. Can complete using
     *  completeName.authenticator.sign(completeName.name, signingKey).
     *  
     * @return
     * @throws SignatureException 
     * @throws InvalidKeyException 
     */
	public static CompleteName generateAuthenticatedName(
	   		ContentName name,
    		PublisherKeyID publisher,
    		Timestamp timestamp,
    		ContentType type,
    		KeyLocator locator,
    		byte [] contentOrDigest, // may be already hashed
    		//boolean isDigest, // should we digest it or is it already done?
    		PrivateKey signingKey) throws SignatureException, InvalidKeyException {
		
		// Generate raw authenticator.
		ContentAuthenticator authenticator = new ContentAuthenticator(publisher, timestamp, type, locator);
		Signature signature = null;
		if (null != signingKey)
			signature = ContentObject.sign(name, authenticator, contentOrDigest, signingKey);
		return new CompleteName(name, authenticator, signature);
	}
	    
	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_authenticator == null) ? 0 : _authenticator.hashCode());
		result = PRIME * result + ((_name == null) ? 0 : _name.hashCode());
		result = PRIME * result + ((_signature == null) ? 0 : _signature.hashCode());
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
		final CompleteName other = (CompleteName) obj;
		if (_authenticator == null) {
			if (other._authenticator != null)
				return false;
		} else if (!_authenticator.equals(other._authenticator))
			return false;
		if (_name == null) {
			if (other._name != null)
				return false;
		} else if (!_name.equals(other._name))
			return false;
		if (_signature == null) {
			if (other.signature() != null)
				return false;
		} else if (!_signature.equals(other.signature()))
			return false;
		return true;
	}
}
