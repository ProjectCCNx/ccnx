package com.parc.ccn.data.content;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.data.util.CCNEncodableObject;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;
import com.parc.ccn.library.CCNLibrary;

/**
 * Mapping from a link to the underlying XML representation.
 * Basically a Link is a content object containing
 * a name and optionally some content authentication
 * information to specify whose value for the name
 * to take.
 * @author smetters
 *
 */
public class LinkReference extends GenericXMLEncodable implements XMLEncodable, Cloneable {
	
	/**
	 * This should eventually be called Link, and the Link class deleted.
	 */
	public static class LinkObject extends CCNEncodableObject<LinkReference> {
		
		/**
		 * Write constructor. Doesn't save until you call save, in case you want to tweak things first.
		 * @param name
		 * @param data
		 * @param library
		 * @throws ConfigurationException
		 * @throws IOException
		 */
		public LinkObject(ContentName name, LinkReference data, CCNLibrary library) throws IOException {
			super(LinkReference.class, name, data, library);
		}
		
		public LinkObject(ContentName name, LinkReference data, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNLibrary library) throws IOException {
			super(LinkReference.class, name, data, publisher, keyLocator, library);
		}

		/**
		 * Read constructor -- opens existing object.
		 * @param name
		 * @param library
		 * @throws XMLStreamException
		 * @throws IOException
		 * @throws ClassNotFoundException 
		 */
		public LinkObject(ContentName name, PublisherPublicKeyDigest publisher, CCNLibrary library) throws IOException, XMLStreamException {
			super(LinkReference.class, name, publisher, library);
		}
		
		public LinkObject(ContentName name, CCNLibrary library) throws IOException, XMLStreamException {
			super(LinkReference.class, name, (PublisherPublicKeyDigest)null, library);
		}
		
		public LinkObject(ContentObject firstBlock, CCNLibrary library) throws IOException, XMLStreamException {
			super(LinkReference.class, firstBlock, library);
		}
		
<<<<<<< HEAD:Java_CCN/com/parc/ccn/data/content/LinkReference.java
		/**
		 * Subclasses that need to write an object of a particular type can override.
		 * @return
		 */
		@Override
		public ContentType contentType() { return ContentType.LINK; }

=======
>>>>>>> Added network object version of link.:Java_CCN/com/parc/ccn/data/content/LinkReference.java
		public ContentName getTargetName() { 
			LinkReference lr = getReference();
			if (null == lr)
				return null;
			return lr.targetName(); 
		}

		public LinkAuthenticator getTargetAuthenticator() { 
			LinkReference lr = getReference();
			if (null == lr)
				return null;
			return lr.targetAuthenticator(); 
		}

		public LinkReference getReference() { 
			if (null == data())
				return null;
			return data(); 
		}
	}

	protected static final String LINK_ELEMENT = "Link";
	protected static final String LABEL_ELEMENT = "Label"; // overlaps with WrappedKey.LABEL_ELEMENT,
															// shared dictionary entry	
	protected ContentName _targetName;
	protected String _targetLabel;
	protected LinkAuthenticator _targetAuthenticator = null;
	
	public LinkReference(ContentName targetName, String targetLabel, LinkAuthenticator targetAuthenticator) {
		_targetName = targetName;
		if ((null != targetLabel) && (targetLabel.length() == 0))
			targetLabel = null;
		_targetLabel = targetLabel;
		_targetAuthenticator = targetAuthenticator;
	}

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
	
	/**
	 * Copy constructor. If we want to change name or authenticator,
	 * should clone those as well.
	 */
	public LinkReference(LinkReference other) {
		_targetName = other.targetName();
		_targetLabel = other.targetLabel();
		_targetAuthenticator = other.targetAuthenticator();
	}
	
	public ContentName targetName() { return _targetName; }
	public String targetLabel() { return _targetLabel; }
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
		
		if (decoder.peekStartElement(LABEL_ELEMENT)) {
			_targetLabel = decoder.readUTF8Element(LABEL_ELEMENT); 
		}

		if (decoder.peekStartElement(LinkAuthenticator.LINK_AUTHENTICATOR_ELEMENT)) {
			_targetAuthenticator = new LinkAuthenticator();
			_targetAuthenticator.decode(decoder);
		}

		decoder.readEndElement();
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {

		encoder.writeStartElement(LINK_ELEMENT);
		_targetName.encode(encoder);
		if (null != targetLabel()) {
			encoder.writeElement(LABEL_ELEMENT, targetLabel());
		}
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
				+ ((_targetLabel == null) ? 0 : _targetLabel.hashCode());
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
		LinkReference other = (LinkReference) obj;
		if (_targetAuthenticator == null) {
			if (other._targetAuthenticator != null)
				return false;
		} else if (!_targetAuthenticator.equals(other._targetAuthenticator))
			return false;
		if (_targetLabel == null) {
			if (other._targetLabel != null)
				return false;
		} else if (!_targetLabel.equals(other._targetLabel))
			return false;
		if (_targetName == null) {
			if (other._targetName != null)
				return false;
		} else if (!_targetName.equals(other._targetName))
			return false;
		return true;
	}

	@Override
	public Object clone() throws CloneNotSupportedException {
		return new LinkReference(this);
	}

}
