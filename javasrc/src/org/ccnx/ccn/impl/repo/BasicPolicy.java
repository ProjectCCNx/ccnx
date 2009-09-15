/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

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
 * A reference implementation of Policy using an XML based policy file
 * 
 * TODO policy change requests via the network are currently totally insecure and could easily be 
 * exploited to disrupt the proper functioning of a repository.
 * 
 * The policy file is structured as follows:
 * 
 * All elements must be nested within a <policy> element.<p>
 * 
 * The file must contain a <version> element with an id attribute set to a string
 * corresponding to the current version of the repository which is "1.4".<p>
 * 
 * The file must contain a <localname> element which specifies the local name.<p>
 * The file must contain a <globalname> element which specifies the global name.<p>
 * The file may contain any number of <namespace> elements specifying namespaces<p>
 * covered by the repository
 * 
 * <pre>
 * For example:
 * 	<policy>
 *		<version id="1.4"/>
 *		<localname> TestRepository </localname>
 *		<globalname> parc.com/csl/ccn/repositories </globalname>
 *		<namespace> /testNameSpace </namespace>
 *		<namespace> /testNameSpace2 </namespace>
 * 	</policy>
 * </pre>
 * 
 */
public class BasicPolicy implements Policy {
	
	public static final String POLICY = "POLICY";
	
	private String _version = null;
	private byte [] _content = null;
	private ContentName _globalPrefix = null;
	private String _localName = null;
	private boolean _localNameMatched = false;
	private boolean _globalNameMatched = false;
	private boolean _nameSpaceChangeRequest = false;
	
	protected String _repoVersion = null;	// set from repo
	
	private ArrayList<ContentName> _nameSpace = new ArrayList<ContentName>(0);
	private ArrayList<ContentName> _prevNameSpace = new ArrayList<ContentName>(0);
	
	/**
	 * Constructor defaulting the initial namespace to "everything"
	 * @param name local name for this repository
	 */
	public BasicPolicy(String name) {
		try {
			if (null != name)
				this._localName = name;
			_nameSpace.add(ContentName.fromNative("/"));
		} catch (MalformedContentNameStringException e) {}
	}
	
	/**
	 * Constructor allowing an initial namespace to be set
	 * @param name local name for this repository
	 * @param namespace the initial namespace
	 */
	@SuppressWarnings("unchecked")
	public BasicPolicy(String name, ArrayList<ContentName> namespace) {
		if (null != name) {
			this._localName = name;
		}
		if (null != namespace) {
			_nameSpace = (ArrayList<ContentName>) namespace.clone();
		}
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
	
	/**
	 * Reads, parses and applies a new policy file. This can be called to read a policy file specified
	 * during repository startup or a file sent via the network.
	 * 
	 * @param stream	The policy file stream
	 * @param fromNet	true if the policy file was submitted over the network
	 * @throws XMLStreamException if the xml is invalid or doesn't contain a version, local name or global name
	 * @throws IOException on file read errors
	 */
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
	 * Parses the XML file
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
						charValue = event.asCharacters().getData();
						String localName = charValue.trim();
						if (fromNet) {
							if (!localName.equals(_localName)) {
								Log.warning("Repository local name doesn't match: request = " + localName);
								throw new RepositoryException("Repository local name doesn't match");
							}

						} else {
							_localName = localName;
						}
						_localNameMatched = true;
						break;
					case GLOBALNAME:
						charValue = event.asCharacters().getData();
						String globalName = charValue.trim();
						try {
						if (fromNet) {
							if (!ContentName.fromNative(fixSlash(globalName)).equals(_globalPrefix)) {
								Log.warning("Repository global name doesn't match: request = " + globalName);
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

	/**
	 * Gets the current policy as a ContentObject
	 * @return the policy content
	 */
	public ContentObject getPolicyContent() {
		// TODO WARNING: this code should not call a generic content builder meant for
		// making test content. The repository needs to have its own set of keys, manage
		// them and use them rather than using the default key manager (which will pull
		// keys from whatever user keystore that started the repo). The repo should build
		// a keystore for its own use, and when it starts up instantiate a key manager
		// that uses that keystore. That key manager should be used for all of its
		// operations.
		return ContentObject.buildContentObject(
				getPolicyName(_globalPrefix, _localName),
				_content);
	}
	
	/**
	 * Creates the path for a policy file for a repository given it's global prefix and local name
	 * 
	 * @param globalPrefix global prefix as a ContentName
	 * @param localName local name as a / separated String
	 * @return the name as a ContentName
	 */
	public static ContentName getPolicyName(ContentName globalPrefix, String localName) {
		return ContentName.fromNative(globalPrefix, new String[]{localName, RepositoryStore.REPO_DATA, RepositoryStore.REPO_POLICY});
	}
	
	/**
	 * Gets the policy path for this repository
	 * @return the policy path as a ContentName
	 */
	public ContentName getPolicyName() { return getPolicyName(_globalPrefix, _localName); }

	/**
	 * Sets the repository version to be used by this policy interpreter. After this call any
	 * policy file containing a different version will be rejected.
	 * 
	 * @param version The version as a String
	 */
	public void setVersion(String version) {
		_repoVersion = version;
	}

	/**
	 * Sets the global prefix for this policy interpreter. After this call any policy file
	 * containing a different global prefix will be rejected
	 *
	 * @param globalPrefix the global prefix as a slash separated String
	 */
	public void setGlobalPrefix(String globalPrefix) throws MalformedContentNameStringException {
		if (null == _globalPrefix) {
			changeGlobalPrefix(globalPrefix);
		}
	}
	
	/**
	 * Gets the global prefix currently in use for this repository
	 * 
	 * @return the global prefix as a ContentName
	 */
	public ContentName getGlobalPrefix() { return _globalPrefix; }
	
	/**
	 * Gets the local name currently used by this repository
	 * 
	 * @return the local name as a slash separated String
	 */
	public String getLocalName() { return _localName; }

	public void setLocalName(String localName) throws MalformedContentNameStringException {
		if (null == _localName) {
			_localName = localName;
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
