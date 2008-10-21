package com.parc.ccn.data.query;

import java.util.ArrayList;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;
import com.parc.ccn.security.keys.TrustManager;

/**
 * This class represents all the allowed specializations
 * of queries recognized and supported (in a best-effort
 * fashion) at the CCN level.
 * 
 * Implement Comparable to make it much easier to store in
 * a Set and avoid duplicates.
 * 
 * <xs:complexType name="InterestType">
 *  <xs:sequence>
 *   <xs:element name="Name" type="NameType"/>
 *   <xs:element name="NameComponentCount" type="xs:nonNegativeInteger"
 *                       minOccurs="0" maxOccurs="1"/>
 *   <xs:element name="PublisherID" type="PublisherIDType"
 *			minOccurs="0" maxOccurs="1"/>
 *    <xs:element name="Exclude" type="ExcludeType"
 *                       minOccurs="0" maxOccurs="1"/>
 *   <xs:element name="OrderPreference" type="xs:nonNegativeInteger"
 *                       minOccurs="0" maxOccurs="1"/>
 *   <xs:element name="AnswerOriginKind" type="xs:nonNegativeInteger"
 *                       minOccurs="0" maxOccurs="1"/>
 *   <xs:element name="Scope" type="xs:nonNegativeInteger"
 *			minOccurs="0" maxOccurs="1"/>
 *   <xs:element name="Count" type="xs:nonNegativeInteger"                                               
 *          minOccurs="0" maxOccurs="1"/>
 *   <xs:element name="Nonce" type="Base64BinaryType"
 *			minOccurs="0" maxOccurs="1"/>
 *   <xs:element name="ExperimentalResponseFilter" type="Base64BinaryType"
 *			minOccurs="0" maxOccurs="1"/>
 * </xs:sequence>
 * </xs:complexType>
 *
 * @author smetters
 *
 */
public class Interest extends GenericXMLEncodable implements XMLEncodable, Comparable<Interest> {
	
	// Used to remove spurious *'s
	public static final String RECURSIVE_POSTFIX = "*";
	
	public static final String INTEREST_ELEMENT = "Interest";
	public static final String ADDITIONAL_NAME_COMPONENTS = "AdditionalNameComponents";
	public static final String NAME_COMPONENT_COUNT = "NameComponentCount";
	public static final String ORDER_PREFERENCE = "OrderPreference";
	public static final String ANSWER_ORIGIN_KIND = "AnswerOriginKind";
	public static final String SCOPE_ELEMENT = "Scope";
	public static final String COUNT_ELEMENT = "Count";
	public static final String NONCE_ELEMENT = "Nonce";
	public static final String RESPONSE_FILTER_ELEMENT = "ExperimentalResponseFilter";
	
	// OrderPreference values.  These are bitmapped
	public static final int ORDER_PREFERENCE_LEFT = 0;		// bit 0
	public static final int ORDER_PREFERENCE_RIGHT = 1;
	public static final int ORDER_PREFERENCE_ORDER_ARRIVAL = 2;
	public static final int	ORDER_PREFERENCE_ORDER_NAME = 4;	// User name space hierarchy for ordering
	
	/**
	 * AnswerOriginKind values
	 * These are bitmapped.  Default is 3. 2 is not allowed
	 */
	public static final int ANSWER_CONTENT_STORE = 1;
	public static final int ANSWER_GENERATED = 2;
	public static final int ANSWER_STALE = 4;		// Stale answer OK
	public static final int MARK_STALE = 16;		// Must have Scope 0.  Michael calls this a "hack"

	protected ContentName _name;
	protected ContentName _prefixName;
	protected Integer _nameComponentCount;
	protected Integer _additionalNameComponents;
	// DKS TODO can we really support a PublisherID here, or just a PublisherKeyID?
	protected PublisherID _publisher;
	protected ExcludeFilter _excludeFilter;
	protected Integer _orderPreference;
	protected Integer _answerOriginKind;
	protected Integer _scope;
	protected Integer _count;
	protected byte [] _nonce;
	protected byte [] _responseFilter;
	
	/**
	 * TODO: DKS figure out how to handle encoding faster,
	 * and how to handle shorter version of names without
	 * copying, particularly without 1.6 array ops.
	 * @param name
	 * @param authenticator
	 */
	public Interest(ContentName name, 
			   PublisherID publisher) {
		_name = name;
		_publisher = publisher;
		_nameComponentCount = _name.prefixCount();
	}
	
	public Interest(ContentName name, int additionalNameComponents,
			   PublisherID publisher) {
		this(name, publisher);
		_additionalNameComponents = additionalNameComponents;
	}
	
	public Interest(ContentName name) {
		this(name, null);
	}
	
	public Interest(String name) throws MalformedContentNameStringException {
		this(ContentName.fromURI(name), null);
	}

	public Interest() {} // for use by decoders

	public ContentName name() { return _name; }
	public void name(ContentName name) { _name = name; }
	
	public Integer nameComponentCount() { return _nameComponentCount;}
	public void nameComponentCount(int nameComponentCount) { _nameComponentCount = nameComponentCount; }
	
	public Integer additionalNameComponents() { return _additionalNameComponents;}
	public void additionalNameComponents(int additionalNameComponents) { _additionalNameComponents = additionalNameComponents; }

	public PublisherID publisherID() { return _publisher; }
	public void publisherID(PublisherID publisherID) { _publisher = publisherID; }
	
	public ExcludeFilter excludeFilter() { return _excludeFilter; }
	public void excludeFilter(ExcludeFilter excludeFilter) { _excludeFilter = excludeFilter; }
	
	public Integer orderPreference() { return _orderPreference;}
	public void orderPreference(int orderPreference) { _orderPreference = orderPreference; }
	
	public Integer answerOriginKind() { return _answerOriginKind; }
	public void answerOriginKind(int answerOriginKind) { _answerOriginKind = answerOriginKind; }
	
	public Integer scope() { return _scope; }
	public void scope(int scope) { _scope = scope; }

	public Integer count() { return _count; }
	public void count(int count) { _count = count; }

	public byte [] nonce() { return _nonce; }
	public void nonce(byte [] nonce) { _nonce = nonce; }
	
	public byte [] responseFilter() { return _responseFilter; }
	public void responseFilter(byte [] responseFilter) { _responseFilter = responseFilter; }
	
	public boolean matches(CompleteName result) {
		return matches(result.name(), (null != result.authenticator()) ? result.authenticator().publisherKeyID() : null);
	}
	
	public boolean matches(ContentObject result) {
		return matches(result.name(), (null != result.authenticator()) ? result.authenticator().publisherKeyID() : null);
	}

	/**
	 * Determine whether a piece of content matches this interest. We need
	 * to beef this up to deal with the more complex interest specs.
	 * 
	 * @param resultName
	 * @param resultPublisherKeyID
	 * @return
	 */
	public boolean matches(ContentName resultName, PublisherKeyID resultPublisherKeyID) {
		if (null == name() || null == resultName)
			return false; // null name() should not happen, null arg can
		// to get interest that matches everything, should
		// use / (ROOT)
		if (name().isPrefixOf(resultName)) {
			if (null != additionalNameComponents()) {
				// we know our specified name is a prefix of the result. 
				// the number of additional components must be this value
				// we add 1 here for the missing "virtual digest" component
				// effectively on the end of resultName
				// TODO DKS this doesn't match if we specify the digest in the interest
				// though lengths will be handled properly (lengthDiff will come out 0,
				// which will match additionalNameComponents(); except we won't
				// compare to the content digest...)
				int lengthDiff = resultName.count() - name().count() + 1;
				if (!additionalNameComponents().equals(lengthDiff)) {
					Library.logger().info("Interest match failed: " + lengthDiff + " more than the " + additionalNameComponents() + " components between expected " +
							name() + " and tested " + resultName);
					return false;
				}
			}
			if (null != orderPreference()) {
				/*
				 * All we can check here is whether the test name is
				 * > our name. Any set of orderPreference requires this
				 */
				if (resultName.compareTo(name()) <= 0)
					return false;
			}
			if (null != publisherID()) {
				if (null == resultPublisherKeyID) {
					return false;
				}
				// Should this be more general?
				// TODO DKS handle issuer
				if (TrustManager.getTrustManager().matchesRole(publisherID(), resultPublisherKeyID)) {
					return true;
				}
			} else {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Construct an interest that will give you the next content after the
	 * argument ContentObject
	 * @param co
	 * @return
	 */
	public static Interest next(ContentObject co, Integer prefixCount) {
		ArrayList<byte []>components = co.name().components();
		components.add(co.contentDigest());
		ContentName nextName = new ContentName(components.size(), components, 
				prefixCount == null ? components.size() - 2 : prefixCount);
		Interest interest = new Interest(nextName);
		interest.orderPreference(ORDER_PREFERENCE_LEFT | ORDER_PREFERENCE_ORDER_NAME);
		return interest;
	}
	
	public boolean recursive() { return true; }
	
	/**
	 * Thought about encoding and decoding as flat -- no wrapping
	 * declaration. But then couldn't use these solo.
	 */
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(INTEREST_ELEMENT);

		_name = new ContentName();
		_name.decode(decoder);
		
		if (decoder.peekStartElement(NAME_COMPONENT_COUNT)) {
			_nameComponentCount = decoder.readIntegerElement(NAME_COMPONENT_COUNT);
		}
		
		if (decoder.peekStartElement(ADDITIONAL_NAME_COMPONENTS)) {
			_additionalNameComponents = decoder.readIntegerElement(ADDITIONAL_NAME_COMPONENTS);
		}
				
		if (decoder.peekStartElement(PublisherID.PUBLISHER_ID_ELEMENT)) {
			_publisher = new PublisherID();
			_publisher.decode(decoder);
		}

		if (decoder.peekStartElement(ExcludeFilter.EXCLUDE_FILTER_ELEMENT)) {
			_excludeFilter = new ExcludeFilter();
			_excludeFilter.decode(decoder);
		}
		
		if (decoder.peekStartElement(ORDER_PREFERENCE)) {
			_orderPreference = decoder.readIntegerElement(ORDER_PREFERENCE);
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
		
		if (decoder.peekStartElement(RESPONSE_FILTER_ELEMENT)) {
			Library.logger().info("Got response filter element.");
			_responseFilter = decoder.readBinaryElement(RESPONSE_FILTER_ELEMENT);
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
		
		if (null != nameComponentCount()) 
			encoder.writeIntegerElement(NAME_COMPONENT_COUNT, nameComponentCount());

		if (null != additionalNameComponents()) 
			encoder.writeIntegerElement(ADDITIONAL_NAME_COMPONENTS, additionalNameComponents());

		if (null != publisherID())
			publisherID().encode(encoder);
		
		if (null != excludeFilter())
			excludeFilter().encode(encoder);

		if (null != orderPreference()) 
			encoder.writeIntegerElement(ORDER_PREFERENCE, orderPreference());

		if (null != answerOriginKind()) 
			encoder.writeIntegerElement(ANSWER_ORIGIN_KIND, answerOriginKind());

		if (null != scope()) 
			encoder.writeIntegerElement(SCOPE_ELEMENT, scope());

		if (null != nonce())
			encoder.writeElement(NONCE_ELEMENT, nonce());

		if (null != responseFilter())
			encoder.writeElement(RESPONSE_FILTER_ELEMENT, responseFilter());

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
		
		result = DataUtils.compare(nameComponentCount(), o.nameComponentCount());
		if (result != 0) return result;
		
		result = DataUtils.compare(additionalNameComponents(), o.additionalNameComponents());
		if (result != 0) return result;
		
		result = DataUtils.compare(publisherID(), o.publisherID());
		if (result != 0) return result;
	
		result = DataUtils.compare(excludeFilter(), o.excludeFilter());
		if (result != 0) return result;
		
		result = DataUtils.compare(orderPreference(), o.orderPreference());
		if (result != 0) return result;
		
		result = DataUtils.compare(answerOriginKind(), o.answerOriginKind());
		if (result != 0) return result;
		
		result = DataUtils.compare(scope(), o.scope());
		if (result != 0) return result;

		result = DataUtils.compare(count(), o.count());
		if (result != 0) return result;
		
		result = DataUtils.compare(nonce(), o.nonce());
		if (result != 0) return result;
		
		result = DataUtils.compare(responseFilter(), o.responseFilter());
		if (result != 0) return result;
		
		return result;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime
			* result
			+ ((_nameComponentCount == null) ? 0 : _nameComponentCount
				.hashCode());
		result = prime
			* result
			+ ((_additionalNameComponents == null) ? 0 : _additionalNameComponents
				.hashCode());
		result = prime
				* result
				+ ((_answerOriginKind == null) ? 0 : _answerOriginKind
						.hashCode());
		result = prime * result + ((_count == null) ? 0 : _count.hashCode());
		result = prime * result
				+ ((_excludeFilter == null) ? 0 : _excludeFilter.hashCode());
		result = prime * result + ((_name == null) ? 0 : _name.hashCode());
		result = prime * result + Arrays.hashCode(_nonce);
		result = prime
				* result
				+ ((_orderPreference == null) ? 0 : _orderPreference.hashCode());
		result = prime * result
				+ ((_publisher == null) ? 0 : _publisher.hashCode());
		result = prime * result + Arrays.hashCode(_responseFilter);
		result = prime * result + ((_scope == null) ? 0 : _scope.hashCode());
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
		if (_nameComponentCount == null) {
			if (other._nameComponentCount != null)
				return false;
		} else if (!_nameComponentCount.equals(other._nameComponentCount))
			return false;
		if (_additionalNameComponents == null) {
			if (other._additionalNameComponents != null)
				return false;
		} else if (!_additionalNameComponents.equals(other._additionalNameComponents))
			return false;
		if (_answerOriginKind == null) {
			if (other._answerOriginKind != null)
				return false;
		} else if (!_answerOriginKind.equals(other._answerOriginKind))
			return false;
		if (_count == null) {
			if (other._count != null)
				return false;
		} else if (!_count.equals(other._count))
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
		if (!Arrays.equals(_nonce, other._nonce))
			return false;
		if (_orderPreference == null) {
			if (other._orderPreference != null)
				return false;
		} else if (!_orderPreference.equals(other._orderPreference))
			return false;
		if (_publisher == null) {
			if (other._publisher != null)
				return false;
		} else if (!_publisher.equals(other._publisher))
			return false;
		if (!Arrays.equals(_responseFilter, other._responseFilter))
			return false;
		if (_scope == null) {
			if (other._scope != null)
				return false;
		} else if (!_scope.equals(other._scope))
			return false;
		return true;
	}
}
