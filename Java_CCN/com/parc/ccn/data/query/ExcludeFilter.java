package com.parc.ccn.data.query;

import java.util.ArrayList;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

public class ExcludeFilter extends GenericXMLEncodable implements XMLEncodable,
		Comparable<ExcludeFilter> {
	
	public static final String EXCLUDE_FILTER_ELEMENT = "Exclude";
	public static final String BLOOM_SEED_ELEMENT = "BloomSeed";
	public static final String BLOOM_ELEMENT = "Bloom";
	public static final String COMPONENT_ELEMENT = "Component";
	
	protected byte [] _bloomSeed;
	protected byte [] _bloom;
	protected ArrayList<byte []> _components;
	
	public ExcludeFilter(byte [] bloomSeed, byte [] bloom, ArrayList<byte []> components) {
		_bloomSeed = bloomSeed;
		_bloom = bloom;
		_components = new ArrayList<byte[]>(components);
	}
	
	public ExcludeFilter() {} // for use by decoders

	public byte[] bloomSeed() {
		return _bloomSeed;
	}

	public void bloomSeed(byte[] seed) {
		_bloomSeed = seed;
	}

	public byte[] bloom() {
		return _bloom;
	}

	public void bloom(byte[] _bloom) {
		this._bloom = _bloom;
	}

	public ArrayList<byte []> components() {
		return _components;
	}

	public void addComponent(byte[] component) {
		this._components.add(component);
	}
	
	public boolean empty() {
		return ((null == bloomSeed()) && (null == bloom()) && ((null == components()) || (components().isEmpty())));
	}

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(EXCLUDE_FILTER_ELEMENT);
		
		if (decoder.peekStartElement(BLOOM_SEED_ELEMENT)) {
			_bloomSeed = decoder.readBinaryElement(BLOOM_SEED_ELEMENT);
		}
		if (decoder.peekStartElement(BLOOM_ELEMENT)) {
			_bloom = decoder.readBinaryElement(BLOOM_ELEMENT);
		}
		while (decoder.peekStartElement(COMPONENT_ELEMENT)) {
			_components.add(decoder.readBinaryElement(COMPONENT_ELEMENT));
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

		if (null != bloomSeed())
			encoder.writeElement(BLOOM_SEED_ELEMENT, bloomSeed());

		if (null != bloom())
			encoder.writeElement(BLOOM_ELEMENT, bloom());

		if (null != components()) {
			for (byte [] component : components()) {
				encoder.writeElement(COMPONENT_ELEMENT, component);
			}
		}

		encoder.writeEndElement();   				
	}

	public boolean validate() {
		// everything can be null
		return true;
	}

	public int compareTo(ExcludeFilter o) {
		int result = DataUtils.compare(bloomSeed(), o.bloomSeed());
		if (0 != result) return result;
		
		result = DataUtils.compare(bloom(), o.bloom());
		if (0 != result) return result;
		
		result = DataUtils.compare(components(), o.components());
		
		return result;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(_bloom);
		result = prime * result + Arrays.hashCode(_bloomSeed);
		result = prime * result
				+ ((_components == null) ? 0 : _components.hashCode());
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
		ExcludeFilter other = (ExcludeFilter) obj;
		if (!Arrays.equals(_bloom, other._bloom))
			return false;
		if (!Arrays.equals(_bloomSeed, other._bloomSeed))
			return false;
		if (_components == null) {
			if (other._components != null)
				return false;
		} else if (!_components.equals(other._components))
			return false;
		return true;
	}

}
