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
	
	public ExcludeElement(byte [] component) {
		_component = component;
	}
	
	public ExcludeElement(byte[] component, BloomFilter bloom) {
		this(component);
		_bloom = bloom;
	}
	
	public ExcludeElement() {} // for use by decoders
	
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
		return Arrays.equals(component(), component);
	}

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		if (decoder.peekStartElement(COMPONENT))
			_component = decoder.readBinaryElement(COMPONENT);
		if (decoder.peekStartElement(BLOOM)) {
			_bloom = new BloomFilter();
			_bloom.decode(decoder);
		}
	}
	
	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (component() != null)
			encoder.writeElement(COMPONENT, _component);
		if (bloomFilter() != null)
			bloomFilter().encode(encoder);
	}
	
	public boolean validate() {
		// everything can be null
		return true;
	}
	
	public int compareTo(ExcludeElement o) {
		if (null != component() && null != o.component()) {
			int result = DataUtils.compare(component(), o.component());
			if (0 != result)
				return result;
		} else {
			if (null != component())
				return 1;
			if (null != o.component())
				return -1;
		}	
		if (bloomFilter() == null && o.bloomFilter() == null)
			return 0;
		if (bloomFilter() == null)
			return 1;
		if (o.bloomFilter() == null)
			return -1;
		return bloomFilter().compareTo(o.bloomFilter());
	}
	
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ExcludeElement other = (ExcludeElement) obj;
		if (component() != null && other.component() != null) {
			if (!Arrays.equals(component(), other.component()))
				return false;
		} else {
			if (component() != null || other.component() != null)
				return false;
		}
		if (bloomFilter() == null && other.bloomFilter() == null)
			return true;
		if (bloomFilter() == null || other.bloomFilter() == null)
			return false;
		return bloomFilter().equals(other.bloomFilter());
	}
	
	public ExcludeElement clone() throws CloneNotSupportedException {
		ExcludeElement result = (ExcludeElement)super.clone();
		result._component = _component.clone();
		result._bloom = _bloom.clone();
		return result;
		//		return new ExcludeElement(_component.clone(), _bloom);
	}
}
