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

import java.io.IOException;
import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.CCNEncodableObject;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * Represents repo policy data
 */
public class PolicyXML extends GenericXMLEncodable implements XMLEncodable {
	
	public static class PolicyObject extends CCNEncodableObject<PolicyXML> {
		
		protected RepositoryStore _repo = null;	// Non null if we are saving from within a repository
		
		public PolicyObject(ContentName name, PolicyXML data, CCNHandle handle, RepositoryStore repo) throws IOException {
			super(PolicyXML.class, true, name, data, handle);
			_repo = repo;
		}
		
		public PolicyObject(ContentName name, PolicyXML data, CCNHandle handle) throws IOException {
			this(name, data, handle, null);
		}
		
		public PolicyObject(ContentName name, CCNHandle handle) 
				throws ContentDecodingException, IOException {
			super(PolicyXML.class, true, name, handle);
		}
		
		public PolicyXML policyXML() throws ContentNotReadyException, ContentGoneException {
			return data();
		}
		
		protected synchronized void createFlowController() throws IOException {
			if (null != _repo)
				_flowControl = new RepositoryInternalFlowControl(_repo, _handle);
			super.createFlowController();
		}
	}
	
	protected static final String POLICY_OBJECT_ELEMENT = "Policy";
	
	/**
	 * The following interface and enumeration allow user created policy files with the
	 * data in any order. The encoder goes through the user's names, checks for matches with
	 * the names from the enumeration and encodes the name it sees appropriately
	 */
	private interface ElementPutter {
		public void put(PolicyXML pxml, String value) throws MalformedContentNameStringException;
	}
	
	private enum PolicyElement {
		VERSION (POLICY_VERSION, new VersionPutter()),
		NAMESPACE (POLICY_NAMESPACE, new NameSpacePutter()),
		LOCALNAME (POLICY_LOCALNAME, new LocalNamePutter()),
		GLOBALPREFIX (POLICY_GLOBALPREFIX, new GlobalPrefixPutter());
		
		private String _stringValue;
		private ElementPutter _putter;
		
		PolicyElement(String stringValue, ElementPutter putter) {
			_stringValue = stringValue;
			_putter = putter;
		}
	}
	
	private static class VersionPutter implements ElementPutter {

		public void put(PolicyXML pxml, String value) throws MalformedContentNameStringException {
			pxml._version = value.trim();
		}
	}
	private static class GlobalPrefixPutter implements ElementPutter {

		public void put(PolicyXML pxml, String value) throws MalformedContentNameStringException {
			pxml._globalPrefix = ContentName.fromNative(fixSlash(value.trim()));
		}
	}
	private static class LocalNamePutter implements ElementPutter {

		public void put(PolicyXML pxml, String value) throws MalformedContentNameStringException {
			pxml._localName = value.trim();
		}
	}
	private static class NameSpacePutter implements ElementPutter {

		public void put(PolicyXML pxml, String value) throws MalformedContentNameStringException {
			if (null == pxml._namespace)
				pxml._namespace = new ArrayList<ContentName>();
			pxml._namespace.add(ContentName.fromNative(value.trim()));
		}
	}
	
	protected static final String POLICY_VERSION = "PolicyVersion";
	protected static final String POLICY_NAMESPACE = "Namespace";
	protected static final String POLICY_GLOBALPREFIX = "GlobalPrefix";
	protected static final String POLICY_LOCALNAME = "LocalName";
	
	protected String _version = null;
	protected ContentName _globalPrefix = null;
	protected String _localName = null;
	
	protected ArrayList<ContentName> _namespace = new ArrayList<ContentName>();

	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());
		PolicyElement foundElement;
		do {
			foundElement = null;
			for (PolicyElement element : PolicyElement.values()) {
				if (decoder.peekStartElement(element._stringValue)) {
					foundElement = element;
					break;
				}		
			}
			if (null != foundElement) {
				String value = decoder.readUTF8Element(foundElement._stringValue);
				try {
					foundElement._putter.put(this, value);
				} catch (MalformedContentNameStringException e) {
					throw new ContentDecodingException(e.getMessage());
				}
				Log.fine("Found policy element {0} with value {1}", foundElement._stringValue, value);
			}
		} while (null != foundElement);
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(getElementLabel());
		encoder.writeElement(POLICY_VERSION, _version);	
		encoder.writeElement(POLICY_LOCALNAME, _localName);
		encoder.writeElement(POLICY_GLOBALPREFIX, _globalPrefix.toString());
		
		if (null != _namespace) {
			synchronized (_namespace) {
				for (ContentName name : _namespace)
					encoder.writeElement(POLICY_NAMESPACE, name.toString());
			}
		}
		encoder.writeEndElement();   			
	}

	@Override
	public String getElementLabel() {
		return POLICY_OBJECT_ELEMENT;
	}

	@Override
	public boolean validate() {
		return null != _version;
	}
	
	public ArrayList<ContentName> getNamespace() {
		return _namespace;
	}
	
	public void setNamespace(ArrayList<ContentName> namespace) {
		_namespace = namespace;
	}
	
	public void addNamespace(ContentName name) {
		if (null == _namespace)
			_namespace = new ArrayList<ContentName>();
		_namespace.add(name);
	}
	
	public void removeNamespace(ContentName name) {
		if (null != _namespace)
			_namespace.remove(name);
	}
	
	public void setLocalName(String localName) {
		_localName = localName;
	}
	
	public String getLocalName() {
		return _localName;
	}
	
	public void setGlobalPrefix(ContentName globalPrefix) {
		_globalPrefix = globalPrefix;
	}
	
	public ContentName getGlobalPrefix() {
		return _globalPrefix;
	}
	
	public void setVersion(String version) {
		_version = version;
	}
	
	public String getVersion() {
		return _version;
	}
	
	/**
	 * Global prefix names are not required to start with a slash. Just add one
	 * here if it doesn't
	 * @param name - the test name
	 * @return
	 */
	public static String fixSlash(String name) {
		if (!name.startsWith("/"))
			name = "/" + name;
		return name;
	}
}
