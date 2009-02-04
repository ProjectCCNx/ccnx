package com.parc.ccn.network.daemons.repo;

import java.io.InputStream;
import java.util.ArrayList;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;

/**
 * 
 * @author rasmusse
 *
 */
public class BasicPolicy implements Policy {
	
	public static final String POLICY = "POLICY";
	public static final String NAMESPACE = "NAMESPACE";
	
	private ArrayList<ContentName> nameSpace = new ArrayList<ContentName>(0);

	public void update(InputStream stream) throws XMLStreamException {
		XMLInputFactory factory = XMLInputFactory.newInstance();
		XMLEventReader reader = factory.createXMLEventReader(stream);
		XMLEvent event = reader.nextEvent();
		if (!event.isStartDocument()) {
			throw new XMLStreamException("Expected start document, got: " + event.toString());
		}
		do {
			event = parseEvent(reader, POLICY);
		} while (!event.isEndDocument());
		reader.close();
	}

	public ArrayList<ContentName> getNameSpace() {
		return nameSpace;
	}
	
	/**
	 * For now we only expect the values "policy" and "namespace"
	 * @param reader
	 * @param expectedValue
	 * @return
	 * @throws XMLStreamException
	 */
	private XMLEvent parseEvent(XMLEventReader reader, String expectedValue) throws XMLStreamException {
		String value = null;
		XMLEvent event = reader.nextEvent();
		if (expectedValue != null) {
			while (!event.isEndDocument() && !event.isStartElement())
				event = reader.nextEvent();
		}
		while (event.isStartElement()) {
			value = event.asStartElement().getName().toString();
			if (expectedValue != null) {
				if (!value.toUpperCase().equals(expectedValue))
					throw new XMLStreamException("Expected " + expectedValue + ", got: " + value);
			}
			event = parseEvent(reader, null);
			expectedValue = null;
		}
		if (event.isEndElement()) {
			String newValue = event.asEndElement().getName().toString();
			if (!newValue.equals(value))
				throw new XMLStreamException("Expected end of " + value + ", got: " + newValue);
			event = reader.nextEvent();
		} else if (event.isCharacters()) {
			if (value != null && value.toUpperCase().equals(NAMESPACE)) {
				value = event.asCharacters().getData();
				try {
					nameSpace.add(ContentName.fromNative(value));
				} catch (MalformedContentNameStringException e) {
					throw new XMLStreamException("Malformed value in namespace: " + value);
				}
			}
			event = reader.nextEvent();
		}
		return event;
	}
}
