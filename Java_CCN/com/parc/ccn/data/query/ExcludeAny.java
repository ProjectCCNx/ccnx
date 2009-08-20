package com.parc.ccn.data.query;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncoder;

/**
 * This element in an #ExcludeFilter matches all components.
 */
public class ExcludeAny extends ExcludeFilter.Filler {
	public static final String ANY = "Any";
	
	public boolean match(byte [] component) {
		return true;
	}

	@Override
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(ANY);
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws XMLStreamException {
		encoder.writeStartElement(ANY);
		encoder.writeEndElement();
	}

	@Override
	public boolean validate() {
		return true;
	}
}
