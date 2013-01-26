/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;

import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.ByteArrayCompare;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;

/**
 * Exclude filters are used during Interest matching to exclude content.
 * The filter works on the name component after the last one specified in the Interest.
 * 
 * Exclude filters contain at least one element. The elements are either a name component,
 * a bloom filter or the 'any' element. This allows the specification of individual component values to be
 * excluded, as well as arbitrary ranges of component values and a compact form for long lists of
 * component values (bloom filters).
 * 
 * The order of elements within an exclude filter must follow 2 rules:
 * 1. Within an exclude filter all name component elements must be in ascending order wherever they occur
 * and there should be no duplicates.
 * 2. An any element or a bloom filter element must not be followed by an any element or bloom filter.
 * @see Filler
 * I.E. Any elements or bloom filters must be separated by at least one name component element.
 */
public class Exclude extends GenericXMLEncodable implements XMLEncodable,
		Comparable<Exclude> {
	
	public static int OPTIMUM_FILTER_SIZE = 100;
	
	/**
	 * Object to contain elements used in an exclude filter. These elements are either
	 * name components, or 'filler' elements - either Any or a BloomFilter.
	 */
	public static abstract class Element extends GenericXMLEncodable implements XMLEncodable {
	}
	
	/**
	 * A filler element occurs in a Exclude filter between 2 name components which may be
	 * an implied name component if the filler element is the first or last element in the Exclude filter.
	 * If a filler element is the first element in an Exclude filter there is an implied name component
	 * before it that matches the first possible name component (in name component ordering). Equally
	 * If the last element in an Exclude filter the implied name component after it is the last possible
	 * name component (in name component ordering).
	 */
	public static abstract class Filler extends Element {
		public abstract boolean match(byte [] component);
	}

	protected ArrayList<Element> _values = new ArrayList<Element>();
	
	/**
	 * @param values Must be a list of ExcludeElements - Components must be in increasing order
	 * and there must not be more than one BloomFilter in a row.
	 * @throws IllegalArgumentException
	 */
	public Exclude(ArrayList<Element> values) {
		// Make sure the values are valid
		ExcludeComponent c = null;
		Element last = null;
		for (Element ee : values) {
			if (ee instanceof ExcludeComponent) {
				ExcludeComponent ec = (ExcludeComponent) ee;
				// Components must be in increasing order, and no duplicates.
				if (c != null && ec.compareTo(c) <=0) {
					//getting this error... adding more debugging information
					String errorMessage = "out of order or duplicate component element: comparing "+Component.printURI(c.getComponent())+" and "+Component.printURI(ec.getComponent());
					throw new InvalidParameterException(errorMessage);
				}
				c = ec;
			} else if (last instanceof Filler)
				// do not allow 2 fillers in a row
				throw new InvalidParameterException("bloom filters or anys are not allowed to follow each other");
			last = ee;
		}			
		_values.addAll(values);
	}

	/**
	 * Create an Exclude filter that excludes exactly the listed name components.
	 * @param omissions The name components to be excluded. Passing in null or a zero length array
	 * here will result in an IllegalArgumentException exception
	 * @throws IllegalArgumentException
	 */
	public Exclude(byte omissions[][]) {
		if (omissions == null || omissions.length == 0)
			throw new IllegalArgumentException("No omissions");
		Arrays.sort(omissions, new ByteArrayCompare());
		for (byte[] omission : omissions) {
			_values.add(new ExcludeComponent(omission));
		}
	}

	public Exclude() {} // for use by decoders

	/**
	 * Create an Exclude filter that excludes all components up to and including the one given,
	 * but none after.
	 * @param component if a null component is passed in then null is returned.
	 */
	public static Exclude uptoFactory(byte [] component) {
		if ( component == null)
			return null;
		Exclude ef = new Exclude();
		synchronized (ef._values) {
			ef._values.add(new ExcludeAny());
			ef._values.add(new ExcludeComponent(component));
		}
		return ef;
	}

	/**
	 * @param omissions List of names to exclude, or null
	 * @return returns null if list is null or empty, or a new Exclude filter that excludes the listed names.
	 * @see Exclude(byte [][])
	 */
	public static Exclude factory(byte omissions [][] ) {
		if (omissions == null || omissions.length == 0)
			return null;
		return new Exclude(omissions);
	}
	
	/**
	 * @param component - A name component
	 * @return true if this component would be excluded by the exclude filter
	 */
	public boolean match(byte [] component) {
		Filler lastFiller = null;
		synchronized (_values) {
			for (Element ee : _values) {
				if (ee instanceof ExcludeComponent) {
					ExcludeComponent ec = (ExcludeComponent) ee;
					int res = ec.compareTo(component);
					if (res == 0) {
						// we exactly matched a component in the filter
						return true;
					} else if (res > 0) {
						// we reached a component in the filter that is lexicographically after than the one
						// we're looking for so check if there was a filler between the last component
						// we saw and this one.
						return lastFiller != null && lastFiller.match(component);
					}
					lastFiller = null;
				} else {
					// The element is not a component - so track what filler it was.
					lastFiller = (Filler) ee;
				}
			}
		}
		return lastFiller != null && lastFiller.match(component);
	}

	/**
	 * Return a new Exclude filter that is a copy of this one with 
	 * the supplied omissions added.
	 * @param omissions name components to be excluded.
	 * @return new Exclude filter object or null in case of error
	 */
	public void add(byte omissions[][] ) {
		if (omissions == null || omissions.length == 0)
			return;

		Arrays.sort(omissions, new ByteArrayCompare());

		/*
		 * i is an outer loop on the omissions list
		 * j is an inner loop on the ExcludeEntries
		 */
		int i = 0, j = 0;
		byte [] omission;
		Element ee;
		Filler lastFiller = null;
		synchronized (_values) {
			for(;i<omissions.length && j<_values.size();) {
				omission = omissions[i];
				ee = _values.get(j);
				if (ee instanceof ExcludeComponent) {
					ExcludeComponent ec = (ExcludeComponent) ee;
					int res = ec.compareTo(omission);
					if (res > 0) {
						// we reached a component in the filter that is lexigraphically after than the one
						// we're looking for.
						if (lastFiller != null && lastFiller.match(omission)) {
							// the filler already matches the component, no need to add it!
							i++;
							continue;
						}
						// no bloom or the bloom does not match - so add the component explicitly
						_values.add(j, new ExcludeComponent(omission));
						j++; // skip the component we just added
						if (lastFiller != null) {
							// there was a non matching bloom, so copy it to ensure same values get excluded
							// TODO: should this be a clone()?
							_values.add(j, lastFiller);
							j++; // skip the bloom we just added
						}
						i++;
						continue;
					} else if (res == 0) {
						// we matched a component already in the filter, so no need to add one in, just skip it.
						i++;
						continue;
					}
					lastFiller = null;
				} else
					lastFiller = (Filler) ee;
				j++;
			}
			// if we have values still to add, then add them to the end of the list
			for(;i<omissions.length;i++) {
				omission = omissions[i];
				_values.add(new ExcludeComponent(omission));
			}
		}
	}
	
	/**
	 * Take an existing Exclude filter and additionally exclude all components up to and including the
	 * component passed in. Useful for updating filters during incremental searches. E.G. for version
	 * number components.
	 * @param component if null then the Exclude filter is left unchanged.
	 */
	public void excludeUpto(byte [] component) {
		if (component == null)
			return;

		Filler lastFiller = null;
		synchronized (_values) {
			int res = -2;
			int removes = 0;
			for (Element ee : _values) {
				if (ee instanceof ExcludeComponent) {
					ExcludeComponent ec = (ExcludeComponent) ee;
					res = ec.compareTo(component);
					if (res >= 0)
						break;
					lastFiller = null;
				} else {
					// The element is not a component - so track what filler it was.
					lastFiller = (Filler) ee;
				}
				removes++;
			}
			for (int i = 0; i < removes; i++)
				_values.remove(0);
			if (res == 0) {
				// we exactly matched a component already in the filter
				// prefix it with an Any element, and we're done.
				_values.add(0, new ExcludeAny());
			} else {
	
				if (lastFiller == null) {
					// there was no filler, so prefix the list with an Any and the component, and we're done
					_values.add(0, new ExcludeAny());
					_values.add(1, new ExcludeComponent(component));
					return;
				}
				if (lastFiller instanceof ExcludeAny) {
					_values.add(0, new ExcludeAny());
					return;
				}
				_values.add(0, new ExcludeAny());
				_values.add(1, new ExcludeComponent(component));
				_values.add(2, lastFiller);
			}
		}
		return;		
	}

	/**
	 * Check for exclude with no elements
	 * @return true if exclude has no elements
	 */
	public boolean empty() {
		synchronized (_values) {
			return _values.isEmpty();
		}
	}

	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());
		
		synchronized (_values) {
			boolean component;
			boolean any = false;
			while ((component = decoder.peekStartElement(CCNProtocolDTags.Component)) || 
					(any = decoder.peekStartElement(CCNProtocolDTags.Any)) ||
						decoder.peekStartElement(CCNProtocolDTags.Bloom)) {
				@SuppressWarnings("deprecation")
				Element ee = component?new ExcludeComponent(): any ? new ExcludeAny() : new BloomFilter();
				ee.decode(decoder);
				_values.add(ee);
			}
			decoder.readEndElement();
		}
	}

	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		// if everything is null, output nothing
		if (empty())
			return;
		
		encoder.writeStartElement(getElementLabel());

		synchronized (_values) {
			for (Element element : _values)
				element.encode(encoder);
		}

		encoder.writeEndElement();
	}

	@Override
	public long getElementLabel() { return CCNProtocolDTags.Exclude; }

	@Override
	public boolean validate() {
		// everything can be null
		return true;
	}

	public int compareTo(Exclude o) {
		int result = 0;
		if (empty() && !o.empty())
			return -1;
		if (!empty()) {
			if (o.empty())
				return 1;
			synchronized (_values) {
				result = _values.size() - o._values.size();
			}
			// TODO: need a better definition of ordering between exclude filters
			// it's definitely an error to report they are the same just based on length
			// but first - is this ever used?
		}
		
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
		Exclude other = (Exclude) obj;
		synchronized (_values) {
			return _values.equals(other._values);
		}
	}
	
	public int hashCode() {
		return _values.hashCode();
	}

	/**
	 * Gets the number of elements in the Exclude filter
	 * @return number of elements
	 */
	public int size() {
		synchronized (_values) {
			return _values.size();
		}
	}
	
	/**
	 * DEBUGGING ONLY -- may need to be removed.
	 */
	public Element value(int i) {
		synchronized(_values) {
			return _values.get(i);
		}
	}

	public String toString() {
		StringBuffer sb = new StringBuffer();
		boolean first = true;
		synchronized (_values) {
			for (Element ee : _values) {
				if (first)
					first = false;
				else
					sb.append(",");
				if (ee instanceof ExcludeComponent) {
					ExcludeComponent ec = (ExcludeComponent) ee;
					sb.append(Component.printURI(ec.body));
				} else {
					sb.append("B");
				}
			}
		}
		return sb.toString();
	}
}
