package com.parc.ccn.data.query;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncoder;

/**
 * 
 * @author rasmusse
 *
 */
public class ExcludeAny extends ExcludeElement {
	public static final String ANY = "Any";

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
