/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.protocol;

import static org.ccnx.ccn.protocol.Component.NONCE;

import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Level;

import org.ccnx.ccn.TrustManager;
import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;


/**
 * Represents a CCN Interest packet, and performs all the allowed specializations
 * of queries recognized and supported by them.
 * cf. ContentObject
 * 
 * Implements Comparable to make it easy to store in a Set and avoid duplicates.
 */
public class Interest extends GenericXMLEncodable implements XMLEncodable, Comparable<Interest>, Cloneable, ContentNameProvider {
	
	// Used to remove spurious *'s
	public static final String RECURSIVE_POSTFIX = "*";
	
	// ChildSelector values
	public static final int CHILD_SELECTOR_LEFT = 0;
	public static final int CHILD_SELECTOR_RIGHT = 1;
	
	/**
	 * AnswerOriginKind values
	 * These are bitmapped.  Default is 3. 2 is not allowed
	 */
	public static final int ANSWER_CONTENT_STORE = 1;
	public static final int ANSWER_GENERATED = 2;
	public static final int ANSWER_STALE = 4;		// Stale answer OK
	public static final int MARK_STALE = 16;		// Must have Scope 0.  Michael calls this a "hack"
	
	public static final int DEFAULT_ANSWER_ORIGIN_KIND = ANSWER_CONTENT_STORE | ANSWER_GENERATED;

	protected ContentName _name;
	protected Integer _maxSuffixComponents;
	protected Integer _minSuffixComponents;
	// DKS TODO can we really support a PublisherID here, or just a PublisherPublicKeyDigest?
	protected PublisherID _publisher;
	protected Exclude _exclude;
	protected Integer _childSelector;
	
	protected Integer _answerOriginKind = null;
	protected Integer _scope;
	protected byte[] _interestLifetime = null;		// For now we don't have the ability to set an interest lifetime
	protected byte[] _nonce;

	public long userTime;
	
	/**
	 * TODO: DKS figure out how to handle encoding faster,
	 * and how to handle shorter version of names without
	 * copying, particularly without 1.6 array ops.
	 * @param name ContentName of Interest
	 * @param publisher PublisherID of Interest or null
	 */
	public Interest(ContentName name, 
			   PublisherID publisher) {
		_name = name;
		_publisher = publisher;
	}
	
	/**
	 * @param name ContentName of Interest
	 * @param publisher PublisherPublicKeyDigest or null
	 */
	public Interest(ContentName name, PublisherPublicKeyDigest publisher) {
		this(name, (null != publisher) ? new PublisherID(publisher) : (PublisherID)null);
	}
	
	/**
	 * Creates Interest with null publisher ID
	 * @param name
	 */
	public Interest(ContentName name) {
		this(name, (PublisherID)null);
	}
	
	public Interest(String name) throws MalformedContentNameStringException {
		this(ContentName.fromURI(name), (PublisherID)null);
	}

	public Interest() {} // for use by decoders

	public ContentName name() { return _name; }
	public void name(ContentName name) { _name = name; }
	
	public Integer maxSuffixComponents() { return _maxSuffixComponents; }
	public void maxSuffixComponents(Integer maxSuffixComponents) { _maxSuffixComponents = maxSuffixComponents; }
	
	public Integer minSuffixComponents() { return _minSuffixComponents; }
	public void minSuffixComponents(Integer minSuffixComponents) { _minSuffixComponents = minSuffixComponents; }
	
	public PublisherID publisherID() { return _publisher; }
	public void publisherID(PublisherID publisherID) { _publisher = publisherID; }
	
	public Exclude exclude() { return _exclude; }
	public void exclude(Exclude exclude) { _exclude = exclude; }
	
	public Integer childSelector() { return _childSelector;}
	public void childSelector(int childSelector) { _childSelector = childSelector; }
	
	public byte[] interestLifetime() { return _interestLifetime;}
	public void interestLifetime(byte[] interestLifetime) { _interestLifetime = interestLifetime; }
	
	public Integer answerOriginKind() { 
		if (null == _answerOriginKind) {
			return DEFAULT_ANSWER_ORIGIN_KIND;
		}
		return _answerOriginKind; 
	}
	
	public void answerOriginKind(int answerOriginKind) {
		if (DEFAULT_ANSWER_ORIGIN_KIND == answerOriginKind) {
			_answerOriginKind = null;
		} else {
			_answerOriginKind = answerOriginKind; 
		}
	}
	
	public Integer scope() { return _scope; }
	public void scope(int scope) { _scope = scope; }
	
	/**
	 * XXX - This isn't user settable and is only useful for ccnd internal functionality. 
	 * Do we ever need to return it?
	 * @return
	 */
	public byte[] nonce() { return _nonce; }

	/**
	 * Determine whether a piece of content matches the Interest
	 * @param test
	 * @return true if the test data packet matches the Interest
	 */
	public boolean matches(ContentObject test) {
		return matches(test, (null != test.signedInfo()) ? test.signedInfo().getPublisherKeyID() : null);
	}

	/**
	 * Determine whether a piece of content's name *without* digest component matches this Interest.
	 * 
	 * This doesn't match if the digest is specified in the Interest.
	 * @see Interest#matches(ContentObject, PublisherPublicKeyDigest)
	 *
	 * @param name - Name of a content object missing it's implied digest component
	 * @param resultPublisherKeyID
	 * @return true if the content/publisherPublicKeyDigest matches the Interest
	 */
	public boolean matches(ContentName name, PublisherPublicKeyDigest resultPublisherKeyID) {
		if (null == name() || null == name)
			return false; // null name() should not happen, null arg can
		// to get interest that matches everything, should
		// use / (ROOT)
		if (isPrefixOf(name)) {
			return internalMatch(name, false, resultPublisherKeyID);
		}
		return false;
	}
	
	/**
	 * Determine whether a piece of content matches this Interest.
	 * Note: this computes the digest for the ContentObject, to know the full name. This is
	 * computationally expensive.
	 * @see Interest#matches(ContentName, PublisherPublicKeyDigest)
	 * TODO: compute digests once when ContentObjects are received into the machine, and pass them
	 * around with the ContentObjects.
	 * 
	 * @param co - ContentObject
	 * @param resultPublisherKeyID
	 * @return true if the content & publisherID match the Interest
	 */
	public boolean matches(ContentObject co, PublisherPublicKeyDigest resultPublisherKeyID) {
		if (null == name() || null == co)
			return false; // null name() should not happen, null arg can
		// to get interest that matches everything, should
		// use / (ROOT)
		boolean digest = co.name().count()+1 == name().count();
		
		if (co.name().count() == name().count() && (exclude() != null && !exclude().empty())) {
			//the interest does not have a digest in the name, but it does have at least one excluded
			digest = true;
		}
		ContentName name = digest ? co.fullName() : co.name();
		if (isPrefixOf(name)) {
			return internalMatch(name, digest, resultPublisherKeyID);
		}
		return false;
	}
	
	// TODO We need to beef this up to deal with the more complex interest specs.
	private boolean internalMatch(ContentName name, boolean digestIncluded,
			PublisherPublicKeyDigest resultPublisherKeyID) {
		if (null != maxSuffixComponents() || null != minSuffixComponents()) {
			// we know our specified name is a prefix of the result. 
			// the number of additional components must be this value
			int nameCount = name.count();
			int lengthDiff = nameCount + (digestIncluded?0:1) - name().count();

			if (null != maxSuffixComponents() && lengthDiff > maxSuffixComponents()) {
				//Log.fine("Interest match failed: " + lengthDiff + " more than the " + maxSuffixComponents() + " components between expected " +
				//		name() + " and tested " + name);
				if(Log.isLoggable(Log.FAC_ENCODING, Level.FINE))
					Log.fine(Log.FAC_ENCODING, "Interest match failed: {0} more than the {1} components between expected {2} and tested {3}",lengthDiff, maxSuffixComponents(), name(), name);
				return false;
			}
			if (null != minSuffixComponents() && lengthDiff < minSuffixComponents()) {
				//Log.fine("Interest match failed: " + lengthDiff + " less than the " + minSuffixComponents() + " components between expected " +
				//		name() + " and tested " + name);
				if(Log.isLoggable(Log.FAC_ENCODING, Level.FINE))
					Log.fine(Log.FAC_ENCODING, "Interest match failed: {0} less than the {1} components between expected {2} and tested {3}",lengthDiff, minSuffixComponents(), name(), name);
				return false;
			}
		}
		if (null != exclude()) {
			if (exclude().match(name.component(name().count()))) {
				if (Log.isLoggable(Log.FAC_ENCODING, Level.FINEST))
					Log.finest(Log.FAC_ENCODING, "Interest match failed. {0} has been excluded", name);
				return false;
			}
		}
		if (null != publisherID()) {
			if (null == resultPublisherKeyID) {
				if (Log.isLoggable(Log.FAC_ENCODING, Level.FINEST))
					Log.finest(Log.FAC_ENCODING, "Interest match failed, target {0} doesn't specify a publisherID and we require a particular one.", name);
				return false; 
			}
			// Should this be more general?
			// TODO DKS handle issuer
			if (Log.isLoggable(Log.FAC_ENCODING, Level.FINEST))
				Log.finest(Log.FAC_ENCODING, "Interest match handed off to trust manager for name: {0}", name);
			return TrustManager.getTrustManager().matchesRole(publisherID(), resultPublisherKeyID);
		}
		if (Log.isLoggable(Log.FAC_ENCODING, Level.FINEST))
			Log.finest(Log.FAC_ENCODING, "Interest match succeeded to name: {0}", name);
		return true;
	}
	
	/**
	 * Return data a specified number of levels below us in the hierarchy, with
	 * order preference of leftmost.
	 * @param name name prefix for interest
	 * @param level number of levels below us we want content. Includes the ephemeral
	 * 	digest component in the count.
	 * @param publisher who should have signed content (can be null)
	 */
	public static Interest lower(ContentName name, int level, PublisherPublicKeyDigest publisher) {
		Interest interest = new Interest(name, publisher);
		interest.maxSuffixComponents(level);
		interest.minSuffixComponents(level);
		return interest;
	}
	
	/**
	 * Construct an Interest that will give you the next content after the argument
	 * name's first prefixCount components
	 * @param name
	 * @param prefixCount   may be null
	 * @param publisher 	may be null
	 * @return new Interest
	 */
	public static Interest next(ContentName name, Integer prefixCount, PublisherPublicKeyDigest publisher) {
		return next(name, null, prefixCount, null, null, publisher);
	}
	
	/**
	 * Construct an Interest that will give you the next content after the argument
	 * names's first prefixCount components excluding the components specified in the omissions
	 * @param name
	 * @param omissions 	components to exclude - may be null
	 * @param prefixCount	may be null
	 * @param publisher		may be null
	 * @return
	 */
	public static Interest next(ContentName name,Exclude exclude, Integer prefixCount, Integer maxSuffixComponents, Integer minSuffixComponents,
					PublisherPublicKeyDigest publisher) {
		return nextOrLast(name, exclude, new Integer(CHILD_SELECTOR_LEFT), prefixCount, maxSuffixComponents, minSuffixComponents, publisher);
	}
	
	/**
	 * Regardless of whether we are looking for the next or the last Content
	 * we always want to exclude everything before the first component at the 
	 * prefix level.
	 * 
	 * @param name
	 * @param exclude 	contains elements to exclude
	 * @param order		corresponds to ChildSelector values
	 * @param prefixCount	may be null
	 * @param publisher may be null
	 * @return the Interest
	 */
	private static Interest nextOrLast(ContentName name, Exclude exclude, Integer order, Integer prefixCount, Integer maxSuffixComponents,
					Integer minSuffixComponents, PublisherPublicKeyDigest publisher )  {
		if (null != prefixCount) {
			if (prefixCount > name.count())
				throw new IllegalArgumentException("Invalid prefixCount > components: " + prefixCount);
		} else
			prefixCount = name.count() - 1;
		
		if (prefixCount < name.count()) {
			byte [] component = name.component(prefixCount);
			name = name.cut(prefixCount);

			if (exclude == null) {
				exclude = Exclude.uptoFactory(component);
			} else
				exclude.excludeUpto(component);
		}
		return constructInterest(name, exclude, order, maxSuffixComponents, minSuffixComponents, publisher);
	}
	
	/**
	 * Construct an Interest that will give you the last content after the argument
	 * name's first prefixCount components
	 * @param name
	 * @param prefixCount   may be null
	 * @param publisher		may be null
	 * @return new Interest
	 */
	public static Interest last(ContentName name, Integer prefixCount, PublisherPublicKeyDigest publisher) {
		return last(name, null, prefixCount, null, null, publisher);
	}
	
	/**
	 * Construct an Interest that will give you the last content after the argument
	 * name excluding the components specified in the Exclude
	 * @param name
	 * @param exclude 	contains components to exclude - may be null
	 * @param prefixCount	may be null
	 * @param publisher 	may be null
	 * @return the Interest
	 */
	public static Interest last(ContentName name, Exclude exclude, Integer prefixCount, Integer maxSuffixComponents, Integer minSuffixComponents, 
				PublisherPublicKeyDigest publisher) {
		return nextOrLast(name, exclude, new Integer(CHILD_SELECTOR_RIGHT), prefixCount, maxSuffixComponents, minSuffixComponents, publisher);
	}
	
	/**
	 * Construct an Interest that will exclude the values in omissions and require maxSuffixComponents and
	 * minSuffixComponents as specific
	 * @param name
	 * @param omissions			components to exclude
	 * @param publisherID
	 * @param maxSuffixComponents
	 * @param minSuffixComponents
	 * @return the Interest
	 */
	public static Interest exclude(ContentName name, Exclude exclude, Integer maxSuffixComponents, Integer minSuffixComponents, PublisherPublicKeyDigest publisherID) {
		return constructInterest(name, exclude, null, maxSuffixComponents, minSuffixComponents, publisherID);
	}
	
	/**
	 * Construct an Interest with specified values set
	 * @param name
	 * @param filter 			may be null
	 * @param childSelector		may be null
	 * @param publisherID		may be null
	 * @param maxSuffixComponents	may be null
	 * @param minSuffixComponents	may be null
	 * @return the Interest
	 */
	public static Interest constructInterest(ContentName name,  Exclude filter,
			Integer childSelector, Integer maxSuffixComponents, Integer minSuffixComponents, PublisherPublicKeyDigest publisher) {
		PublisherID pubID = null;
		if (publisher!=null)
			pubID = new PublisherID(publisher);
		Interest interest = new Interest(name);
		if (null != childSelector)
			interest.childSelector(childSelector);
		if (null != filter)
			interest.exclude(filter);
		if (null != pubID)
			interest.publisherID(pubID);
		if (null != maxSuffixComponents)
			interest.maxSuffixComponents(maxSuffixComponents);
		if (null != minSuffixComponents)
			interest.minSuffixComponents(minSuffixComponents);
		return interest;
	}
	
	/**
	 * Currently used as an Interest name component to disambiguate multiple requests for the
	 * same content.
	 * 
	 * @return the nonce in component form
	 * @deprecated use {@link Component#NONCE} instead.
	 */
	@Deprecated
	public static byte[] generateNonce() {
		return NONCE.getComponent();
	}

	/**
	 * Determine if this Interest's name is a prefix of the specified name
	 * @param name
	 * @return true if our name is a prefix of the specified name
	 */
	public boolean isPrefixOf(ContentName name) {
		int count = name().count();
		if (null != maxSuffixComponents() && 0 == maxSuffixComponents()) {
			// This Interest is trying to match a complete content name with digest explicitly included
			// so we must drop the last component for the prefix test against a name that is 
			// designed to be direct from ContentObject and so does not include digest explicitly
			//count--;
		}
		return name().isPrefixOf(name, count);
	}
	
	/**
	 * Determine if this Interest's name is a prefix of the first "count" components of the input name
	 * @param name
	 * @param count
	 * @return true if our name is a prefix of the specified name's first "count" components
	 */
	public boolean isPrefixOf(ContentName name, int count) {
		return name().isPrefixOf(name, count);
	}
	
	/**
	 * Determine if this Interest's name is a prefix of the specified ContentObject's name
	 * @param other
	 * @return true if our name is a prefix of the specified ContentObject's name
	 */
	public boolean isPrefixOf(ContentObject other) {
		return name().isPrefixOf(other, name().count());
	}

	/**
	 * Thought about encoding and decoding as flat -- no wrapping
	 * declaration. But then couldn't use these solo.
	 */
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());

		_name = new ContentName();
		_name.decode(decoder);
		
		if (decoder.peekStartElement(CCNProtocolDTags.MinSuffixComponents)) {
			_minSuffixComponents = decoder.readIntegerElement(CCNProtocolDTags.MinSuffixComponents);
		}
		
		if (decoder.peekStartElement(CCNProtocolDTags.MaxSuffixComponents)) {
			_maxSuffixComponents = decoder.readIntegerElement(CCNProtocolDTags.MaxSuffixComponents);
		}
				
		if (PublisherID.peek(decoder)) {
			_publisher = new PublisherID();
			_publisher.decode(decoder);
		}

		if (decoder.peekStartElement(CCNProtocolDTags.Exclude)) {
			_exclude = new Exclude();
			_exclude.decode(decoder);
		}
		
		if (decoder.peekStartElement(CCNProtocolDTags.ChildSelector)) {
			_childSelector = decoder.readIntegerElement(CCNProtocolDTags.ChildSelector);
		}
		
		if (decoder.peekStartElement(CCNProtocolDTags.AnswerOriginKind)) {
			// call setter to handle defaulting
			_answerOriginKind = decoder.readIntegerElement(CCNProtocolDTags.AnswerOriginKind);
		}
		
		if (decoder.peekStartElement(CCNProtocolDTags.Scope)) {
			_scope = decoder.readIntegerElement(CCNProtocolDTags.Scope);
		}

		if (decoder.peekStartElement(CCNProtocolDTags.InterestLifetime)) {
			_interestLifetime = decoder.readBinaryElement(CCNProtocolDTags.InterestLifetime);
		}
		
		if (decoder.peekStartElement(CCNProtocolDTags.Nonce)) {
			_nonce = decoder.readBinaryElement(CCNProtocolDTags.Nonce);
		}
		
		decoder.readEndElement();
	}

	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(getElementLabel());
		
		name().encode(encoder);
	
		if (null != minSuffixComponents()) 
			encoder.writeElement(CCNProtocolDTags.MinSuffixComponents, minSuffixComponents());	

		if (null != maxSuffixComponents()) 
			encoder.writeElement(CCNProtocolDTags.MaxSuffixComponents, maxSuffixComponents());

		if (null != publisherID())
			publisherID().encode(encoder);
		
		if (null != exclude())
			exclude().encode(encoder);

		if (null != childSelector()) 
			encoder.writeElement(CCNProtocolDTags.ChildSelector, childSelector());

		if (DEFAULT_ANSWER_ORIGIN_KIND != answerOriginKind()) 
			encoder.writeElement(CCNProtocolDTags.AnswerOriginKind, answerOriginKind());

		if (null != scope()) 
			encoder.writeElement(CCNProtocolDTags.Scope, scope());
		
		if (null != nonce())
			encoder.writeElement(CCNProtocolDTags.Nonce, nonce());
		
		encoder.writeEndElement();   		
	}
	
	@Override
	public long getElementLabel() { return CCNProtocolDTags.Interest; }

	@Override
	public boolean validate() {
		// DKS -- do we do recursive validation?
		// null authenticator ok
		return (null != name());
	}

	public int compareTo(Interest o) {
		int result = DataUtils.compare(name(), o.name());
		if (result != 0) return result;
		
		result = DataUtils.compare(maxSuffixComponents(), o.maxSuffixComponents());
		if (result != 0) return result;
		
		result = DataUtils.compare(minSuffixComponents(), o.minSuffixComponents());
		if (result != 0) return result;
		
		result = DataUtils.compare(publisherID(), o.publisherID());
		if (result != 0) return result;
	
		result = DataUtils.compare(exclude(), o.exclude());
		if (result != 0) return result;
		
		result = DataUtils.compare(childSelector(), o.childSelector());
		if (result != 0) return result;
		
		result = DataUtils.compare(answerOriginKind(), o.answerOriginKind());
		if (result != 0) return result;
		
		result = DataUtils.compare(scope(), o.scope());
		if (result != 0) return result;

		return result;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime
			* result
			+ ((_maxSuffixComponents == null) ? 0 : _maxSuffixComponents
				.hashCode());
		result = prime
		* result
		+ ((_minSuffixComponents == null) ? 0 : _minSuffixComponents
			.hashCode());
		result = prime
				* result
				+ ((_answerOriginKind == null) ? 0 : _answerOriginKind
						.hashCode());
		result = prime * result
				+ ((_exclude == null) ? 0 : _exclude.hashCode());
		result = prime * result + ((_name == null) ? 0 : _name.hashCode());
		result = prime
				* result
				+ ((_childSelector == null) ? 0 : _childSelector.hashCode());
		result = prime * result
				+ ((_publisher == null) ? 0 : _publisher.hashCode());
		result = prime * result + ((_scope == null) ? 0 : _scope.hashCode());
		result = prime * result + Arrays.hashCode(_interestLifetime);
		result = prime * result + Arrays.hashCode(_nonce);
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
		Interest other = (Interest) obj;
		if (_maxSuffixComponents == null) {
			if (other._maxSuffixComponents != null)
				return false;
		} else if (!_maxSuffixComponents.equals(other._maxSuffixComponents))
			return false;
		if (_minSuffixComponents == null) {
			if (other._minSuffixComponents != null)
				return false;
		} else if (!_minSuffixComponents.equals(other._minSuffixComponents))
			return false;
		if (_answerOriginKind == null) {
			if (other._answerOriginKind != null)
				return false;
		} else if (!_answerOriginKind.equals(other._answerOriginKind))
			return false;
		if (_exclude == null) {
			if (other._exclude != null)
				return false;
		} else if (!_exclude.equals(other._exclude))
			return false;
		if (_name == null) {
			if (other._name != null)
				return false;
		} else if (!_name.equals(other._name))
			return false;
		if (_childSelector == null) {
			if (other._childSelector != null)
				return false;
		} else if (!_childSelector.equals(other._childSelector))
			return false;
		if (_publisher == null) {
			if (other._publisher != null)
				return false;
		} else if (!_publisher.equals(other._publisher))
			return false;
		if (_scope == null) {
			if (other._scope != null)
				return false;
		} else if (!_scope.equals(other._scope))
			return false;
		return true;
	}
	
	public String toString() {
		StringBuffer sb = new StringBuffer(_name.toString());
		sb.append(": ");
	
		if  (null != _maxSuffixComponents)
			sb.append(" maxsc:" + _maxSuffixComponents);
		
		if  (null != _minSuffixComponents)
			sb.append(" minsc:" + _minSuffixComponents);

		if (null != _publisher)
			sb.append(" p:" + _publisher);

		if (null != _exclude)
			sb.append(" ex("+_exclude+")");
		return sb.toString();
	}
	
	public Interest clone() {
		Interest clone = new Interest(name());
		if (null != _maxSuffixComponents)
			clone.maxSuffixComponents(maxSuffixComponents());
		if (null != _minSuffixComponents)
			clone.minSuffixComponents(minSuffixComponents());
		if (null != _publisher)
			clone.publisherID(publisherID());
		if (null != _exclude)
			clone.exclude(exclude());
		if (null != _childSelector)
			clone.childSelector(childSelector());
		if (null != _answerOriginKind)
			clone.answerOriginKind(answerOriginKind());
		if (null != _interestLifetime)
			clone.interestLifetime(interestLifetime());
		if (null != _scope)
			clone.scope(scope());
		return clone;
	}

	@SuppressWarnings("serial")
	public static class NoResponseException extends IOException {
		protected Interest interest;
		public NoResponseException(Interest i) {
			super(i.toString());
			interest = i;
		}
		public Interest getInterest() {
			return interest;
		}
	}

	public ContentName getContentName() {
		return _name;
	}
}
