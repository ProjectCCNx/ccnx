package com.parc.ccn.data.query;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

/**
 * The exclude filters are used with Interest matching to exclude content with components
 * which match the filters 1 level below the prefix length of the interest.
 * 
 * Filters contain at least one element. The elements consist of either a component name,
 * a bloom filter or the 'any' element.
 * 
 * The order of elements within an exclude filter must follow 2 rules:
 * 1. Within an exclude filter all component elements must be in ascending order wherever they occur
 * and there should be no duplicates.
 * 2. An any element or a bloom filter element must not be followed by an any element or bloom filter.
 * I.E. Any elements or bloom filters must be separated by at least one component element.
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
				// Components must be in increasing order, and no duplicates.
				if (c != null && ec.compareTo(c) <=0)
					throw new InvalidParameterException("out of order or duplicate component element");
				c = ec;
			} else if (last instanceof BloomFilter ||
					last instanceof ExcludeAny)
				// do not allow 2 bloom filters/Any's in a row
				throw new InvalidParameterException("bloom filters or anys are not allowed to follow each other");
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
	 * Exclude range of values between input ranges
	 * @param omissions
	 * @return
	 */
	public static ArrayList<ExcludeElement> rangeFactory(byte [][] range) {
		if (range == null || range.length == 0 || (range.length %2) != 0)
			return null;
		int i = 0;
		ArrayList<ExcludeElement>values = new ArrayList<ExcludeElement>();
		while (i < range.length) {
			values.add(new ExcludeComponent(range[i]));
			values.add(new ExcludeAny());
			values.add(new ExcludeComponent(range[i+1]));
			i += 2;
		}
		return values;
	}

	/**
	 * @param component - A name component
	 * @return true if this component matches the exclude filter
	 */
	public boolean exclude(byte [] component) {
		BloomFilter bloom = null;
		for (int i = 0; i < _values.size(); i++) {
			ExcludeElement ee = _values.get(i);
			if (ee instanceof ExcludeComponent) {
				ExcludeComponent ec = (ExcludeComponent) ee;
				byte[][] range = getRange(i);
				if (range != null) {
					if (DataUtils.compare(component, range[0]) >= 0) {
						if (DataUtils.compare(component, range[1]) <= 0)
							return true;
					}
					i += 2;
					if (i >= _values.size()) {
						return false;
					}
					continue;
				}
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
					// we're looking for.
					byte[][] range = getRange(j);
					if (null != range) {
						if (DataUtils.compare(omission,range[1]) < 0) {
							i++;	// Falls within an existing range
							continue;
						}		
					}
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
	
	/**
	 * Add a range - only one is allowed for now...
	 * @param range
	 */
	public void addRange(byte[][] range) {
		if (range.length != 2)
			throw new NotImplementedException();
		if (DataUtils.compare(range[0], range[1]) > 0)
			throw new IllegalArgumentException("Invalid range");
		for (int i = 0; i < _values.size(); i++) {
			ExcludeElement ee = _values.get(i);
			byte[][] oldRange = getRange(i);
			if (oldRange != null) {
				ExcludeComponent ec = (ExcludeComponent)ee;
				if ((DataUtils.compare(oldRange[0], range[0]) < 0) && (DataUtils.compare(range[1], oldRange[1]) < 0))
					return;  	// oldRange completely encapsulates new range
				if (DataUtils.compare(range[0], oldRange[0]) < 0) {
					if (DataUtils.compare(oldRange[0], range[1]) < 0) {
						// We are extending an old range in front
						ec.body = range[0];
						return;
					}
				}
				if (DataUtils.compare(range[0], oldRange[1]) < 0) {
					if (DataUtils.compare(oldRange[1], range[1]) < 0) {
						// We are extending an old range in back
						ec = (ExcludeComponent)_values.get(i + 2);
						ec.body = range[0];
						return;
					}
				}
				i += 2;
			} 
			if (! (ee instanceof BloomFilter)) {
				ExcludeComponent ec = (ExcludeComponent)ee;
				if (DataUtils.compare(range[1], ec.body) < 0) {
					_values.addAll(i, rangeFactory(range));
					if (DataUtils.compare(ec.body, range[0]) < 0) {
						_values.remove(i + 3);
					}
					return;
				}
			}
		}
	}
	
	/**
	 * Get a range at i if there is one
	 * XXX - should we check for bogus bloom filters here?
	 */
	protected byte[][] getRange(int i) {
		if ((_values.size() < i + 2))
			return null;
		ExcludeElement ee = _values.get(i + 1);
		if (! (ee instanceof ExcludeAny))
			return null;
		byte[][] range = new byte[2][];
		range[0] = ((ExcludeComponent)_values.get(i)).body;
		range[1] = ((ExcludeComponent)_values.get(i + 2)).body;
		return range;
	}

	public boolean empty() {
		return ((null == _values) || (_values.isEmpty()));
	}

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(EXCLUDE_ELEMENT);
		
		_values = new ArrayList<ExcludeElement>();
		
		boolean component;
		boolean any = false;
		while ((component = decoder.peekStartElement(ExcludeComponent.COMPONENT)) || (any = decoder.peekStartElement(ExcludeAny.ANY)) ||
					decoder.peekStartElement(BloomFilter.BLOOM)) {
			ExcludeElement ee = component?new ExcludeComponent(): any ? new ExcludeAny() : new BloomFilter();
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
	
	public ArrayList<ExcludeElement> getValues() {
		return _values;
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
