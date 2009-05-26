package com.parc.ccn.data.query;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.util.DataUtils;
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
 *
 * @author rasmusse
 *
 */

public class ExcludeFilter extends GenericXMLEncodable implements XMLEncodable,
		Comparable<ExcludeFilter> {
	
	public static final String EXCLUDE_ELEMENT = "Exclude";
	public static final String BLOOM = "Bloom";
	public static final String BLOOM_SEED = "BloomSeed";
	public static int OPTIMUM_FILTER_SIZE = 100;

	protected ArrayList<ExcludeElement> _values;
	
	public ExcludeFilter(ArrayList<ExcludeElement> values) 
				throws InvalidParameterException {
		// Make sure the values are valid
		byte [] lastName = null;
		for (ExcludeElement ee : values) {
			if (lastName != null) {
				if (DataUtils.compare(lastName, ee._component) >= 0) {
					throw new InvalidParameterException("Components out of order in Exclude Filter");
				}
			}
			lastName = ee._component;
		}
		_values = new ArrayList<ExcludeElement>(values);
	}
		
	public ExcludeFilter(byte [][] omissions)
		throws InvalidParameterException {
		if (omissions == null || omissions.length == 0)
			throw new InvalidParameterException("No omissions");
		Comparator<byte[]> comparator = new ByteArrayCompare();
		Arrays.sort(omissions, comparator);
		ArrayList<ExcludeElement> elements = new ArrayList<ExcludeElement>();
		int filterCount = 0;
		boolean needNewElement = omissions.length > OPTIMUM_FILTER_SIZE;
		BloomFilter currentFilter = null;;
		if (!needNewElement) {
			currentFilter = createBloom();
			elements.add(new ExcludeElement(null, currentFilter));
		}
		for (byte[] omission : omissions) {
			if (filterCount > OPTIMUM_FILTER_SIZE) {
				needNewElement = true;
				filterCount = 0;
			}
			if (needNewElement) {
				currentFilter = createBloom();
				elements.add(new ExcludeElement(omission, currentFilter));
				needNewElement = false;
			} else {
				currentFilter.insert(omission);
				filterCount++;
			}
		}
		_values = elements;
	}

	public ExcludeFilter() {} // for use by decoders

	public ArrayList<ExcludeElement> values() {
		return _values;
	}
	
	private static BloomFilter createBloom() {
		byte[] seed = new byte[4];
		BloomFilter.createSeed(seed);
		return new BloomFilter(OPTIMUM_FILTER_SIZE, seed);
	}

	/**
	 * Exclude this co if it matches the filter
	 * @param content
	 * @return
	 */
	public boolean exclude(byte [] component) {
		for (int i = 0; i < values().size(); i++) {
			ExcludeElement ee = values().get(i);
			if (ee.exclude(component))
				return true;
				
			// Bloom filter case. If the next component is less than us,
			// we don't want to use this filter. If its the same, we go on
			// and catch it at the next value
			if (ee.bloomFilter() != null) {
				if (values().size() > (i + 1)) {
					byte [] nextComponent = values().get(i + 1).component();
					if (DataUtils.compare(nextComponent, component) <= 0)
						continue;
				}
				
				// Finally test via the filter. Since our value is in between the components
				// before and after we don't need to continue testing
				return ee.bloomFilter().match(component);
			}
		}
		return false;
	}
	
	/**
	 * Return a new ExcludeFilter that is a copy of this one with 
	 * the supplied omissions added.
	 * @param omissions
	 * @return new ExcludeFilter object or null in case of error
	 */
	public ExcludeFilter add(byte[][] omissions) {
		if (omissions == null || omissions.length == 0) {
			return null;
		}
		Comparator<byte[]> comparator = new ByteArrayCompare();
		Arrays.sort(omissions, comparator);
		ExcludeFilter result = new ExcludeFilter();
		result._values = new ArrayList<ExcludeElement>();
		ExcludeElement prev = null;
		int j = 0;
		byte[] omission = omissions[j];
		for (int i = 0; i < values().size(); i++) {
			ExcludeElement ee = values().get(i);
			for ( ; j<omissions.length; j++, omission=omissions[j]) { 
				if (DataUtils.compare(ee._component, omission) > 0) {
					// Current component is greater than current omission
					// so we use prev filter
					if (null != prev) {
						prev._bloom.insert(omission);
					} else {
						prev = new ExcludeElement(null, createBloom());
						result._values.add(prev);
					}
				} else {
					// Gone past point where we can use previous filter
					// so break out of inner loop to set up next filter
					break;
				}
			}
			try {
				prev = ee.clone();
			} catch (CloneNotSupportedException e) {
				return null;
			}
			result._values.add(prev);
		}
		if (j < omissions.length) {
			// Got all the way through and found no suitable filter so need
			// to add for remainder
			byte[][] remainder = new byte[omissions.length-j][];
			for (int k=j; k < omissions.length; k++) {
				remainder[k-j] = omissions[k];
			}
			ExcludeFilter remFilter = new ExcludeFilter(remainder); // builds remainder list
			ExcludeElement first = remFilter._values.get(0);
			if (null == first._component) {
				first._component = omissions[j];
			}
			result._values.addAll(remFilter._values);
		}
		return result;
	}

	// TODO should we be able to add values arbitrarily?
	// If so, must be done in order
	
	public boolean empty() {
		return ((null == values()) || (values().isEmpty()));
	}

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(EXCLUDE_ELEMENT);
		
		_values = new ArrayList<ExcludeElement>();
		
		while (decoder.peekStartElement(ExcludeElement.COMPONENT) || decoder.peekStartElement(BLOOM)) {
			ExcludeElement ee = new ExcludeElement();
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

		if (null != values()) {
			for (ExcludeElement element : values()) {
				element.encode(encoder);
			}
		}

		encoder.writeEndElement();
	}

	public boolean validate() {
		// everything can be null
		return true;
	}

	public int compareTo(ExcludeFilter o) {
		int result = 0;
		if (values() == null && o.values() != null)
			return -1;
		if (_values != null) {
			if (o.values() == null)
				return 1;
			result = values().size() - o.values().size();
			if (0 != result) return result;
			for (int i = 0; i < values().size(); i++) {
				ExcludeElement ee = values().get(i);
				result = ee.compareTo(o.values().get(i));
				if (0 != result)
					return result;
			}
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
		if ((values().size() > other.values().size()))
			return false;
		for (int i=0; i < values().size(); ++i) {
			if (!values().get(i).equals(other.values().get(i)))
				return false;
		}
		return true;
	}
}
