package com.parc.ccn.data;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SignatureException;
import java.util.Arrays;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLHelper;

/**
 * Utility class for grouping all of the bits associated
 * with a piece of content.
 * @author smetters
 *
 */
public class ContentObject extends GenericXMLEncodable implements XMLEncodable {
	
	protected static final String CONTENT_OBJECT_ELEMENT = "Mapping";
	protected static final String CONTENT_ELEMENT = "Content";
	
	protected ContentName _name;
	protected ContentAuthenticator _authenticator;
    protected byte [] _content;
    
    public ContentObject(ContentName name,
    					 ContentAuthenticator authenticator,
    					 byte [] content) {
    	_name = name;
    	_authenticator = authenticator;
    	_content = content;
    }
    
    public ContentObject() {} // for use by decoders
    
    public ContentName name() { 
    	return _name;
    }
    
    public ContentAuthenticator authenticator() { 
    	return _authenticator;
    }
    
    public byte [] content() { return _content; }

	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, CONTENT_OBJECT_ELEMENT);

		_name = new ContentName();
		_name.decode(reader);
		
		_authenticator = new ContentAuthenticator();
		_authenticator.decode(reader);
		
		String strContent = XMLHelper.readElementText(reader, CONTENT_ELEMENT); 
		try {
			_content = XMLHelper.decodeElement(strContent);
		} catch (IOException e) {
			throw new XMLStreamException("Cannot decode content : " + strContent, e);
		}
		
		XMLHelper.readEndElement(reader);
	}

	public void encode(XMLStreamWriter writer, boolean isFirstElement) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		XMLHelper.writeStartElement(writer, CONTENT_OBJECT_ELEMENT, isFirstElement);

		name().encode(writer);
		authenticator().encode(writer);

		// needs to handle null content
		XMLHelper.writeElement(writer, CONTENT_ELEMENT, 
				XMLHelper.encodeElement(_content));

		writer.writeEndElement();   		
	}
	
	public boolean validate() { 
		// recursive?
		// null content ok
		return ((null != name()) && (null != authenticator()));
	}

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_name == null) ? 0 : _name.hashCode());
		result = PRIME * result + ((_authenticator == null) ? 0 : _authenticator.hashCode());
		result = PRIME * result + Arrays.hashCode(_content);
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
		final ContentObject other = (ContentObject) obj;
		if (_name == null) {
			if (other.name() != null)
				return false;
		} else if (!_name.equals(other.name()))
			return false;
		if (_authenticator == null) {
			if (other.authenticator() != null)
				return false;
		} else if (!_authenticator.equals(other.authenticator()))
			return false;
		if (!Arrays.equals(_content, other._content))
			return false;
		return true;
	}
	
	/**
	 * Need a low-level signing and verification interface that
	 * can be used by things with only access to low-level
	 * interfaces. Put that code in the authenticator.
	 * @param publicKey The key to use to attempt to verify
	 * 	the signature. If null, the key locator will be used
	 *  to pull the key.
	 * @throws XMLStreamException 
	 * @throws NoSuchAlgorithmException 
	 * @throws SignatureException 
	 * @throws InvalidKeyException 
	 */
	public boolean verify(PublicKey publicKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, XMLStreamException {
		return ContentAuthenticator.verify(this, true, publicKey);
	}
}
