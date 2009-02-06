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
	
	private String _version;
	
	private enum PolicyValue {
		VERSION ("VERSION"),
		NAMESPACE ("NAMESPACE"),
		UNKNOWN();
		
		private String _stringValue = null;
		
		PolicyValue() {}
		
		PolicyValue(String stringValue) {
			this._stringValue = stringValue;
		}
		
		static PolicyValue valueFromString(String value) {
			for (PolicyValue pv : PolicyValue.values()) {
				if (pv._stringValue != null) {
					if (pv._stringValue.equals(value.toUpperCase()))
						return pv;
				}
			}
			return UNKNOWN;
		}
	}
	
	private ArrayList<ContentName> nameSpace = new ArrayList<ContentName>(0);

	public void update(InputStream stream) throws XMLStreamException {
		XMLInputFactory factory = XMLInputFactory.newInstance();
		XMLEventReader reader = factory.createXMLEventReader(stream);
		XMLEvent event = reader.nextEvent();
		_version = null;
		if (!event.isStartDocument()) {
			throw new XMLStreamException("Expected start document, got: " + event.toString());
		}
		parseXML(reader, null, POLICY, false);
		reader.close();
		if (_version == null)
			throw new XMLStreamException("No version in policy file");
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
	private XMLEvent parseXML(XMLEventReader reader, String value, String expectedValue, boolean started) throws XMLStreamException {
		XMLEvent event = reader.nextEvent();
		boolean finished = false;
		while (!finished) {
			if (event.isStartElement()) {
				String startValue = event.asStartElement().getName().toString();
				if (expectedValue != null) {
					if (!startValue.toUpperCase().equals(expectedValue.toUpperCase()))
						throw new XMLStreamException("Expected " + expectedValue + ", got: " + value);
					event = reader.nextEvent();
					value = expectedValue;
					expectedValue = null;
				} else
					event = parseXML(reader, startValue, null, true);
			} else if (event.isEndElement()) {
				String newValue = event.asEndElement().getName().toString();
				if (!newValue.toUpperCase().equals(value.toUpperCase()))
					throw new XMLStreamException("Expected end of " + value + ", got: " + newValue);
				event = reader.nextEvent();
				finished = true;
			} else if (event.isCharacters()) {
				if (started) {
					switch (PolicyValue.valueFromString(value)) {
					case NAMESPACE:
						String charValue = event.asCharacters().getData();
						try {
							nameSpace.add(ContentName.fromNative(charValue.trim()));
						} catch (MalformedContentNameStringException e) {
							throw new XMLStreamException("Malformed value in namespace: " + charValue);
						}
						break;
					case VERSION:
						charValue = event.asCharacters().getData();
						if (!charValue.trim().equals("1.0"))
							throw new XMLStreamException("Bad version in policy file");
						_version = value;
						break;
					default:
						break;
					}
				}
				event = reader.nextEvent();
			} else if (event.isEndDocument()) {
				finished = true;
			}
		}
		return event;
	}
}
