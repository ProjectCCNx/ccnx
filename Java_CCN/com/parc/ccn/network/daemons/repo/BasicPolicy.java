package com.parc.ccn.network.daemons.repo;

import java.io.InputStream;
import java.util.ArrayList;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;

import com.parc.ccn.data.ContentName;

/**
 * 
 * @author rasmusse
 *
 */
public class BasicPolicy implements Policy {
	
	private ArrayList<ContentName> nameSpace = new ArrayList<ContentName>(0);

	public void update(InputStream stream) throws XMLStreamException {
		XMLInputFactory factory = XMLInputFactory.newInstance();
		XMLEventReader reader = factory.createXMLEventReader(stream);
		XMLEvent event = reader.nextEvent();
		if (!event.isStartDocument()) {
			throw new XMLStreamException("Expected start document, got: " + event.toString());
		}
		do {
			event = reader.nextEvent();
		} while (!event.isEndDocument());
	}

	public ArrayList<ContentName> getNameSpace() {
		return nameSpace;
	}
	
}
