package com.parc.ccn.data.util;

import java.sql.Timestamp;
import java.text.ParseException;

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
	
	public Timestamp readDateTime(String startTag) throws XMLStreamException {
		String strTimestamp = readUTF8Element(startTag);
		Timestamp timestamp;
		try {
			timestamp = TextXMLCodec.parseDateTime(strTimestamp);
		} catch (ParseException e) {
			timestamp = null;
		}
		if (null == timestamp) {
			throw new XMLStreamException("Cannot parse timestamp: " + strTimestamp);
		}		
		return timestamp;
	}
}