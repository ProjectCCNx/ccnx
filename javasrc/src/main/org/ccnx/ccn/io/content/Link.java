/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2013 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.util.EnumSet;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.io.CCNAbstractInputStream.FlagTypes;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * Represents a secure, authenticatable link from one part of the CCN namespace to another.
 * 
 * CCN links are very flexible and can be used to represent a wide variety of application-level
 * structures. A link can point to a specific content object (an individual block of content),
 * the collection of "segments" making up a specific version of a stream or document, an aggregated
 * "document" object consisting of multiple versions and their associated metadata, or to an arbitrary
 * point in the name tree -- essentially saying "treat the children of this node as if they
 * were my children".
 * 
 * CCN links have authentication information associated with them, and can be made highly secure --
 * by specifying who should have published (signed) the target of a given link, one can say effectively
 * "what I mean by name N is whatever Tom means by name N'". The authentication information
 * associated with a Link is called a LinkAuthenticator; its form and capabilities are still
 * evolving, but it will at least have the ability to offer indirection -- "trust anyone whose
 * key is signed by key K to have signed a valid target for this link".
 * 
 * Links also play an important role in making up Collections, the CCN notion of a container full
 * of objects or names.
 */
public class Link extends GenericXMLEncodable implements XMLEncodable, Cloneable {
	
	/**
	 * A CCNNetworkObject wrapper around Link, used for easily saving and retrieving
	 * versioned Links to CCN. A typical pattern for using network objects to save
	 * objects that happen to be encodable or serializable is to incorporate such a static
	 * member wrapper class subclassing CCNEncodableObject, CCNSerializableObject, or
	 * CCNNetworkObject itself inside the main class definition.
	 */
	public static class LinkObject extends CCNEncodableObject<Link> {
		
		public LinkObject(ContentName name, Link data, SaveType saveType, CCNHandle handle) throws IOException {
			super(Link.class, true, name, data, saveType, handle);
		}
		
		public LinkObject(ContentName name, Link data, SaveType saveType,
						  PublisherPublicKeyDigest publisher, 
						  KeyLocator keyLocator, CCNHandle handle) throws IOException {
			super(Link.class, true, name, data, saveType,
					publisher, keyLocator, handle);
		}

		public LinkObject(ContentName name, CCNHandle handle) 
				throws ContentDecodingException, IOException {
			super(Link.class, true, name, (PublisherPublicKeyDigest)null, handle);
		}
		
		public LinkObject(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle handle) 
				throws ContentDecodingException, IOException {
			super(Link.class, true, name, publisher, handle);
		}

		public LinkObject(ContentObject firstBlock, CCNHandle handle) 
				throws ContentDecodingException, IOException {
			super(Link.class, true, firstBlock, handle);
		}
		
		public LinkObject(ContentName name, PublisherPublicKeyDigest publisher,
				CCNFlowControl flowControl) throws ContentDecodingException,
				IOException {
			super(Link.class, true, name, publisher, flowControl);
		}

		public LinkObject(ContentObject firstBlock, CCNFlowControl flowControl)
				throws ContentDecodingException, IOException {
			super(Link.class, true, firstBlock, flowControl);
		}

		public LinkObject(ContentName name, Link data, PublisherPublicKeyDigest publisher,
				KeyLocator keyLocator, CCNFlowControl flowControl)
				throws IOException {
			super(Link.class, true, name, data, publisher, keyLocator, flowControl);
		}

		public LinkObject(CCNEncodableObject<? extends Link> other) {
			super(Link.class, other);
		}
		
		/**
		 * Subclasses that need to write an object of a particular type can override.
		 * @return Content type to use.
		 */
		@Override
		public ContentType contentType() { return ContentType.LINK; }

		public ContentName getTargetName() throws ContentGoneException, ContentNotReadyException, ErrorStateException { 
			Link lr = link();
			if (null == lr)
				return null;
			return lr.targetName(); 
		}

		public LinkAuthenticator getTargetAuthenticator() throws ContentNotReadyException, ContentGoneException, ErrorStateException { 
			Link lr = link();
			if (null == lr)
				return null;
			return lr.targetAuthenticator(); 
		}

		public Link link() throws ContentNotReadyException, ContentGoneException, ErrorStateException { 
			if (null == data())
				return null;
			return data(); 
		}
		
		public ContentObject dereference(long timeout) throws IOException {
			if (null == data())
				return null;
			return link().dereference(timeout, _handle);
		}
		
		/**
		 * Modify the properties of the input streams we read to read links themselves,
		 * rather than dereferencing them and causing an infinite loop; must modify
		 * in constructor to handle passed in content objects..
		 */
		@Override
		protected EnumSet<FlagTypes> getInputStreamFlags() {
			return EnumSet.of(FlagTypes.DONT_DEREFERENCE);
		}
	}

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
	
	public Link(Link other) {
		_targetName = other.targetName();
		_targetLabel = other.targetLabel();
		_targetAuthenticator = other.targetAuthenticator();
	}
	
	public ContentName targetName() { return _targetName; }
	public String targetLabel() { return _targetLabel; }
	public LinkAuthenticator targetAuthenticator() { return _targetAuthenticator; }
	
	public void setTargetLabel(String label) {
		_targetLabel = label;
	}
	
	public void setTargetName(ContentName name) {
		_targetName = name;
	}
	
	public void setTargetAuthenticator(LinkAuthenticator authenticator) {
		_targetAuthenticator = authenticator;
	}
	
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
	 * @param timeout How long to try for, in milliseconds.
	 * @param handle Handle to use. Should not be null.
	 * @return Returns a child object. Verifies that it meets the requirement of the link,
	 *   and that it is signed by who it claims. Could allow caller to pass in verifier
	 *   to verify higher-level trust and go look for another block on failure.
	 * @throws IOException 
	 */
	public ContentObject dereference(long timeout, CCNHandle handle) throws IOException {
		
		// getLatestVersion will return the latest version of an unversioned name, or the
		// latest version after a given version. So if given a specific version, get that one.
		// TODO -- verify, use non-default verifier.
		if (VersioningProfile.hasTerminalVersion(targetName())) {
			return handle.get(targetName(), (null != targetAuthenticator()) ? targetAuthenticator().publisher() : null, timeout);
		}
		// Don't know if we are referencing a particular object, so don't look for segments.
		PublisherPublicKeyDigest desiredPublisher = (null != targetAuthenticator()) ? targetAuthenticator().publisher() : null;
		ContentObject result = VersioningProfile.getLatestVersion(targetName(), 
				desiredPublisher, timeout, 
				new ContentObject.SimpleVerifier(desiredPublisher, handle.keyManager()), handle);
		if (null != result) {
			return result;
		}
		// Alright, last shot -- resolve link to unversioned data.
		Interest unversionedInterest = SegmentationProfile.anySegmentInterest(targetName(),
				(null != targetAuthenticator()) ? targetAuthenticator().publisher() : null);

		result = handle.get(unversionedInterest, timeout);
		if ((null != result) && !SegmentationProfile.isSegment(result.name())) {
			return null;
		}
		return result;
	}
	
	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());

		_targetName = new ContentName();
		_targetName.decode(decoder);
		
		if (decoder.peekStartElement(CCNProtocolDTags.Label)) {
			_targetLabel = decoder.readUTF8Element(CCNProtocolDTags.Label); 
		}

		if (decoder.peekStartElement(CCNProtocolDTags.LinkAuthenticator)) {
			_targetAuthenticator = new LinkAuthenticator();
			_targetAuthenticator.decode(decoder);
		}

		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		
		if (!validate())
			throw new ContentEncodingException("Link failed to validate!");

		encoder.writeStartElement(getElementLabel());
		_targetName.encode(encoder);
		if (null != targetLabel()) {
			encoder.writeElement(CCNProtocolDTags.Label, targetLabel());
		}
		if (null != _targetAuthenticator)
			_targetAuthenticator.encode(encoder);
		encoder.writeEndElement();   		
	}
	
	@Override
	public long getElementLabel() { return CCNProtocolDTags.Link; }

	@Override
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
	
	/**
	 * Return true if this link matches target on all fields where
	 * target is non-null.
	 * @param linkToMatch The specification of the values we want.
	 * @return
	 */
	public boolean approximates(Link linkToMatch) {
		if (null != _targetName) {
			if (null == linkToMatch._targetName)
				return false;
			if (!linkToMatch._targetName.equals(_targetName)) {
				if (VersioningProfile.hasTerminalVersion(linkToMatch._targetName) && 
						!VersioningProfile.hasTerminalVersion(_targetName)) {
					if (!_targetName.isPrefixOf(linkToMatch._targetName)) {
						return false;
					}
				} else {
					return false;
				}
			}
		}
		if (null != _targetLabel) {
			if (null == linkToMatch._targetLabel)
				return false;
			if (!linkToMatch._targetLabel.equals(_targetLabel))
				return false;
		}
		if (null != _targetAuthenticator) {
			if (null == linkToMatch._targetAuthenticator)
				return false;
			return _targetAuthenticator.approximates(linkToMatch._targetAuthenticator);
		}
		return true;
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
	public Link clone() throws CloneNotSupportedException {
		Link l = (Link)super.clone();
		l._targetName = _targetName;
		l._targetLabel = _targetLabel;
		l._targetAuthenticator = _targetAuthenticator;
		return l;
	}

	@Override
	public String toString() {
		return "Link [targetName=" + targetName() + 
				", targetLabel=" + targetLabel() + 
				", targetAuthenticator=" + targetAuthenticator() + "]";
	}

}
