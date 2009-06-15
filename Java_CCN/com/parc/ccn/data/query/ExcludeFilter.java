package com.parc.ccn.data.query;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

/**
 * Terse documentation of these filters:
 * 
 * The filters are used to exclude content with components which match the filters 1 level
 * below the prefix length of the interest.
 * 
 * Filters can contain 1-n "elements". The elements consist of either a component name
 * or a bloom filter. The elements must contain one of the following:
 * 
 *  - A single bloom filter. If the component to be tested matches the filter, it is excluded.
 *  - A series of ordered components, canonically increasing. If the component to be tested 
 *       exactly matches any of these components, it is excluded.
 *  - A mixture of bloom filters and components with the component elements canonically increasing.
 *       2 bloom filters in a row are not allowed. The component to be tested is excluded if it
 *       exactly matches one of the components or if it is canonically located between 2 components 
 *       in the series of elements and it matches a bloom filter located between those 2 components.
 */

public class ExcludeFilter extends GenericXMLEncodable implements XMLEncodable,
		Comparable<ExcludeFilter> {
	
	public static final String EXCLUDE_ELEMENT = "Exclude";
	public static final String BLOOM_SEED = "BloomSeed";
	public static int OPTIMUM_FILTER_SIZE = 100;

	protected ArrayList<ExcludeElement> _values;
	
	/**
	 * @param values Must be a list of ExcludeElements - Components must be in increasing order
	 * and there must not be more than one BloomFilter in a row.
	 * @throws InvalidParameterException
	 */
	public ExcludeFilter(ArrayList<ExcludeElement> values)
				throws InvalidParameterException {
		// Make sure the values are valid
		ExcludeComponent c = null;
		ExcludeElement last = null;
		for (ExcludeElement ee : values) {
			if (ee instanceof ExcludeComponent) {
				ExcludeComponent ec = (ExcludeComponent) ee;
				// Components must be in increasing order
				if (c != null && ec.compareTo(c) <=0)
					throw new InvalidParameterException();
				c = ec;
			} else if (last instanceof BloomFilter)
				// do not allow 2 bloom filters in a row
				throw new InvalidParameterException();
			last = ee;
		}
		_values = new ArrayList<ExcludeElement>(values);
	}
		
	/**
	 * Create an Exclude filter that excludes exactly the listed name components.
	 * @param omissions The name components to be excluded.
	 * @throws InvalidParameterException
	 */
	public ExcludeFilter(byte [][] omissions)
		throws InvalidParameterException {
		if (omissions == null || omissions.length == 0)
			throw new InvalidParameterException("No omissions");
		Arrays.sort(omissions, new ByteArrayCompare());
		_values = new ArrayList<ExcludeElement>();
		for (byte[] omission : omissions) {
			_values.add(new ExcludeComponent(omission));
		}
	}

	public ExcludeFilter() {} // for use by decoders
	
	/**
	 * @param omissions List of names to exclude, or null
	 * @return returns null if list is null, or a new Exclude filter that excludes the listed names. @see #ExcludeFilter(byte [][])
	 */
	public static ExcludeFilter factory(byte [][] omissions) {
		if (omissions == null || omissions.length == 0)
			return null;
		return new ExcludeFilter(omissions);
	}

	/**
	 * @param component - A name component
	 * @return true if this component matches the exclude filter
	 */
	public boolean exclude(byte [] component) {
		BloomFilter bloom = null;
		for (ExcludeElement ee : _values) {
			if (ee instanceof ExcludeComponent) {
				ExcludeComponent ec = (ExcludeComponent) ee;
				int res = ec.compareTo(component);
				if (res > 0) {
					// we reached a component in the filter that is lexigraphically after than the one
					// we're looking for so check the bloom and exit.
					return bloom != null && bloom.match(component);
				} else if (res == 0)
					// we matched a component in the filter
					return true;
				bloom = null;
			} else
				bloom = (BloomFilter) ee;
		}
		return bloom != null && bloom.match(component);
	}
	
	/**
	 * Return a new ExcludeFilter that is a copy of this one with 
	 * the supplied omissions added.
	 * @param omissions
	 * @return new ExcludeFilter object or null in case of error
	 */
	public void add(byte[][] omissions) {
		if (omissions == null || omissions.length == 0)
			return;

		Arrays.sort(omissions, new ByteArrayCompare());

		/*
		 * i is an outer loop on the omissions list
		 * j is an inner loop on the ExcludeEntries
		 */
		int i = 0, j = 0;
		byte [] omission;
		ExcludeElement ee;
		BloomFilter bloom = null;
		for(;i<omissions.length && j<_values.size();) {
			omission = omissions[i];
			ee = _values.get(j);
			if (ee instanceof ExcludeComponent) {
				ExcludeComponent ec = (ExcludeComponent) ee;
				int res = ec.compareTo(omission);
				if (res > 0) {
					// we reached a component in the filter that is lexigraphically after than the one
					// we're looking for so check the bloom and exit.
					if (bloom != null && bloom.match(omission)) {
						// the bloom already matches the component, no need to add it!
						i++;
						continue;
					}
					// no bloom or the bloom does not match - add the component explicitly
					_values.add(j, new ExcludeComponent(omission));
					j++; // skip the component we just added
					if (bloom != null) {
						// there was a bloom, so copy it to ensure same values get excluded
						try {
							_values.add(j, bloom.clone());
							j++; // skip the bloom we just added
						} catch (CloneNotSupportedException e) {
							throw new RuntimeException(e);
						}
					}
					i++;
					continue;
				} else if (res == 0) {
					// we matched a component in the filter
					i++;
					continue;
				}
				bloom = null;
			} else
				bloom = (BloomFilter) ee;
			j++;
		}
		// if we have values still to add, then add them to the end of the list
		for(;i<omissions.length;i++) {
			omission = omissions[i];
			_values.add(new ExcludeComponent(omission));
		}
	}

	public boolean empty() {
		return ((null == _values) || (_values.isEmpty()));
	}

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(EXCLUDE_ELEMENT);
		
		_values = new ArrayList<ExcludeElement>();
		
		boolean component;
		while ((component = decoder.peekStartElement(ExcludeComponent.COMPONENT)) || decoder.peekStartElement(BloomFilter.BLOOM)) {
			ExcludeElement ee = component?new ExcludeComponent():new BloomFilter();
			ee.decode(decoder);
			_values.add(ee);
		}
		decoder.readEndElement();
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		// if everything is null, output nothing
		if (empty())
			return;
		
		encoder.writeStartElement(EXCLUDE_ELEMENT);

		for (ExcludeElement element : _values)
			element.encode(encoder);

		encoder.writeEndElement();
	}

	public boolean validate() {
		// everything can be null
		return true;
	}

	public int compareTo(ExcludeFilter o) {
		int result = 0;
		if (empty() && !o.empty())
			return -1;
		if (!empty()) {
			if (o.empty())
				return 1;
			result = _values.size() - o._values.size();
			// TODO: need a better definition of ordering between exclude filters
			// it's definitely an error to report they are the same just based on length
			// but first - is this ever used?
		}
		
		return result;
	}
	
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ExcludeFilter other = (ExcludeFilter) obj;
		return _values.equals(other._values);
	}

	public int size() {
		return _values.size();
	}

	public String toString() {
		StringBuffer sb = new StringBuffer();
		boolean first = true;
		for (ExcludeElement ee : _values) {
			if (first)
				first = false;
			else
				sb.append(",");
			if (ee instanceof ExcludeComponent) {
				ExcludeComponent ec = (ExcludeComponent) ee;
				sb.append(ContentName.componentPrintURI(ec.body));
			} else {
				sb.append("B");
			}
		}
		return sb.toString();
	}
}
