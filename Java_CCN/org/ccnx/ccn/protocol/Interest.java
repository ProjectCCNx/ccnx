package org.ccnx.ccn.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.Library;

import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;
import com.parc.ccn.library.profiles.CommandMarkers;
import com.parc.ccn.security.keys.TrustManager;

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
 *
 * @author smetters, rasmusse
 *
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
	
	// OrderPreference values.  These are bitmapped
	public static final int CHILD_SELECTOR_LEFT = 0;		// bit 0
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
	protected ExcludeFilter _excludeFilter;
	protected Integer _childSelector;
	protected Integer _answerOriginKind;
	protected Integer _scope;
	protected byte[] _nonce;

	/**
	 * TODO: DKS figure out how to handle encoding faster,
	 * and how to handle shorter version of names without
	 * copying, particularly without 1.6 array ops.
	 * @param name
	 * @param publisher
	 */
	public Interest(ContentName name, 
			   PublisherID publisher) {
		_name = name;
		_publisher = publisher;
	}
	
	public Interest(ContentName name, PublisherPublicKeyDigest publisher) {
		this(name, (null != publisher) ? new PublisherID(publisher) : (PublisherID)null);
	}
	
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
	
	public ExcludeFilter excludeFilter() { return _excludeFilter; }
	public void excludeFilter(ExcludeFilter excludeFilter) { _excludeFilter = excludeFilter; }
	
	public Integer childSelector() { return _childSelector;}
	public void childSelector(int childSelector) { _childSelector = childSelector; }
	
	public Integer answerOriginKind() { return _answerOriginKind; }
	public void answerOriginKind(int answerOriginKind) { _answerOriginKind = answerOriginKind; }
	
	public Integer scope() { return _scope; }
	public void scope(int scope) { _scope = scope; }
	
	public byte[] nonce() { return _nonce; }
	public void nonce(byte[] nonce) { _nonce = nonce; }

	public boolean matches(ContentObject result) {
		return matches(result, (null != result.signedInfo()) ? result.signedInfo().getPublisherKeyID() : null);
	}

	/**
	 * Determine whether a piece of content's name (without digest component) matches this interest.
	 * 
	 * This doesn't match if we specify the digest in the interest.
	 *
	 * @param name - Name of a content object without a digest component
	 * @param resultPublisherKeyID
	 * @return
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
	 * Determine whether a piece of content matches this interest.
	 * 
	 * @param co - ContentObject
	 * @param resultPublisherKeyID
	 * @return
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
				Library.logger().fine("Interest match failed: " + lengthDiff + " more than the " + maxSuffixComponents() + " components between expected " +
						name() + " and tested " + name);
				return false;
			}
			if (null != minSuffixComponents() && lengthDiff < minSuffixComponents()) {
				Library.logger().fine("Interest match failed: " + lengthDiff + " less than the " + minSuffixComponents() + " components between expected " +
						name() + " and tested " + name);
				return false;
			}
		}
		if (null != excludeFilter()) {
			if (excludeFilter().match(name.component(name().count()))) {
				Library.logger().finest("Interest match failed. " + name + " has been excluded");
				return false;
			}
		}
		if (null != publisherID()) {
			if (null == resultPublisherKeyID) {
				Library.logger().finest("Interest match failed, target " + name + " doesn't specify a publisherID and we require a particular one.");
				return false; 
			}
			// Should this be more general?
			// TODO DKS handle issuer
			Library.logger().finest("Interest match handed off to trust manager for name: " + name);
			return TrustManager.getTrustManager().matchesRole(publisherID(), resultPublisherKeyID);
		} 
		Library.logger().finest("Interest match succeeded to name: " + name);
		return true;
	}
	
	/**
	 * Construct an interest that will give you the next content after the
	 * argument name
	 */
	public static Interest next(ContentName name) {
		return next(name, (byte[][])null, null);
	}
	
	public static Interest next(ContentName name, int prefixCount) {
		return next(name, (byte[][])null, prefixCount);
	}
	
	public static Interest next(ContentName name, byte[][] omissions, Integer prefixCount) {
		return nextOrLast(name, ExcludeFilter.factory(omissions), new Integer(CHILD_SELECTOR_LEFT), prefixCount);
	}
	
	/**
	 * Regardless of whether we are looking for the next or the last Content
	 * we always want to exclude everything before the first component at the 
	 * prefix level.
	 * 
	 * @param name
	 * @param exclude
	 * @param order
	 * @param prefixCount
	 * @return
	 */
	private static Interest nextOrLast(ContentName name, ExcludeFilter exclude, Integer order, Integer prefixCount)  {
		if (null != prefixCount) {
			if (prefixCount > name.count())
				throw new IllegalArgumentException("Invalid prefixCount > components: " + prefixCount);
		} else
			prefixCount = name.count() - 1;
		
		if (prefixCount < name.count()) {
			byte [] component = name.component(prefixCount);
			name = new ContentName(prefixCount, name.components());
		
			if (exclude == null) {
				exclude = ExcludeFilter.uptoFactory(component);
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
	
	public static Interest last(ContentName name, Integer prefixCount) {
		return last(name, (byte[][])null, prefixCount);
	}
	
	public static Interest last(ContentName name, byte[] [] omissions, Integer prefixCount) {
		return nextOrLast(name, ExcludeFilter.factory(omissions), new Integer(CHILD_SELECTOR_RIGHT), prefixCount);
	}
	
	public static Interest last(ContentName name, ExcludeFilter exclude) {
		return nextOrLast(name, exclude, new Integer(CHILD_SELECTOR_RIGHT), null);
	}
	
	public static Interest last(ContentName name, ExcludeFilter exclude, Integer prefixCount) {
		return nextOrLast(name, exclude, new Integer(CHILD_SELECTOR_RIGHT), prefixCount);
	}
	
	/**
	 * Construct an interest to exclude the objects in the filter
	 * @param co
	 * @param exclude
	 * @return
	 */
	public static Interest exclude(ContentName name, byte[][] omissions) {
		return constructInterest(name, null == omissions ? null : new ExcludeFilter(omissions), null);
	}
	
	public static Interest exclude(ContentName name, byte[][] omissions, PublisherID publisherID, Integer maxSuffixComponents, Integer minSuffixComponents) {
		return constructInterest(name, null == omissions ? null : new ExcludeFilter(omissions), null, publisherID, maxSuffixComponents, minSuffixComponents);
	}
	
	
	/**
	 * Construct an interest that will give you the next content after the
	 * argument ContentObject
	 */
	public static Interest next(ContentObject co, Integer prefixCount) {
		ArrayList<byte []>components = byteArrayClone(co.name().components());
		components.add(co.contentDigest());
		ContentName nextName = new ContentName(components.size(), components);
		return next(nextName, prefixCount == null ? components.size() - 2 : prefixCount);
	}
	
	public static Interest next(ContentObject co) {
		return next(co, null);
	}

	public static Interest constructInterest(ContentName name,  ExcludeFilter filter,
			Integer orderPreference) {
		return constructInterest(name, filter, orderPreference, null, null, null);
	}
	
	public static Interest constructInterest(ContentName name,  ExcludeFilter filter,
			Integer childSelector, PublisherID publisherID, Integer maxSuffixComponents, Integer minSuffixComponents) {
		Interest interest = new Interest(name);
		if (null != childSelector)
			interest.childSelector(childSelector);
		if (null != filter)
			interest.excludeFilter(filter);
		if (null != publisherID)
			interest.publisherID(publisherID);
		if (null != maxSuffixComponents)
			interest.maxSuffixComponents(maxSuffixComponents);
		if (null != minSuffixComponents)
			interest.minSuffixComponents(minSuffixComponents);
		return interest;
	}
	
	/**
	 * Currently used as an interest name component to disambiguate multiple requests for the
	 * same content.
	 * 
	 * @return
	 */
	public static byte[] generateNonce() {
		byte [] nonce = new byte[8];
		_random.nextBytes(nonce);
		byte [] wholeNonce = new byte[CommandMarkers.NONCE_MARKER.length + nonce.length];
		System.arraycopy(CommandMarkers.NONCE_MARKER, 0, wholeNonce, 0, CommandMarkers.NONCE_MARKER.length);
		System.arraycopy(nonce, 0, wholeNonce, CommandMarkers.NONCE_MARKER.length, nonce.length);	
		return wholeNonce;
	}

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
	
	public boolean isPrefixOf(ContentName name, int count) {
		return name().isPrefixOf(name, count);
	}
	
	public boolean isPrefixOf(ContentObject other) {
		return name().isPrefixOf(other, name().count());
	}
	
	public boolean recursive() { return true; }
	
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
		decoder.readStartElement(INTEREST_ELEMENT);

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

		if (decoder.peekStartElement(ExcludeFilter.EXCLUDE_ELEMENT)) {
			_excludeFilter = new ExcludeFilter();
			_excludeFilter.decode(decoder);
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
			Library.logger().info("Catching exception reading interest end element, and moving on. Waiting for schema updates...");
		}
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(INTEREST_ELEMENT);
		
		name().encode(encoder);
	
		if (null != minSuffixComponents()) 
			encoder.writeIntegerElement(MIN_SUFFIX_COMPONENTS, minSuffixComponents());	

		if (null != maxSuffixComponents()) 
			encoder.writeIntegerElement(MAX_SUFFIX_COMPONENTS, maxSuffixComponents());

		if (null != publisherID())
			publisherID().encode(encoder);
		
		if (null != excludeFilter())
			excludeFilter().encode(encoder);

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
	
		result = DataUtils.compare(excludeFilter(), o.excludeFilter());
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
				+ ((_excludeFilter == null) ? 0 : _excludeFilter.hashCode());
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
		if (_excludeFilter == null) {
			if (other._excludeFilter != null)
				return false;
		} else if (!_excludeFilter.equals(other._excludeFilter))
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

		if (null != _excludeFilter)
			sb.append(" ex("+_excludeFilter+")");
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
		if (null != _excludeFilter)
			clone.excludeFilter(excludeFilter());
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
