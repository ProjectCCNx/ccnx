package com.parc.ccn.data.query;

import java.security.InvalidParameterException;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

public class ExcludeFilter extends GenericXMLEncodable implements XMLEncodable,
		Comparable<ExcludeFilter> {
	
	public static final String EXCLUDE_FILTER_ELEMENT = "Exclude";
	public static final String VALUES_ELEMENT = "Values";
	
	protected ArrayList<ExcludeElement> _values;
	
	public ExcludeFilter(ArrayList<ExcludeElement> values) throws InvalidParameterException {
		// Make sure the values are valid
		boolean lastIsComponent = true;
		ContentName lastName = null;
		for (ExcludeElement ee : values) {
			if (!ee._isComponent) {
				if (!lastIsComponent)
					throw new InvalidParameterException("Consecutive bloom filters in Exclude Filter");
			} else {
				if (lastName != null) {
					if (lastName.compareTo(ee._name) >= 0) {
						throw new InvalidParameterException("Components out of order in Exclude Filter");
					}
				}
				lastName = ee._name;
			}
			lastIsComponent = ee._isComponent;
		}
		_values = new ArrayList<ExcludeElement>(values);
	}
	
	public ExcludeFilter() {} // for use by decoders

	public ArrayList<ExcludeElement> values() {
		return _values;
	}
	
	/**
	 * Exclude this co if it matches the filter
	 * @param content
	 * @return
	 */
	public boolean exclude(ContentObject content) {
		for (ExcludeElement ee : values()) {
			if (ee._isComponent) {
				if (ee._name.equals(content.name()))
					return true;
			} else {
				
			}
		}
		return false;
	}

	// TODO should we be able to add values arbitrarily?
	// If so, must be done in order
	
	public boolean empty() {
		return ((null == values()) || (values().isEmpty()));
	}

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(EXCLUDE_FILTER_ELEMENT);
		
		_values = new ArrayList<ExcludeElement>();
		
		while (decoder.peekStartElement(VALUES_ELEMENT)) {
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
		
		encoder.writeStartElement(EXCLUDE_FILTER_ELEMENT);

		if (null != values()) {
			encoder.writeStartElement(VALUES_ELEMENT);
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

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		return result;
	}
}
