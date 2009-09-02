package org.ccnx.ccn.protocol;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.DataUtils;


/**
 * This represents a Component with an Exclude filter
 */
public class ExcludeComponent extends Exclude.Element implements Comparable<ExcludeComponent> {
	public static final String COMPONENT_ELEMENT = "Component";
	protected byte [] body = null;
	
	public ExcludeComponent(byte [] component) {
		body = component.clone();
	}

	public ExcludeComponent() {
	}
	
	@Override
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		body = decoder.readBinaryElement(getElementLabel());
	}

	@Override
	public void encode(XMLEncoder encoder) throws XMLStreamException {
		encoder.writeElement(getElementLabel(), body);
	}
	
	public int compareTo(ExcludeComponent component) {
		return DataUtils.compare(body, component.body);
	}

	public int compareTo(byte [] component) {
		return DataUtils.compare(body, component);
	}

	@Override
	public String getElementLabel() { return COMPONENT_ELEMENT; }

	@Override
	public boolean validate() {
		return body != null;
	}
	
	@Override
	public boolean equals(Object obj) {
		if (obj == null)
			return false;
		if (!(obj instanceof ExcludeComponent))
			return false;
		ExcludeComponent ec = (ExcludeComponent) obj;
		return DataUtils.arrayEquals(body, ec.body);
	}

	public byte [] getBytes() {
		return body.clone();
	}
}
