package org.ccnx.ccn.protocol;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.ByteArrayCompare;



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
public class Exclude extends GenericXMLEncodable implements XMLEncodable,
		Comparable<Exclude> {
	
	public static final String EXCLUDE_ELEMENT = "Exclude";
	public static final String BLOOM_SEED = "BloomSeed";
	public static int OPTIMUM_FILTER_SIZE = 100;
	
	/**
	 * Object to contain elements used in an exclude filter. These elements are either
	 * components, or 'filler' elements - either Any or a BloomFilter.
	 */
	public static abstract class Element extends GenericXMLEncodable implements XMLEncodable {
	}
	public static abstract class Filler extends Element {
		public abstract boolean match(byte [] component);
	}

	protected ArrayList<Element> _values = new ArrayList<Element>();
	
	/**
	 * @param values Must be a list of ExcludeElements - Components must be in increasing order
	 * and there must not be more than one BloomFilter in a row.
	 * @throws #IllegalArgumentException
	 */
	public Exclude(ArrayList<Element> values) {
		// Make sure the values are valid
		ExcludeComponent c = null;
		Element last = null;
		for (Element ee : values) {
			if (ee instanceof ExcludeComponent) {
				ExcludeComponent ec = (ExcludeComponent) ee;
				// Components must be in increasing order, and no duplicates.
				if (c != null && ec.compareTo(c) <=0)
					throw new InvalidParameterException("out of order or duplicate component element");
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
	 * here will result in an #IllegalArgumentException exception
	 * @throws #IllegalArgumentException
	 */
	public Exclude(byte [][] omissions) {
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
	 * @see #Exclude(byte [][])
	 */
	public static Exclude factory(byte [][] omissions) {
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
						// we reached a component in the filter that is lexigraphically after than the one
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
	 * @param omissions
	 * @return new Exclude filter object or null in case of error
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
	 * component passed in. Useful for updating filters during incremental searches.
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

	public boolean empty() {
		synchronized (_values) {
			return ((null == _values) || (_values.isEmpty()));
		}
	}

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(EXCLUDE_ELEMENT);
		
		synchronized (_values) {
			boolean component;
			boolean any = false;
			while ((component = decoder.peekStartElement(ExcludeComponent.COMPONENT)) || (any = decoder.peekStartElement(ExcludeAny.ANY)) ||
						decoder.peekStartElement(BloomFilter.BLOOM)) {
				Element ee = component?new ExcludeComponent(): any ? new ExcludeAny() : new BloomFilter();
				ee.decode(decoder);
				_values.add(ee);
			}
			decoder.readEndElement();
		}
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		// if everything is null, output nothing
		if (empty())
			return;
		
		encoder.writeStartElement(EXCLUDE_ELEMENT);

		synchronized (_values) {
			for (Element element : _values)
				element.encode(encoder);
		}

		encoder.writeEndElement();
	}

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

	public int size() {
		synchronized (_values) {
			return _values.size();
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
					sb.append(ContentName.componentPrintURI(ec.body));
				} else {
					sb.append("B");
				}
			}
		}
		return sb.toString();
	}
}
