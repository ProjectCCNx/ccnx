package com.parc.ccn.data.content;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

/**
 * Mapping from a link to the underlying XML representation.
 * Basically a Link is a content object containing
 * a name and optionally some content authentication
 * information to specify whose value for the name
 * to take.
 * @author smetters
 *
 */
public class LinkReference extends GenericXMLEncodable implements XMLEncodable {
	
	protected static final String LINK_ELEMENT = "Link";
	
	protected ContentName _targetName;
	protected LinkAuthenticator _targetAuthenticator = null;
	
	public LinkReference(ContentName targetName, LinkAuthenticator targetAuthenticator) {
		_targetName = targetName;
		_targetAuthenticator = targetAuthenticator;
	}
	
	public LinkReference(ContentName targetName) {
		this(targetName, null);
	}
	
	/**
	 * Decoding constructor.
	 */
	public LinkReference() {}
	
	public ContentName targetName() { return _targetName; }
	public LinkAuthenticator targetAuthenticator() { return _targetAuthenticator; }
		
	/**
	 * XML format:
	 * @throws XMLStreamException 
	 * 
	 */
	
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(LINK_ELEMENT);

		_targetName = new ContentName();
		_targetName.decode(decoder);
		
		if (decoder.peekStartElement(LinkAuthenticator.LINK_AUTHENTICATOR_ELEMENT)) {
			_targetAuthenticator = new LinkAuthenticator();
			_targetAuthenticator.decode(decoder);
		}

		decoder.readEndElement();
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {

		encoder.writeStartElement(LINK_ELEMENT);
		_targetName.encode(encoder);
		if (null != _targetAuthenticator)
			_targetAuthenticator.encode(encoder);
		encoder.writeEndElement();   		
	}
	
	public boolean validate() {
		return (null != targetName());
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime
				* result
				+ ((_targetAuthenticator == null) ? 0 : _targetAuthenticator
						.hashCode());
		result = prime * result
				+ ((_targetName == null) ? 0 : _targetName.hashCode());
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
		final LinkReference other = (LinkReference) obj;
		if (_targetAuthenticator == null) {
			if (other._targetAuthenticator != null)
				return false;
		} else if (!_targetAuthenticator.equals(other._targetAuthenticator))
			return false;
		if (_targetName == null) {
			if (other._targetName != null)
				return false;
		} else if (!_targetName.equals(other._targetName))
			return false;
		return true;
	}
}
