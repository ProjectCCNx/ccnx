package com.parc.ccn.network.daemons.repo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.XMLEvent;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.library.CCNLibrary;

/**
 * 
 * @author rasmusse
 *
 */
public class BasicPolicy implements Policy {
	
	public static final String POLICY = "POLICY";
	
	private String _version = null;
	private String _hostName = null;
	private byte [] _content = null;
	private boolean hostNameSet = false;
	
	public BasicPolicy() {
		try {
			_hostName = InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			try {
				_hostName = InetAddress.getLocalHost().getHostAddress();
			} catch (UnknownHostException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
				return;
			}
		}
	}
	
	private enum PolicyValue {
		VERSION ("VERSION"),
		NAMESPACE ("NAMESPACE"),
		HOSTNAME ("HOSTNAME"),
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

	public boolean update(InputStream stream) throws XMLStreamException, IOException {
		_content = new byte[stream.available()];
		stream.read(_content);
		stream.close();
		ByteArrayInputStream bais = new ByteArrayInputStream(_content);
		XMLInputFactory factory = XMLInputFactory.newInstance();
		XMLEventReader reader = factory.createXMLEventReader(bais);
		XMLEvent event = reader.nextEvent();
		_version = null;
		hostNameSet = false;
		if (!event.isStartDocument()) {
			throw new XMLStreamException("Expected start document, got: " + event.toString());
		}
		try {
			parseXML(reader, null, null, POLICY, false);
		} catch (RepositoryException e) {
			return false; // wrong hostname - i.e. not for us
		}
		reader.close();
		if (_version == null)
			throw new XMLStreamException("No version in policy file");
		if (!hostNameSet)
			throw new XMLStreamException("No hostname in policy file");
		return true;
	}

	public ArrayList<ContentName> getNameSpace() {
		return nameSpace;
	}
	
	/**
	 * For now we only expect the values "policy", "version", and "namespace"
	 * @param reader
	 * @param expectedValue
	 * @return
	 * @throws XMLStreamException
	 * @throws RepositoryException 
	 */
	private XMLEvent parseXML(XMLEventReader reader, XMLEvent event, String value, String expectedValue, boolean started) 
				throws XMLStreamException, RepositoryException {
		if (started) {
			switch (PolicyValue.valueFromString(value)) {
			case VERSION:
				QName id = new QName("id");
				Attribute idAttr = event.asStartElement().getAttributeByName(id);
				if (idAttr != null) {
					if (!idAttr.getValue().trim().equals("1.0"))
						throw new XMLStreamException("Bad version in policy file");
					_version = value;
				}
				break;
			default:
				break;
			}
		}
		
		event = reader.nextEvent();
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
				} else {
					event = parseXML(reader, event, startValue, null, true);
				}
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
					case HOSTNAME:
						charValue = event.asCharacters().getData();
						String hostName = charValue.trim();
						if (!hostName.equals(_hostName))
							throw new RepositoryException("Wrong host");
						hostNameSet = true;
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

	public String getHostname() {
		return _hostName;
	}

	public ContentObject getPolicyContent() {
		try {
			return CCNLibrary.getContent(ContentName.fromNative(Repository.REPO_POLICY), _content);
		} catch (MalformedContentNameStringException e) {return null;}	// shouldn't happen
	}
}
