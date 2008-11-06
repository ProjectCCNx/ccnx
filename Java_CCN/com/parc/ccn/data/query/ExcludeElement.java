package com.parc.ccn.data.query;

import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

/**
 * Object to contain elements used in an exclude filter
 * @author rasmusse
 *
 */
public class ExcludeElement extends GenericXMLEncodable implements XMLEncodable,
			Comparable<ExcludeElement>	{
	public static final String COMPONENT = "Component";
	public static final String BLOOM = "Bloom";
	
	BloomFilter _bloom = null;
	byte [] _component = null;
	boolean _isComponent;
	
	public ExcludeElement(byte [] component) {
		_component = component;
		_isComponent = true;
	}
	
	public ExcludeElement(BloomFilter bloom) {
		_bloom = bloom;
		_isComponent = false;
	}
	
	public ExcludeElement() {} // for use by decoders
	
	public boolean isComponent() {
		return _isComponent;
	}
	
	public byte [] component() {
		return _component;
	}
	
	public BloomFilter bloomFilter() {
		return _bloom;
	}
	
	/**
	 * Exclude if matches
	 * @param co
	 * @return
	 */
	public boolean exclude(byte [] component) {
		if (_isComponent) {
			return (Arrays.equals(_component, component));
		}
		return _bloom.match(component);
	}

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		_isComponent = decoder.peekStartElement(COMPONENT);
		if (_isComponent) {
			_component = decoder.readBinaryElement(COMPONENT);
		} else {
			_bloom = new BloomFilter(decoder.readBinaryElement(BLOOM));
		}
	}
	
	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (_isComponent) {
			encoder.writeElement(COMPONENT, _component);
		} else {
			encoder.writeElement(BLOOM, _bloom.bloom());
		};
	}
	
	public boolean validate() {
		// everything can be null
		return true;
	}
	
	public int compareTo(ExcludeElement o) {
		if (_isComponent != o._isComponent)
			return _isComponent ? 1 : -1; //arbitrary
		if (_isComponent) {
			return DataUtils.compare(_component, o._component);
		}
		return _bloom.compareTo(o._bloom);
	}
	
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ExcludeElement other = (ExcludeElement) obj;
		if (_isComponent) {
			if (!other._isComponent)
				return false;
			return Arrays.equals(_component, other._component);
		} else {
			if (other._isComponent)
				return false;
		}
		return true;
	}
}
