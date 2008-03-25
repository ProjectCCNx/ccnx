package com.parc.ccn.data;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.sql.Timestamp;
import java.util.Arrays;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.Library;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.ContentAuthenticator.ContentType;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLHelper;
import com.parc.ccn.security.crypto.DigestHelper;

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
    protected static final String SIGNATURE_ELEMENT = "Signature";

	protected ContentName _name;
	protected ContentAuthenticator _authenticator;
	protected byte [] _signature; // DKS might want to use signature type
	
	/**
	 * TODO: DKS figure out how to handle encoding faster,
	 * and how to handle shorter version of names without
	 * copying, particularly without 1.6 array ops.
	 * @param name
	 * @param authenticator
	 */
	public CompleteName(ContentName name, 
						Integer nameComponentCount,
						ContentAuthenticator authenticator,
						byte [] signature) {
		if ((null == nameComponentCount) || (nameComponentCount == name.count())) {
			_name = name;
		} else {
			_name = name.copy(nameComponentCount);
		}
		_authenticator = authenticator;
		_signature = signature;
	}
	
	public CompleteName(ContentName name, ContentAuthenticator authenticator, byte [] signature) {
		this(name, null, authenticator, signature);
	}

	public CompleteName() {} // for use by decoders

	public ContentName name() { return _name; }
	
	public ContentAuthenticator authenticator() { return _authenticator; }
	
	public byte [] signature() { return _signature; }
	
	/**
	 * Thought about encoding and decoding as flat -- no wrapping
	 * declaration. But then couldn't use these solo.
	 */
	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, COMPLETE_NAME_ELEMENT);

		_name = new ContentName();
		_name.decode(reader);
		
		if (XMLHelper.peekStartElement(reader, ContentAuthenticator.CONTENT_AUTHENTICATOR_ELEMENT)) {
			_authenticator = new ContentAuthenticator();
			_authenticator.decode(reader);
		}
		
		String strSignature = XMLHelper.readElementText(reader, SIGNATURE_ELEMENT); 
		try {
			_signature = XMLHelper.decodeElement(strSignature);
		} catch (IOException e) {
			throw new XMLStreamException("Cannot decode signature : " + strSignature, e);
		}

		XMLHelper.readEndElement(reader);
	}

	public void encode(XMLStreamWriter writer, boolean isFirstElement) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		XMLHelper.writeStartElement(writer, COMPLETE_NAME_ELEMENT, isFirstElement);
		
		name().encode(writer);
		
		if (null != authenticator())
			authenticator().encode(writer);
		
		if (null != signature()) {
			// needs to handle null content
			XMLHelper.writeElement(writer, SIGNATURE_ELEMENT, 
					XMLHelper.encodeElement(_signature));
		}

		writer.writeEndElement();   		
	}
	
	public boolean validate() {
		// DKS -- do we do recursive validation?
		// null authenticator ok
		return (null != name());
	}
	
	   /**
     * Generate unique name and authentication information.
     * @param signingKey if null, only generates unique name
     * 	and filled-in content authenticator. Can complete using
     *  completeName.authenticator.sign(completeName.name, signingKey).
     *  
     *  TODO DKS is this a low-level or library level convention?
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
    		boolean isDigest, // should we digest it or is it already done?
    		PrivateKey signingKey) throws SignatureException, InvalidKeyException {
		
		// Generate raw authenticator.
		ContentAuthenticator authenticator =
			new ContentAuthenticator(publisher, null, timestamp, type,
					   				 locator, contentOrDigest, isDigest);
		byte [] authenticatorDigest = null;
		try {
			authenticatorDigest =
				DigestHelper.digest(
						authenticator.canonicalizeAndEncode());
		} catch (XMLStreamException e) {
			Library.handleException("Exception encoding internally-generated XML!", e);
			throw new SignatureException(e);
		}
		
		ContentName fullName = 
			new ContentName(name, authenticatorDigest);
		byte [] signature = null;
		if (null != signingKey)
			signature = ContentObject.sign(fullName, fullName.count(), authenticator, signingKey);
		return new CompleteName(fullName, authenticator, signature);
	}
	    


	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_authenticator == null) ? 0 : _authenticator.hashCode());
		result = PRIME * result + ((_name == null) ? 0 : _name.hashCode());
		result = PRIME * result + Arrays.hashCode(_signature);
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
		if (!Arrays.equals(_signature, other._signature))
			return false;
		return true;
	}
}
