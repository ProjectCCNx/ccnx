package org.ccnx.ccn.impl.encoding;

import javax.xml.stream.XMLStreamException;

public abstract class GenericXMLDecoder implements XMLDecoder {

	public Integer readIntegerElement(String startTag) throws XMLStreamException {
		String strVal = readUTF8Element(startTag); 
		Integer value = Integer.valueOf(strVal);
		if (null == value) {
			throw new XMLStreamException("Cannot parse " + startTag + ": " + strVal);
		}
		return value;
	}
	
}