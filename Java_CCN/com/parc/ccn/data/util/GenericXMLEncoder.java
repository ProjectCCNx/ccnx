package com.parc.ccn.data.util;

import java.sql.Timestamp;

import javax.xml.stream.XMLStreamException;

public abstract class GenericXMLEncoder implements XMLEncoder {

	/**
	 * For now, same as text. Might want something more compact.
	 */
	public void writeDateTime(String tag, Timestamp dateTime) throws XMLStreamException {
		writeElement(tag, 
				TextXMLCodec.formatDateTime(dateTime));
	}

	public void writeIntegerElement(String tag, Integer value) throws XMLStreamException {
		writeElement(tag, value.toString());
	}
}
