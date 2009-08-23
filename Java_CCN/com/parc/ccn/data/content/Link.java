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
import com.parc.ccn.library.profiles.VersioningProfile;

/**
 * Mapping from a link to the underlying XML representation.
 * Basically a Link is a content object containing
 * a name and optionally some content authentication
 * information to specify whose value for the name
 * to take.
 * @author smetters
 *
 */
public class Link extends GenericXMLEncodable implements XMLEncodable, Cloneable {
	
	/**
	 * This should eventually be called Link, and the Link class deleted.
	 */
	public static class LinkObject extends CCNEncodableObject<Link> {
		
		/**
		 * Write constructor. Doesn't save until you call save, in case you want to tweak things first.
		 * @param name
		 * @param data
		 * @param library
		 * @throws ConfigurationException
		 * @throws IOException
		 */
		public LinkObject(ContentName name, Link data, CCNLibrary library) throws IOException {
			super(Link.class, name, data, library);
		}
		
		public LinkObject(ContentName name, Link data, PublisherPublicKeyDigest publisher, KeyLocator keyLocator, CCNLibrary library) throws IOException {
			super(Link.class, name, data, publisher, keyLocator, library);
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
			super(Link.class, name, publisher, library);
		}
		
		public LinkObject(ContentName name, CCNLibrary library) throws IOException, XMLStreamException {
			super(Link.class, name, (PublisherPublicKeyDigest)null, library);
		}
		
		public LinkObject(ContentObject firstBlock, CCNLibrary library) throws IOException, XMLStreamException {
			super(Link.class, firstBlock, library);
		}
		
		/**
		 * Subclasses that need to write an object of a particular type can override.
		 * @return
		 */
		@Override
		public ContentType contentType() { return ContentType.LINK; }

		public ContentName getTargetName() { 
			Link lr = link();
			if (null == lr)
				return null;
			return lr.targetName(); 
		}

		public LinkAuthenticator getTargetAuthenticator() { 
			Link lr = link();
			if (null == lr)
				return null;
			return lr.targetAuthenticator(); 
		}

		public Link link() { 
			if (null == data())
				return null;
			return data(); 
		}
		
		public ContentObject dereference(long timeout) throws IOException {
			if (null == data())
				return null;
			return link().dereference(timeout, _library);
		}
	}

	protected static final String LINK_ELEMENT = "Link";
	protected static final String LABEL_ELEMENT = "Label"; // overlaps with WrappedKey.LABEL_ELEMENT,
															// shared dictionary entry	
	protected ContentName _targetName;
	protected String _targetLabel;
	protected LinkAuthenticator _targetAuthenticator = null;
	
	public Link(ContentName targetName, String targetLabel, LinkAuthenticator targetAuthenticator) {
		_targetName = targetName;
		if ((null != targetLabel) && (targetLabel.length() == 0))
			targetLabel = null;
		_targetLabel = targetLabel;
		_targetAuthenticator = targetAuthenticator;
	}

	public Link(ContentName targetName, LinkAuthenticator targetAuthenticator) {
		_targetName = targetName;
		_targetAuthenticator = targetAuthenticator;
	}
	
	public Link(ContentName targetName) {
		this(targetName, null);
	}
	
	/**
	 * Decoding constructor.
	 */
	public Link() {}
	
	/**
	 * Copy constructor. If we want to change name or authenticator,
	 * should clone those as well.
	 */
	public Link(Link other) {
		_targetName = other.targetName();
		_targetLabel = other.targetLabel();
		_targetAuthenticator = other.targetAuthenticator();
	}
	
	public ContentName targetName() { return _targetName; }
	public String targetLabel() { return _targetLabel; }
	public LinkAuthenticator targetAuthenticator() { return _targetAuthenticator; }
		
	/**
	 * A stab at a dereference() method. Dereferencing is not well-defined in this
	 * general setting -- we don't know what we'll find down below this name. A link may
	 * link to anything in the tree, including an intermediate node, or a name qualified
	 * down to the digest, and we need a way of distinguishing these things (TODO). Usually
	 * you'll read the target of the link using a method that knows something about what
	 * kind of data to find there. This is a brute-force method that hands you back a block
	 * underneath the link target name that meets the authentication criteria; at minimum
	 * it should pull an exact match if the link fully specifies digests and so on (TODO -- TBD),
	 * and otherwise it'll probably assume that what is below here is either a version and
	 * segments (get latest version) or that this is versioned and it wants segments.
	 * 
	 * @return Returns a child object. Verifies that it meets the requirement of the link,
	 *   and that it is signed by who it claims. Could allow caller to pass in verifier
	 *   to verify higher-level trust and go look for another block on failure.
	 * @throws IOException 
	 */
	public ContentObject dereference(long timeout, CCNLibrary library) throws IOException {
		
		// getLatestVersion will return the latest version of an unversioned name, or the
		// latest version after a given version. So if given a specific version, get that one.
		if (VersioningProfile.hasTerminalVersion(targetName())) {
			return library.get(targetName(), (null != targetAuthenticator()) ? targetAuthenticator().publisher() : null, timeout);
		}
		// Don't know if we are referencing a particular object, so don't look for segments.
		PublisherPublicKeyDigest desiredPublisher = (null != targetAuthenticator()) ? targetAuthenticator().publisher() : null;
		return VersioningProfile.getLatestVersion(targetName(), 
				desiredPublisher, timeout, new ContentObject.SimpleVerifier(desiredPublisher), library);
	}
	
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
		Link other = (Link) obj;
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
		return new Link(this);
	}

}
