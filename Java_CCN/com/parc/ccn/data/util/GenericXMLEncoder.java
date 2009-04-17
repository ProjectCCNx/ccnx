package com.parc.ccn.data.util;

import javax.xml.stream.XMLStreamException;

public abstract class GenericXMLEncoder implements XMLEncoder {

	public void writeIntegerElement(String tag, Integer value) throws XMLStreamException {
		writeElement(tag, value.toString());
	}
}
