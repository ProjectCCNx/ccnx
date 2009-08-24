package org.ccnx.ccn.impl.repo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.XMLEvent;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


/**
 * 
 * @author rasmusse
 *
 */
public class BasicPolicy implements Policy {
	
	public static final String POLICY = "POLICY";
	
	private String _version = null;
	private byte [] _content = null;
	private ContentName _globalPrefix = null;
	private ContentName _localName = null;
	private boolean _localNameMatched = false;
	private boolean _globalNameMatched = false;
	private boolean _nameSpaceChangeRequest = false;
	
	protected String _repoVersion = null;	// set from repo
	
	private ArrayList<ContentName> _nameSpace = new ArrayList<ContentName>(0);
	private ArrayList<ContentName> _prevNameSpace = new ArrayList<ContentName>(0);
	
	public BasicPolicy(String name) {
		try {
			if (null != name)
				this._localName = ContentName.fromNative(name);
			_nameSpace.add(ContentName.fromNative("/"));
		} catch (MalformedContentNameStringException e) {}
	}
	
	private enum PolicyValue {
		VERSION ("VERSION"),
		NAMESPACE ("NAMESPACE"),
		GLOBALNAME ("GLOBALNAME"),
		LOCALNAME ("LOCALNAME"),
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
	
	public boolean update(InputStream stream, boolean fromNet) throws XMLStreamException, IOException {
		_content = new byte[stream.available()];
		stream.read(_content);
		stream.close();
		ByteArrayInputStream bais = new ByteArrayInputStream(_content);
		XMLInputFactory factory = XMLInputFactory.newInstance();
		XMLEventReader reader;
		try {
			reader = factory.createXMLEventReader(bais);
		} catch (XMLStreamException xse) {
			return false;	// Wasn't really an update stream (probably a header)
		}
		
		Log.info("Policy file update requested");
		XMLEvent event = reader.nextEvent();
		_version = null;
		_localNameMatched = false;
		_globalNameMatched = false;
		_nameSpaceChangeRequest = false;
		if (!event.isStartDocument()) {
			throw new XMLStreamException("Expected start document, got: " + event.toString());
		}
		try {
			parseXML(reader, null, null, POLICY, false, fromNet);
		} catch (RepositoryException e) {
			return false; // wrong hostname - i.e. not for us
		}
		reader.close();
		if (_version == null)
			throw new XMLStreamException("No version in policy file");
		if (!_localNameMatched)
			throw new XMLStreamException("No local name in policy file");
		if (!_globalNameMatched)
			throw new XMLStreamException("No global name in policy file");
		return true;
	}

	public ArrayList<ContentName> getNameSpace() {
		return _nameSpace;
	}
	
	/**
	 * For now we only expect the values "policy", "version", and "namespace"
	 * @param reader
	 * @param expectedValue
	 * @return
	 * @throws XMLStreamException
	 * @throws RepositoryException 
	 */
	@SuppressWarnings("unchecked")
	private XMLEvent parseXML(XMLEventReader reader, XMLEvent event, String value, String expectedValue, boolean started,
				boolean fromNet) 
				throws XMLStreamException, RepositoryException {
		if (started) {
			switch (PolicyValue.valueFromString(value)) {
			case VERSION:
				QName id = new QName("id");
				Attribute idAttr = event.asStartElement().getAttributeByName(id);
				if (idAttr != null) {
					if (!idAttr.getValue().trim().equals(_repoVersion)) {
						Log.warning("Bad version in policy file: " + idAttr.getValue().trim());
						throw new XMLStreamException("Bad version in policy file");
					}
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
					event = parseXML(reader, event, startValue, null, true, fromNet);
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
						Log.fine("New namespace requested: " + charValue);
						// Note - need to synchronize on "this" to synchronize with events reading
						// the name space in the policy clients which have access only to this object
						synchronized (this) {
							_prevNameSpace = (ArrayList<ContentName>) _nameSpace.clone();
							if (!_nameSpaceChangeRequest) {
								_nameSpace.clear();
								if (null != _globalPrefix)
									_nameSpace.add(_globalPrefix);
								_nameSpaceChangeRequest = true;
							}
							try {
								_nameSpace.add(ContentName.fromNative(charValue.trim()));
							} catch (MalformedContentNameStringException e) {
								_nameSpace = _prevNameSpace;
								throw new XMLStreamException("Malformed value in namespace: " + charValue);
							}
						}
						break;
					case LOCALNAME:
						try {
							charValue = event.asCharacters().getData();
							String localName = charValue.trim();
							if (fromNet) {
									if (!ContentName.fromNative(fixSlash(localName)).equals(_localName)) {
										Log.warning("Repository local name doesn't match: request = " + localName);
										throw new RepositoryException("Repository local name doesn't match");
									}
								
							} else
								_localName = ContentName.fromNative(fixSlash(localName));
						} catch (MalformedContentNameStringException e) {
							throw new RepositoryException(e.getMessage());
						}
						_localNameMatched = true;
						break;
					case GLOBALNAME:
						charValue = event.asCharacters().getData();
						String globalName = charValue.trim();
						try {
						if (fromNet) {
							if (!ContentName.fromNative(fixSlash(globalName)).equals(_globalPrefix)) {
								Log.warning("Repository local name doesn't match: request = " + globalName);
								throw new RepositoryException("Repository global name doesn't match");
							}
						} else {
							changeGlobalPrefix(globalName);
						}
						} catch (MalformedContentNameStringException e) {
							throw new RepositoryException(e.getMessage());
						}
						_globalNameMatched = true;
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

	public ContentObject getPolicyContent() {
		try {
			// TODO WARNING: this code should not call a generic content builder meant for
			// making test content. The repository needs to have its own set of keys, manage
			// them and use them rather than using the default key manager (which will pull
			// keys from whatever user keystore that started the repo). The repo should build
			// a keystore for its own use, and when it starts up instantiate a key manager
			// that uses that keystore. That key manager should be used for all of its
			// operations.
			return ContentObject.buildContentObject(ContentName.fromNative(_globalPrefix + "/" + _localName +
					"/" + Repository.REPO_DATA + "/" + Repository.REPO_POLICY), 
					_content);
		} catch (MalformedContentNameStringException e) {
			// shouldn't happen 
			// TODO DKS: if it shouldn't happen, throw a big warning if it does -- don't silently
			// return null, which could cause all sorts of cascading problems. 
			Log.severe("SHOULD NOT HAPPEN: Unexpected MalformedContentNameStringException: " + e.getMessage());
			return null;
		}	
	}

	public void setVersion(String version) {
		_repoVersion = version;
	}

	public void setGlobalPrefix(String globalPrefix) throws MalformedContentNameStringException {
		if (null == _globalPrefix) {
			changeGlobalPrefix(globalPrefix);
		}
	}

	public void setLocalName(String localName) throws MalformedContentNameStringException {
		if (null == _localName) {
			_localName = ContentName.fromNative(fixSlash(localName));
		}
	}
	
	private String fixSlash(String name) {
		if (!name.startsWith("/"))
			name = "/" + name;
		return name;
	}
	
	private void changeGlobalPrefix(String globalPrefix) throws MalformedContentNameStringException {
		// Note - need to synchronize on "this" to synchronize with events reading
		// the name space in the policy clients which have access only to this object
		synchronized (this) {
			if (null != _globalPrefix)
				_nameSpace.remove(_globalPrefix);
			_globalPrefix = ContentName.fromNative(fixSlash(globalPrefix));
			_nameSpace.add(_globalPrefix);
		}
	}
}
