/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.TrustManager;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.CommandMarkers;


/**
 * This class represents all the allowed specializations
 * of queries recognized and supported (in a best-effort
 * fashion) at the CCN level.
 * 
 * Implement Comparable to make it much easier to store in
 * a Set and avoid duplicates.
 * 
 * xs:complexType name="InterestType">
 * <xs:sequence>
 *   <xs:element name="Name" type="NameType"/>
 *   <xs:element name="MinSuffixComponents" type="xs:nonNegativeInteger"
 *                       minOccurs="0" maxOccurs="1"/>
 *   <xs:element name="MaxSuffixComponents" type="xs:nonNegativeInteger"
 *                       minOccurs="0" maxOccurs="1"/>
 *   <xs:choice minOccurs="0" maxOccurs="1">
 *       <xs:element name="PublisherPublicKeyDigest" type="DigestType"/>
 *       <xs:element name="PublisherCertificateDigest" type="DigestType"/>
 *       <xs:element name="PublisherIssuerKeyDigest" type="DigestType"/>
 *       <xs:element name="PublisherIssuerCertificateDigest" type="DigestType"/>
 *   </xs:choice>
 *   <xs:element name="Exclude" type="ExcludeType"
 *                       minOccurs="0" maxOccurs="1"/>
 *   <xs:element name="ChildSelector" type="xs:nonNegativeInteger"
 *                       minOccurs="0" maxOccurs="1"/>
 *   <xs:element name="AnswerOriginKind" type="xs:nonNegativeInteger"
 *                       minOccurs="0" maxOccurs="1"/>
 *   <xs:element name="Scope" type="xs:nonNegativeInteger"
 *			minOccurs="0" maxOccurs="1"/>
 *   <xs:element name="Nonce" type="Base64BinaryType"
 *			minOccurs="0" maxOccurs="1"/>
 * </xs:sequence>
 * </xs:complexType>
 */
public class Interest extends GenericXMLEncodable implements XMLEncodable, Comparable<Interest>, Cloneable {
	
	// Used to remove spurious *'s
	public static final String RECURSIVE_POSTFIX = "*";
	
	public static final String INTEREST_ELEMENT = "Interest";
	public static final String MAX_SUFFIX_COMPONENTS = "MaxSuffixComponents";
	public static final String MIN_SUFFIX_COMPONENTS = "MinSuffixComponents";
	public static final String CHILD_SELECTOR = "ChildSelector";
	public static final String ANSWER_ORIGIN_KIND = "AnswerOriginKind";
	public static final String SCOPE_ELEMENT = "Scope";
	public static final String NONCE_ELEMENT = "Nonce";
	
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

	/**
	 * For nonce generation
	 */
	protected static Random _random = new Random();
	
	protected ContentName _name;
	protected Integer _maxSuffixComponents;
	protected Integer _minSuffixComponents;
	// DKS TODO can we really support a PublisherID here, or just a PublisherPublicKeyDigest?
	protected PublisherID _publisher;
	protected Exclude _exclude;
	protected Integer _childSelector;
	protected Integer _answerOriginKind;
	protected Integer _scope;
	protected byte[] _nonce;

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
	
	public Integer answerOriginKind() { return _answerOriginKind; }
	public void answerOriginKind(int answerOriginKind) { _answerOriginKind = answerOriginKind; }
	
	public Integer scope() { return _scope; }
	public void scope(int scope) { _scope = scope; }
	
	public byte[] nonce() { return _nonce; }
	public void nonce(byte[] nonce) { _nonce = nonce; }

	/**
	 * Determine whether a piece of content matches the Interest
	 * @param test
	 * @return true if the test co matches the Interest
	 */
	public boolean matches(ContentObject test) {
		return matches(test, (null != test.signedInfo()) ? test.signedInfo().getPublisherKeyID() : null);
	}

	/**
	 * Determine whether a piece of content's name (without digest component) matches this Interest.
	 * 
	 * This doesn't match if we specify the digest in the Interest.
	 *
	 * @param name - Name of a content object without a digest component
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
				Log.fine("Interest match failed: " + lengthDiff + " more than the " + maxSuffixComponents() + " components between expected " +
						name() + " and tested " + name);
				return false;
			}
			if (null != minSuffixComponents() && lengthDiff < minSuffixComponents()) {
				Log.fine("Interest match failed: " + lengthDiff + " less than the " + minSuffixComponents() + " components between expected " +
						name() + " and tested " + name);
				return false;
			}
		}
		if (null != exclude()) {
			if (exclude().match(name.component(name().count()))) {
				Log.finest("Interest match failed. " + name + " has been excluded");
				return false;
			}
		}
		if (null != publisherID()) {
			if (null == resultPublisherKeyID) {
				Log.finest("Interest match failed, target " + name + " doesn't specify a publisherID and we require a particular one.");
				return false; 
			}
			// Should this be more general?
			// TODO DKS handle issuer
			Log.finest("Interest match handed off to trust manager for name: " + name);
			return TrustManager.getTrustManager().matchesRole(publisherID(), resultPublisherKeyID);
		} 
		Log.finest("Interest match succeeded to name: " + name);
		return true;
	}
	
	/**
	 * Construct an Interest that will give you the next content after the
	 * argument name
	 * @param name
	 * @return new Interest
	 */
	public static Interest next(ContentName name) {
		return next(name, (byte[][])null, null);
	}
	
	/**
	 * Construct an Interest that will give you the next content after the argument
	 * name's first prefixCount components
	 * @param name
	 * @param prefixCount  may be null
	 * @return new Interest
	 */
	public static Interest next(ContentName name, int prefixCount) {
		return next(name, (byte[][])null, prefixCount);
	}
	
	/**
	 * Construct an Interest that will give you the next content after the argument
	 * names's first prefixCount components excluding the components specified in the omissions
	 * @param name
	 * @param omissions 	components to exclude - may be null
	 * @param prefixCount	may be null
	 * @return
	 */
	public static Interest next(ContentName name, byte[][] omissions, Integer prefixCount) {
		return nextOrLast(name, Exclude.factory(omissions), new Integer(CHILD_SELECTOR_LEFT), prefixCount);
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
	 * @return the Interest
	 */
	private static Interest nextOrLast(ContentName name, Exclude exclude, Integer order, Integer prefixCount)  {
		if (null != prefixCount) {
			if (prefixCount > name.count())
				throw new IllegalArgumentException("Invalid prefixCount > components: " + prefixCount);
		} else
			prefixCount = name.count() - 1;
		
		if (prefixCount < name.count()) {
			byte [] component = name.component(prefixCount);
			name = new ContentName(prefixCount, name.components());
		
			if (exclude == null) {
				exclude = Exclude.uptoFactory(component);
			} else
				exclude.excludeUpto(component);
		}
		return constructInterest(name, exclude, order);
	}
	
	/**
	 * Construct an Interest that will give you the last content after the argument name
	 * @param name
	 * @return
	 */
	public static Interest last(ContentName name) {
		return last(name, (byte[][])null, null);
	}
	
	/**
	 * Construct an Interest that will give you the last content after the argument
	 * name's first prefixCount components
	 * @param name
	 * @param prefixCount  may be null
	 * @return new Interest
	 */
	public static Interest last(ContentName name, Integer prefixCount) {
		return last(name, (byte[][])null, prefixCount);
	}
	
	/**
	 * Construct an Interest that will give you the last content after the argument
	 * names's first prefixCount components excluding the components specified in the omissions
	 * @param name
	 * @param omissions 	components to exclude - may be null
	 * @param prefixCount	may be null
	 * @return
	 */
	public static Interest last(ContentName name, byte omissions[][], Integer prefixCount) {
		return nextOrLast(name, Exclude.factory(omissions), new Integer(CHILD_SELECTOR_RIGHT), prefixCount);
	}
	
	/**
	 * Construct an Interest that will give you the last content after the argument
	 * name excluding the components specified in the Exclude
	 * @param name
	 * @param exclude contains components to exclude - may be null
	 * @return the Interest
	 */
	public static Interest last(ContentName name, Exclude exclude) {
		return nextOrLast(name, exclude, new Integer(CHILD_SELECTOR_RIGHT), null);
	}
	
	/**
	 * Construct an Interest that will give you the last content after the argument
	 * name excluding the components specified in the Exclude
	 * @param name
	 * @param exclude 	contains components to exclude - may be null
	 * @param prefixCount	may be null
	 * @return the Interest
	 */
	public static Interest last(ContentName name, Exclude exclude, Integer prefixCount) {
		return nextOrLast(name, exclude, new Integer(CHILD_SELECTOR_RIGHT), prefixCount);
	}
	
	/**
	 * Construct an Interest to exclude the objects in the filter
	 * @param name
	 * @param omissions components to exclude
	 * @return the Interest
	 */
	public static Interest exclude(ContentName name, byte omissions[][]) {
		return constructInterest(name, null == omissions ? null : new Exclude(omissions), null);
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
	public static Interest exclude(ContentName name, byte omissions[][], PublisherID publisherID, Integer maxSuffixComponents, Integer minSuffixComponents) {
		return constructInterest(name, null == omissions ? null : new Exclude(omissions), null, publisherID, maxSuffixComponents, minSuffixComponents);
	}
	
	/**
	 * Construct an Interest that will give you the next content after the
	 * argument ContentObject
	 *
	 * @param co
	 * @param prefixCount
	 * @return
	 */
	public static Interest next(ContentObject co, Integer prefixCount) {
		ArrayList<byte []>components = byteArrayClone(co.name().components());
		components.add(co.contentDigest());
		ContentName nextName = new ContentName(components.size(), components);
		return next(nextName, prefixCount == null ? components.size() - 2 : prefixCount);
	}
	
	/**
	 * Construct an Interest that will give you the next content after the
	 * argument ContentObject
	 *
	 * @param co
	 * @return the Interest
	 */
	public static Interest next(ContentObject co) {
		return next(co, null);
	}

	/**
	 * Construct an Interest with specified values set
	 * @param name
	 * @param filter 			may be null
	 * @param orderPreference 	may be null
	 * @return the Interest
	 */
	public static Interest constructInterest(ContentName name,  Exclude filter,
			Integer orderPreference) {
		return constructInterest(name, filter, orderPreference, null, null, null);
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
			Integer childSelector, PublisherID publisherID, Integer maxSuffixComponents, Integer minSuffixComponents) {
		Interest interest = new Interest(name);
		if (null != childSelector)
			interest.childSelector(childSelector);
		if (null != filter)
			interest.exclude(filter);
		if (null != publisherID)
			interest.publisherID(publisherID);
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
	 */
	public static byte[] generateNonce() {
		byte [] nonce = new byte[8];
		_random.nextBytes(nonce);
		byte [] wholeNonce = new byte[CommandMarkers.COMMAND_MARKER_NONCE.length + nonce.length];
		System.arraycopy(CommandMarkers.COMMAND_MARKER_NONCE, 0, wholeNonce, 0, CommandMarkers.COMMAND_MARKER_NONCE.length);
		System.arraycopy(nonce, 0, wholeNonce, CommandMarkers.COMMAND_MARKER_NONCE.length, nonce.length);	
		return wholeNonce;
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
	 * Deteremine if this Interest's name is a prefix of the specified ContentObject's name
	 * @param other
	 * @return true if our name is a prefix of the specified ContentObject's name
	 */
	public boolean isPrefixOf(ContentObject other) {
		return name().isPrefixOf(other, name().count());
	}
		
	private static ArrayList<byte[]> byteArrayClone(ArrayList<byte[]> input) {
		ArrayList<byte[]> al = new ArrayList<byte[]>();
		for (int i = 0; i < input.size(); i++) {
			byte[] value = new byte[input.get(i).length];
			System.arraycopy(input.get(i), 0, value, 0, input.get(i).length);
			al.add(value);
		}
		return al;
	}
	
	/**
	 * Thought about encoding and decoding as flat -- no wrapping
	 * declaration. But then couldn't use these solo.
	 */
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(getElementLabel());

		_name = new ContentName();
		_name.decode(decoder);
		
		if (decoder.peekStartElement(MIN_SUFFIX_COMPONENTS)) {
			_minSuffixComponents = decoder.readIntegerElement(MIN_SUFFIX_COMPONENTS);
		}
		
		if (decoder.peekStartElement(MAX_SUFFIX_COMPONENTS)) {
			_maxSuffixComponents = decoder.readIntegerElement(MAX_SUFFIX_COMPONENTS);
		}
				
		if (PublisherID.peek(decoder)) {
			_publisher = new PublisherID();
			_publisher.decode(decoder);
		}

		if (decoder.peekStartElement(Exclude.EXCLUDE_ELEMENT)) {
			_exclude = new Exclude();
			_exclude.decode(decoder);
		}
		
		if (decoder.peekStartElement(CHILD_SELECTOR)) {
			_childSelector = decoder.readIntegerElement(CHILD_SELECTOR);
		}
		
		if (decoder.peekStartElement(ANSWER_ORIGIN_KIND)) {
			_answerOriginKind = decoder.readIntegerElement(ANSWER_ORIGIN_KIND);
		}
		
		if (decoder.peekStartElement(SCOPE_ELEMENT)) {
			_scope = decoder.readIntegerElement(SCOPE_ELEMENT);
		}
		
		if (decoder.peekStartElement(NONCE_ELEMENT)) {
			_nonce = decoder.readBinaryElement(NONCE_ELEMENT);
		}
		
		try {
			decoder.readEndElement();
		} catch (XMLStreamException e) {
			// DKS TODO -- get Michael to update schema!
			Log.info("Catching exception reading Interest end element, and moving on. Waiting for schema updates...");
		}
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(getElementLabel());
		
		name().encode(encoder);
	
		if (null != minSuffixComponents()) 
			encoder.writeIntegerElement(MIN_SUFFIX_COMPONENTS, minSuffixComponents());	

		if (null != maxSuffixComponents()) 
			encoder.writeIntegerElement(MAX_SUFFIX_COMPONENTS, maxSuffixComponents());

		if (null != publisherID())
			publisherID().encode(encoder);
		
		if (null != exclude())
			exclude().encode(encoder);

		if (null != childSelector()) 
			encoder.writeIntegerElement(CHILD_SELECTOR, childSelector());

		if (null != answerOriginKind()) 
			encoder.writeIntegerElement(ANSWER_ORIGIN_KIND, answerOriginKind());

		if (null != scope()) 
			encoder.writeIntegerElement(SCOPE_ELEMENT, scope());
		
		if (null != nonce())
			encoder.writeElement(NONCE_ELEMENT, nonce());
		
		encoder.writeEndElement();   		
	}
	
	@Override
	public String getElementLabel() { return INTEREST_ELEMENT; }

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
		
		result = DataUtils.compare(nonce(), o.nonce());
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
		//if (!Arrays.equals(_nonce, other._nonce))
		//	return false;
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
			sb.append(" p:" + DataUtils.printHexBytes(_publisher.id()) + "");

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
		if (null != _scope)
			clone.scope(scope());
		if (null != _nonce)
			clone.nonce(nonce());
		return clone;
	}

}
